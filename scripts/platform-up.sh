#!/usr/bin/env bash
# Deploy the platform layer -- Kafka and MongoDB -- into the Kind cluster and
# wait until both actually answer requests. Idempotent: safe to re-run.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

require kubectl "Ships with Docker Desktop."

kind get clusters 2>/dev/null | grep -qx "$CLUSTER_NAME" \
  || die "Cluster '$CLUSTER_NAME' does not exist. Run ./scripts/cluster-up.sh"
kubectl config use-context "kind-$CLUSTER_NAME" >/dev/null

# A Job's pod template is immutable once created, so `kubectl apply` on a
# changed Job fails with a field-is-immutable error. Deleting it first makes
# re-applying work and also re-runs the topic creation, which is a no-op
# thanks to --if-not-exists.
kubectl delete job kafka-topics -n fleet --ignore-not-found >/dev/null 2>&1 || true

log "Applying deploy/base"
kubectl apply -k "$REPO_ROOT/deploy/base"

# rollout status waits on the readiness probes, which for both of these run a
# real client command rather than a port check -- so "available" here means the
# broker answers a metadata request and Mongo answers a ping.
log "Waiting for Kafka (this pulls a ~400 MB image on first run)"
kubectl rollout status statefulset/kafka -n fleet --timeout=300s

log "Waiting for MongoDB"
kubectl rollout status statefulset/mongodb -n fleet --timeout=300s

log "Waiting for topic creation"
kubectl wait --for=condition=complete job/kafka-topics -n fleet --timeout=180s

echo
log "Topics"
# MSYS_NO_PATHCONV=1 is required, not decoration. Git Bash rewrites any
# argument that looks like a Unix absolute path into a Windows one before the
# process sees it, so /opt/kafka/bin/kafka-topics.sh arrives inside the Linux
# container as "C:/Program Files/Git/opt/kafka/...". The path is meant for the
# container, not for this machine, so the conversion has to be switched off.
MSYS_NO_PATHCONV=1 kubectl exec -n fleet kafka-0 -- \
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list

echo
log "Pods"
kubectl get pods -n fleet

echo
ok "Platform up."
ok "  in-cluster : kafka.fleet.svc.cluster.local:9092 | mongodb.fleet.svc.cluster.local:27017"
ok "  from host  : localhost:19092                    | mongodb://localhost:37017"
