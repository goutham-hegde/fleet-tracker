#!/usr/bin/env bash
# The whole platform, in the cluster, from one command.
#
# M5's scripts/demo.sh brought the same platform up as seven jars on the host. This brings it up as
# manifests: eight images, thirteen pods, and nothing running on the laptop except Docker. The
# difference matters for one reason above all others -- what this script produces is a description
# of the platform that a machine can apply, so the same description can be applied by a CI job in
# M7 and by a cloud cluster in M8. A shell script that starts jars can only ever be run by the
# person holding the laptop.
#
#   ./scripts/stack-up.sh          everything: cluster, platform, KEDA, images, seed, deploy
#   ./scripts/stack-up.sh --deploy just rebuild the images and roll the services out again
#
# The single command M6 is actually graded on is the last step:
#
#   kubectl apply -k deploy/overlays/local
#
# Everything before it exists because that command needs a cluster with the right port mappings, a
# broker with topics, an operator that can read Kafka lag, images on the node, and reference data in
# MongoDB. None of those are the deployment; all of them have to be true before it means anything.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

require kubectl "Ships with Docker Desktop."
require kind "winget install Kubernetes.kind"

DEPLOY_ONLY=false
[ "${1:-}" = "--deploy" ] && DEPLOY_ONLY=true

MONGO_URI="mongodb://localhost:37017"

# Derived state, and why it is cleared on every full run.
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

reset_derived_state() {
  # The tracking processor must not be running for this. position.history is a MongoDB time-series
  # collection, and an insert into a missing collection silently creates an ordinary one -- no
  # buckets, no compression, no error. Dropping it under a live consumer is precisely the failure
  # S10 hit for real. Scaling the deployment to zero first makes the ordering explicit rather than
  # hopeful; the deploy step below brings it back.
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
  mongosh "$MONGO_URI" --quiet --eval "
    const d = db.getSiblingDB('fleet');
    ${DERIVED_COLLECTIONS}.forEach(c => d.getCollection(c).drop());
  " >/dev/null
  ok "position history, geofence state, ETAs and incidents cleared"

  kubectl annotate scaledobject/tracking-processor -n fleet \
    autoscaling.keda.sh/paused-replicas- >/dev/null 2>&1 || true
}

if [ "$DEPLOY_ONLY" = false ]; then
  if ! kind get clusters 2>/dev/null | grep -qx "$CLUSTER_NAME"; then
    log "Creating the cluster"
    "$REPO_ROOT/scripts/cluster-up.sh"
  else
    # A stopped cluster keeps its data, its deployments and its randomly assigned API server port,
    # and resumes in about six seconds. Recreating it takes ninety and loses all three.
    log "Cluster exists; making sure it is running"
    "$REPO_ROOT/scripts/cluster-start.sh" >/dev/null 2>&1 || true
  fi

  "$REPO_ROOT/scripts/platform-up.sh" >/dev/null
  ok "Kafka, MongoDB and the topics are up"

  # KEDA before the overlay, not after. The overlay contains a ScaledObject, which is a kind the
  # API server does not recognise until KEDA's CRDs exist -- so applying in the other order fails
  # with "no matches for kind", which reads like a broken manifest and is a missing operator.
  if ! kubectl get crd scaledobjects.keda.sh >/dev/null 2>&1; then
    "$REPO_ROOT/scripts/keda-up.sh" >/dev/null
  fi
  ok "KEDA is installed"

  log "Seeding reference data"
  "$REPO_ROOT/scripts/seed-identity.sh"        >/dev/null
  "$REPO_ROOT/scripts/seed-itinerary.sh"       >/dev/null
  "$REPO_ROOT/scripts/seed-manifest-schemas.sh" >/dev/null
  "$REPO_ROOT/scripts/seed-manifests.sh"       >/dev/null
  ok "assignments, itineraries, schemas and manifests seeded"
fi

"$REPO_ROOT/scripts/images.sh" >/dev/null
ok "images built and loaded into the node"

reset_derived_state

log "kubectl apply -k deploy/overlays/local"
kubectl apply -k "$REPO_ROOT/deploy/overlays/local"

# A rollout that was already running the same image is not restarted by an apply, because nothing
# in the manifest changed -- the tag is the same even though the bytes behind it are not. This is
# what makes a rebuilt image appear to have no effect.
WORKLOADS=(ingest-gateway tracking-processor shipment-service exception-service dashboard-api dashboard fleet-simulator archiver)

# The archiver is the one workload that needs something from outside this machine: the bucket and
# role that scripts/aws-link.sh writes into the archive-destination ConfigMap from Terraform's
# outputs. Without AWS it waits, naming the missing ConfigMap, and the rest of the platform is
# unaffected -- so it is left out of the wait rather than failing the whole bring-up.
if ! kubectl get configmap/archive-destination -n fleet >/dev/null 2>&1; then
  if aws sts get-caller-identity >/dev/null 2>&1; then
    log "Linking the cluster to AWS for the archiver"
    "$REPO_ROOT/scripts/aws-link.sh" || warn "aws-link.sh failed; the archiver will wait until it succeeds"
  fi
fi
if ! kubectl get configmap/archive-destination -n fleet >/dev/null 2>&1; then
  warn "No archive-destination ConfigMap, so the archiver will wait. Run: aws login --region ap-south-1 && ./scripts/aws-link.sh"
  WORKLOADS=("${WORKLOADS[@]/archiver}")
fi

log "Rolling out the freshly built images"
for d in "${WORKLOADS[@]}"; do
  [ -n "$d" ] && kubectl rollout restart "deployment/$d" -n fleet >/dev/null
done

log "Waiting for every workload to pass its probes"
for d in "${WORKLOADS[@]}"; do
  [ -n "$d" ] && kubectl rollout status "deployment/$d" -n fleet --timeout=300s
done

echo
kubectl get pods -n fleet
echo
kubectl get scaledobject,hpa -n fleet

echo
ok "The platform is running in the cluster."
ok "  dashboard        http://localhost:18080"
ok "  dashboard API    http://localhost:18083/api/shipments"
ok "  live stream      curl -N localhost:18083/api/stream"
ok "  ingest gateway   http://localhost:18081/actuator/health"
ok "  shipment service http://localhost:18082/manifests/SHP-HYD-0002"
echo
ok "  Watch it scale:  kubectl get hpa -n fleet -w"
ok "  Tear it down:    kubectl delete -k deploy/overlays/local   (leaves Kafka and MongoDB data)"
