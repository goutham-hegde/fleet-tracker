#!/usr/bin/env bash
# Create the local Kind cluster. Idempotent.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

require kind   "Install with: winget install Kubernetes.kind"
require kubectl "Ships with Docker Desktop."

if kind get clusters 2>/dev/null | grep -qx "$CLUSTER_NAME"; then
  ok "Cluster '$CLUSTER_NAME' already exists."
else
  log "Creating cluster '$CLUSTER_NAME'"
  kind create cluster --config "$KIND_CONFIG" --wait 240s
fi

kubectl config use-context "kind-$CLUSTER_NAME" >/dev/null
log "Nodes"
kubectl get nodes
echo
ok "Cluster ready. Host ports: dashboard 18080 | kafka 19092 | mongo 37017"
