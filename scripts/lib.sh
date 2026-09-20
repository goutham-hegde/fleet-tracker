#!/usr/bin/env bash
# Shared helpers. Source this, don't execute it.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLUSTER_NAME="fleet-tracking"
KIND_CONFIG="$REPO_ROOT/deploy/kind-cluster.yaml"

# A path a Windows program can open.
#
# Under Git Bash, $REPO_ROOT is /g/project/fleet-tracking. The AWS CLI is a Windows binary and
# cannot open that -- it needs G:/project/fleet-tracking -- and the failure is a confusing
# "No such file or directory" naming a path that plainly exists. `cygpath -m` gives the Windows
# drive with forward slashes, which is what `fileb://` and the rest accept.
#
# On Linux there is no cygpath and the path is already right, so this returns it unchanged. That
# matters: CI runs these same scripts on ubuntu.
native() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

# winget installs tools into per-package directories and updates the *user* PATH,
# which existing shells do not see until they restart. Add them here so scripts
# work in the same shell session an install happened in.
#
# $LOCALAPPDATA is a Windows path with backslashes, which bash cannot use as a
# directory, so convert it with cygpath and fall back to $HOME otherwise.
if [ -n "${LOCALAPPDATA:-}" ] && command -v cygpath >/dev/null 2>&1; then
  _localappdata="$(cygpath -u "$LOCALAPPDATA")"
else
  _localappdata="$HOME/AppData/Local"
fi
_winget_pkgs="$_localappdata/Microsoft/WinGet/Packages"

for _d in \
  "$_winget_pkgs/Kubernetes.kind_Microsoft.Winget.Source_8wekyb3d8bbwe" \
  "$_winget_pkgs/Helm.Helm_Microsoft.Winget.Source_8wekyb3d8bbwe/windows-amd64" \
  "$_winget_pkgs/Hashicorp.Terraform_Microsoft.Winget.Source_8wekyb3d8bbwe" \
  "$_localappdata/Programs/mongosh" \
  "/c/Program Files/GitHub CLI" \
  "/c/Program Files/Amazon/AWSCLIV2" ; do
  if [ -d "$_d" ]; then
    case ":$PATH:" in *":$_d:"*) ;; *) PATH="$PATH:$_d" ;; esac
  fi
done
export PATH

log()  { printf '\033[0;36m==>\033[0m %s\n' "$*"; }
ok()   { printf '\033[0;32m  ok\033[0m %s\n' "$*"; }
warn() { printf '\033[0;33m  !!\033[0m %s\n' "$*"; }
die()  { printf '\033[0;31m ERR\033[0m %s\n' "$*" >&2; exit 1; }

require() {
  command -v "$1" >/dev/null 2>&1 || die "'$1' not found on PATH. $2"
}

# Download images into the Kind node before anything waits on the pods that use them.
#
# A new node has no images, and on a slow connection Kafka and MongoDB alone took fifteen minutes to
# arrive (S24's fresh-clone test). A rollout wait with a timeout cannot tell "downloading" from
# "broken", so the first run on a new machine failed with nothing but "timed out waiting for the
# condition". Pulling first, with no timeout and a line saying what is happening, keeps every later
# wait about the application. Images already on the node are skipped, so this costs nothing on a
# cluster that has run before.
node_pull() {
  local node="$CLUSTER_NAME-control-plane" image ref
  for image in "$@"; do
    [ -n "$image" ] || continue
    case "$image" in
      [!/]*.[!/]*/*) ref="$image" ;;             # the first segment is a registry: ghcr.io/..., registry.k8s.io/...
      */*)   ref="docker.io/$image" ;;
      *)     ref="docker.io/library/$image" ;;
    esac
    docker exec "$node" crictl inspecti "$ref" >/dev/null 2>&1 && continue
    log "Downloading $ref into the cluster (first run only; can take minutes)"
    docker exec "$node" crictl pull "$ref" >/dev/null || die "could not download $ref"
  done
}

# Derived state, and how to clear it safely. Used by stack-up.sh on every full run and by
# load-test.sh before a pod-kill run.
#
# A stop that has been arrived at and departed from is terminal -- S10's rule, working exactly as
# designed. So re-running the simulator over the same shipment ids produces a fleet in which every
# marker already reads DELIVERED and nothing ever moves: a demonstration in which the platform is
# correct and there is nothing to see. These six collections are all rebuilt from the stream.
#
# Reference data (assignments, itinerary, manifests, schemas) is NOT in this list. That is the
# platform's input rather than its conclusions, it is what the seed scripts write, and dropping it
# would leave the gateway dead-lettering every message it received.
DERIVED_COLLECTIONS='["geofence.state","shipment.eta","shipment.position","exception.state","exceptions","position.history"]'

# Leaves the tracking processor at zero replicas with KEDA's pause lifted, so KEDA brings it back
# on its next loop; stack-up.sh's rollout (or the caller) waits for it.
reset_derived_state() {
  # The tracking processor must not be running for this. position.history is a MongoDB time-series
  # collection, and an insert into a missing collection silently creates an ordinary one -- no
  # buckets, no compression, no error. Dropping it under a live consumer is precisely the failure
  # S10 hit for real. Scaling the deployment to zero first makes the ordering explicit rather than
  # hopeful.
  if kubectl get deployment/tracking-processor -n fleet >/dev/null 2>&1; then
    log "Stopping the tracking processor before touching its time-series collection"
    # KEDA owns this deployment's replica count, so it would restore the pod on its next loop. The
    # pause annotation is how you tell it not to, and it is removed again after the drop.
    kubectl annotate scaledobject/tracking-processor -n fleet \
      autoscaling.keda.sh/paused-replicas="0" --overwrite >/dev/null 2>&1 || true
    kubectl scale deployment/tracking-processor -n fleet --replicas=0 >/dev/null
    kubectl wait --for=delete pod -l app.kubernetes.io/name=tracking-processor -n fleet --timeout=90s >/dev/null 2>&1 || true
  fi

  log "Clearing derived state"
  mongosh "mongodb://localhost:37017" --quiet --eval "
    const d = db.getSiblingDB('fleet');
    ${DERIVED_COLLECTIONS}.forEach(c => d.getCollection(c).drop());
  " >/dev/null
  ok "position history, geofence state, ETAs and incidents cleared"

  kubectl annotate scaledobject/tracking-processor -n fleet \
    autoscaling.keda.sh/paused-replicas- >/dev/null 2>&1 || true
}
