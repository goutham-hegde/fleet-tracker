#!/usr/bin/env bash
# Point the cluster's archiver at the MinIO running beside it, instead of at S3.
#
# The local counterpart of aws-link.sh, and deliberately the same shape: generate what the
# environment supplies, write it into the two objects the base manifests read, and restart the
# archiver only if what it holds has actually changed. What differs is the whole point of S21 --
# there the pod proved its identity to STS and received credentials nobody stored; here a static
# key is generated and kept in a Secret, because MinIO checks a pair of strings and nothing else.
#
#   ./scripts/local-link.sh          # link, creating credentials on first run
#   ./scripts/local-link.sh --show   # print the credentials, for the AWS CLI against MinIO
#
# Needs: the cluster running with deploy/overlays/local applied (which is what deploys MinIO).
# Idempotent: existing credentials are reused, because MinIO's stored data is only reachable with
# the key it was initialised with.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

require kubectl "Ships with Docker Desktop."
require aws "winget install Amazon.AWSCLI"

BUCKET="fleet-tracker-archive"
REGION="ap-south-1"
ENDPOINT="http://minio.fleet.svc.cluster.local:9000"
# Only used by this script, to reach MinIO through a port-forward. Not a Kind port mapping, which
# could not be added without recreating the cluster.
FORWARD_PORT=19000

kubectl cluster-info >/dev/null 2>&1 || die "The cluster is not reachable. Run ./scripts/cluster-start.sh"
kubectl get namespace fleet >/dev/null 2>&1 || die "No fleet namespace. Run ./scripts/platform-up.sh"

# Empty when the Secret does not exist yet, rather than fatal. lib.sh sets `pipefail`, so a failing
# kubectl inside a pipeline would take the whole script down with it on the very first run -- before
# it could create the thing whose absence it had just established.
secret_value() {
  local raw
  raw="$(kubectl get secret/archive-credentials -n fleet -o jsonpath="{.data.$1}" 2>/dev/null || true)"
  [ -n "$raw" ] || return 0
  printf '%s' "$raw" | base64 -d 2>/dev/null || true
}

if [ "${1:-}" = "--show" ]; then
  access="$(secret_value access-key-id)"
  [ -n "$access" ] || die "No archive-credentials Secret. Run ./scripts/local-link.sh first."
  printf 'AWS_ACCESS_KEY_ID=%s\nAWS_SECRET_ACCESS_KEY=%s\nendpoint (port-forward) http://localhost:%s\n' \
    "$access" "$(secret_value secret-access-key)" "$FORWARD_PORT"
  exit 0
fi

# ------------------------------------------------------------------------------------------------
# 1. Credentials. Reused if they exist: MinIO initialises its store with the root key it first saw,
#    and a new key would leave the existing archive unreadable rather than simply unauthenticated.
log "Credentials for the local archive"
access="$(secret_value access-key-id)"
secret="$(secret_value secret-access-key)"
if [ -n "$access" ] && [ -n "$secret" ]; then
  ok "reusing the existing key (regenerating one would orphan what MinIO already holds)"
else
  random() {
    if command -v openssl >/dev/null 2>&1; then openssl rand -hex "$1"
    else head -c "$1" /dev/urandom | od -An -tx1 | tr -d ' \n'; fi
  }
  access="fleet$(random 6)"
  secret="$(random 24)"
  kubectl create secret generic archive-credentials -n fleet \
    --from-literal=access-key-id="$access" \
    --from-literal=secret-access-key="$secret" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  ok "generated a new key and stored it in fleet/archive-credentials"
fi

# ------------------------------------------------------------------------------------------------
# 2. The destination. Same ConfigMap the AWS path writes, read by the same manifests. The `endpoint`
#    key is what tells everything downstream -- the archiver through its patch, archive-replay.sh
#    through a branch -- that this is a local store with no STS behind it. Its absence means AWS.
log "Writing the archive-destination ConfigMap"
destination() { kubectl get configmap/archive-destination -n fleet -o jsonpath='{.data}' 2>/dev/null || true; }
before="$(destination)"
kubectl create configmap archive-destination -n fleet \
  --from-literal=bucket="$BUCKET" \
  --from-literal=region="$REGION" \
  --from-literal=endpoint="$ENDPOINT" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
ok "fleet/archive-destination -> $ENDPOINT/$BUCKET"

# ------------------------------------------------------------------------------------------------
# 3. The bucket. MinIO has to be running to take the request, and it cannot start until the Secret
#    above exists -- which is why this comes third rather than first.
if ! kubectl get statefulset/minio -n fleet >/dev/null 2>&1; then
  warn "MinIO is not deployed yet. Apply the overlay and run this again:"
  warn "  kubectl apply -k deploy/overlays/local && ./scripts/local-link.sh"
  exit 0
fi

log "Waiting for MinIO"
node_pull quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z
kubectl rollout status statefulset/minio -n fleet --timeout=180s >/dev/null \
  || die "MinIO did not become ready. kubectl describe pod/minio-0 -n fleet"
ok "minio-0 ready"

log "Creating the bucket"
# A port-forward rather than a host port: Kind's mappings are fixed when the cluster is created.
kubectl port-forward -n fleet service/minio "$FORWARD_PORT:9000" >/dev/null 2>&1 &
forward_pid=$!
trap 'kill "$forward_pid" 2>/dev/null || true' EXIT
for _ in $(seq 1 30); do
  curl -fsS -m 2 "http://localhost:$FORWARD_PORT/minio/health/live" >/dev/null 2>&1 && break
  sleep 1
done
curl -fsS -m 2 "http://localhost:$FORWARD_PORT/minio/health/live" >/dev/null 2>&1 \
  || die "Could not reach MinIO through the port-forward on $FORWARD_PORT. Is something else on it?"

# Explicit credentials in the environment, and AWS_PROFILE cleared: this must talk to MinIO with
# the key just generated, never to AWS with whatever `aws login` last left behind -- expired or not.
unset AWS_PROFILE AWS_SESSION_TOKEN
export AWS_ACCESS_KEY_ID="$access" AWS_SECRET_ACCESS_KEY="$secret" AWS_DEFAULT_REGION="$REGION"
if aws s3api head-bucket --bucket "$BUCKET" --endpoint-url "http://localhost:$FORWARD_PORT" >/dev/null 2>&1; then
  ok "s3://$BUCKET already exists"
else
  aws s3api create-bucket --bucket "$BUCKET" \
    --endpoint-url "http://localhost:$FORWARD_PORT" \
    --create-bucket-configuration "LocationConstraint=$REGION" >/dev/null \
    || die "Could not create s3://$BUCKET in MinIO."
  ok "s3://$BUCKET created"
fi

kill "$forward_pid" 2>/dev/null || true
trap - EXIT

# ------------------------------------------------------------------------------------------------
# 4. The archiver. Environment variables are read once, at container start, so a destination that
#    has changed under a running pod needs a restart -- and one that has not must not get one, or
#    every link would cost a rebalance and a flush. On a first link the pod is still waiting in
#    CreateContainerConfigError and starts by itself.
if [ -z "$before" ]; then
  ok "first link: a waiting archiver starts by itself"
elif [ "$before" != "$(destination)" ] && kubectl get deployment/archiver -n fleet >/dev/null 2>&1; then
  kubectl rollout restart deployment/archiver -n fleet >/dev/null
  ok "destination changed: archiver restarted to pick it up"
else
  ok "destination unchanged: archiver left running"
fi

ok "The archive is local. Inspect it with: ./scripts/local-link.sh --show"
