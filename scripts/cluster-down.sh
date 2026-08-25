#!/usr/bin/env bash
# Delete the local Kind cluster and everything in it.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

require kind "Install with: winget install Kubernetes.kind"

if kind get clusters 2>/dev/null | grep -qx "$CLUSTER_NAME"; then
  log "Deleting cluster '$CLUSTER_NAME'"
  kind delete cluster --name "$CLUSTER_NAME"
  ok "Deleted."
else
  ok "Cluster '$CLUSTER_NAME' does not exist."
fi
