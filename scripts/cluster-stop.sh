#!/usr/bin/env bash
# Stop the local Kind cluster without destroying it.
#
# Prefer this over cluster-down.sh at the end of a work session: it frees all
# the RAM and CPU the cluster was using, but keeps the cluster, its data, and
# anything deployed into it. Restarting takes about a second.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

require docker "Start Docker Desktop."

node="${CLUSTER_NAME}-control-plane"

if ! docker ps -a --format '{{.Names}}' | grep -qx "$node"; then
  ok "No cluster container '$node' — nothing to stop."
  exit 0
fi

if ! docker ps --format '{{.Names}}' | grep -qx "$node"; then
  ok "Cluster '$CLUSTER_NAME' is already stopped."
  exit 0
fi

log "Stopping cluster '$CLUSTER_NAME'"
docker stop "$node" >/dev/null
ok "Stopped. RAM and CPU released; data and deployments preserved."
echo
echo "  Resume with : ./scripts/cluster-start.sh"
echo "  Destroy with: ./scripts/cluster-down.sh"
echo
echo "  Note: Docker Desktop's own VM still holds memory. Quit Docker Desktop"
echo "        entirely to give it all back to Windows."
