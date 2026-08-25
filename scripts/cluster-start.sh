#!/usr/bin/env bash
# Restart a stopped Kind cluster and wait until it is genuinely usable.
#
# Kubernetes needs a moment to settle after a restart. In particular the
# local-path storage provisioner reliably comes back in Error state and is
# restarted automatically a few seconds later, so a pod looking broken
# immediately after start is expected, not a problem.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

require docker  "Start Docker Desktop."
require kubectl "Ships with Docker Desktop."

node="${CLUSTER_NAME}-control-plane"

if ! docker ps -a --format '{{.Names}}' | grep -qx "$node"; then
  die "No cluster container '$node'. Create one with ./scripts/cluster-up.sh"
fi

if docker ps --format '{{.Names}}' | grep -qx "$node"; then
  ok "Cluster '$CLUSTER_NAME' is already running."
else
  log "Starting cluster '$CLUSTER_NAME'"
  docker start "$node" >/dev/null
fi

kubectl config use-context "kind-$CLUSTER_NAME" >/dev/null 2>&1 || true

log "Waiting for the API server"
for _ in $(seq 1 60); do
  kubectl get nodes >/dev/null 2>&1 && break
  sleep 2
done
kubectl get nodes >/dev/null 2>&1 || die "API server did not come back. Try ./scripts/cluster-down.sh then cluster-up.sh"

log "Waiting for system pods to settle (a brief Error here is normal)"
for _ in $(seq 1 60); do
  bad=$(kubectl get pods -A --no-headers 2>/dev/null | awk '$4!="Running" && $4!="Completed"' | wc -l)
  [ "$bad" -eq 0 ] && break
  sleep 2
done

bad=$(kubectl get pods -A --no-headers 2>/dev/null | awk '$4!="Running" && $4!="Completed"' | wc -l)
echo
if [ "$bad" -eq 0 ]; then
  kubectl get nodes
  echo
  ok "Cluster ready. Host ports: dashboard 18080 | kafka 19092 | mongo 37017"
else
  warn "$bad pod(s) still not Running after 2 minutes:"
  kubectl get pods -A --no-headers | awk '$4!="Running" && $4!="Completed"'
  warn "If this persists, recreate: ./scripts/cluster-down.sh && ./scripts/cluster-up.sh"
  exit 1
fi
