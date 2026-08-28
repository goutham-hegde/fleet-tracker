#!/usr/bin/env bash
# Remove the platform layer. Deleting the namespace takes the StatefulSets,
# services and Job with it.
#
# PersistentVolumeClaims live in the namespace too, so this DESTROYS the Kafka
# log and the Mongo data files. To free memory while keeping the data, stop the
# whole cluster instead: ./scripts/cluster-stop.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

require kubectl "Ships with Docker Desktop."

if ! kubectl get namespace fleet >/dev/null 2>&1; then
  ok "Namespace 'fleet' does not exist; nothing to remove."
  exit 0
fi

warn "This deletes the 'fleet' namespace including all Kafka and MongoDB data."
log "Deleting namespace 'fleet'"
kubectl delete namespace fleet --wait=true

ok "Platform removed."
