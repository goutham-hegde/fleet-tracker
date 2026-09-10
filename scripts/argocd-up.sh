#!/usr/bin/env bash
# Install ArgoCD and hand it this repository.
#
# Everything before M7 pushed: a person, or a script a person ran, connected to the cluster and
# told it what to run. That works exactly as long as somebody can reach the cluster's API server --
# and this one is a Kind node on a laptop behind a home router, with no public address and no port
# forwarded to it. A CI job on GitHub's runners cannot connect to it, and arranging for it to be
# able to would mean punching a hole in a home network so that a machine on the internet could
# administer it, which is a bad trade even when it is easy.
#
# So the direction is reversed. ArgoCD runs *inside* the cluster, polls a public git repository
# every few minutes, and applies what it finds. The only connection is outbound, from the laptop to
# github.com, which is the same connection a `git pull` makes. Nothing needs to reach in. The
# laptop can be asleep for a day and will catch up when it wakes.
#
# Idempotent. Re-running it re-applies the same manifests and the same Application.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

require kubectl "Ships with Docker Desktop."
require kind "winget install Kubernetes.kind"

# Pinned, for the same reason KEDA is: a cluster component that follows a moving "latest" is a
# cluster that changes underneath you between two runs of the same script.
ARGOCD_VERSION="v3.5.2"
ARGOCD_MANIFEST="https://raw.githubusercontent.com/argoproj/argo-cd/${ARGOCD_VERSION}/manifests/install.yaml"
ARGOCD_NS="argocd"

kind get clusters 2>/dev/null | grep -qx "$CLUSTER_NAME" \
  || die "Cluster '$CLUSTER_NAME' does not exist. Run ./scripts/cluster-up.sh"
kubectl config use-context "kind-$CLUSTER_NAME" >/dev/null

# The overlay ArgoCD will apply contains a ScaledObject, so KEDA has to exist first for exactly the
# reason it does in stack-up.sh -- otherwise the sync fails with `no matches for kind`, which reads
# like a broken manifest and is a missing operator. Failing here, in one line, beats failing later
# inside a controller's log.
kubectl get crd scaledobjects.keda.sh >/dev/null 2>&1 \
  || die "KEDA is not installed. Run ./scripts/keda-up.sh first."

log "Installing ArgoCD $ARGOCD_VERSION"
kubectl create namespace "$ARGOCD_NS" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
# --server-side is not optional here. The install manifest carries several CustomResourceDefinitions
# whose schemas are hundreds of kilobytes; a client-side apply stores the whole thing in a
# last-applied-configuration annotation, and annotations are capped at 256 kB. The failure is
# "metadata.annotations: Too long", which names nothing useful.
kubectl apply --server-side -n "$ARGOCD_NS" -f "$ARGOCD_MANIFEST" >/dev/null
ok "manifests applied"

log "Waiting for the controllers"
# The repo-server is the one that clones the repository and runs kustomize over it; the application
# controller is the one that compares the result with the cluster and acts. Both must be up before
# an Application means anything. The API server is what the UI and the CLI talk to.
kubectl rollout status statefulset/argocd-application-controller -n "$ARGOCD_NS" --timeout=300s
kubectl rollout status deployment/argocd-repo-server            -n "$ARGOCD_NS" --timeout=300s
kubectl rollout status deployment/argocd-server                 -n "$ARGOCD_NS" --timeout=300s

log "Registering the fleet-tracking application"
kubectl apply -f "$REPO_ROOT/deploy/argocd/application.yaml"

echo
kubectl get pods -n "$ARGOCD_NS"
echo
kubectl get application -n "$ARGOCD_NS"

echo
ok "ArgoCD is watching the 'deploy' branch of goutham-hegde/fleet-tracker."
ok "  It polls every three minutes by default, so a push takes up to that long to appear."
ok "  Force a check now:   kubectl -n argocd annotate app/fleet-tracking argocd.argoproj.io/refresh=hard --overwrite"
echo
ok "  Watch it:            kubectl get application -n argocd -w"
ok "  What it thinks:      kubectl describe application/fleet-tracking -n argocd"
echo
ok "  The web UI needs a port-forward, because the Kind node's port mappings are fixed at cluster"
ok "  creation and adding one means recreating the cluster:"
ok "    kubectl port-forward -n argocd service/argocd-server 8090:443"
ok "    then https://localhost:8090  (self-signed certificate; the browser will complain)"
ok "  Username 'admin'; the initial password is generated into a Secret:"
ok "    kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d"
