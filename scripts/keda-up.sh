#!/usr/bin/env bash
# Install KEDA, the piece that lets Kubernetes autoscale on a number it cannot see by itself.
#
# The built-in HorizontalPodAutoscaler reads CPU and memory from the metrics API. Neither answers
# the question this platform actually has, which is whether the tracking processor is keeping up
# with the position topic -- a consumer at 20% CPU that is four hundred thousand records behind is
# failing, and one at 90% CPU that is three records behind is fine.
#
# KEDA is an operator that polls an external system (here, Kafka's own consumer group offsets),
# converts the answer into a metric, and drives an ordinary HPA that it creates and owns. The
# autoscaling underneath is stock Kubernetes; what KEDA adds is knowing how to ask a broker.
#
# Installed separately from `kubectl apply -k deploy/overlays/local` on purpose. The overlay
# contains a ScaledObject, which is a custom resource -- a kind Kubernetes does not know until
# KEDA's CRDs are installed. Applying it first fails with `no matches for kind "ScaledObject"`,
# which is not a manifest error but a missing operator.
#
# Idempotent: re-applying the release manifest is a no-op on an unchanged install.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

require kubectl "Ships with Docker Desktop."

# Pinned. A cluster component that follows a moving "latest" is a cluster that changes underneath
# you between two runs of the same script.
KEDA_VERSION="v2.20.2"
# The asset name drops the leading v that the tag carries: keda-2.20.2.yaml under tag v2.20.2.
# Getting that wrong produces a 404 from GitHub rather than anything about Kubernetes.
KEDA_MANIFEST="https://github.com/kedacore/keda/releases/download/${KEDA_VERSION}/keda-${KEDA_VERSION#v}.yaml"

kind get clusters 2>/dev/null | grep -qx "$CLUSTER_NAME" \
  || die "Cluster '$CLUSTER_NAME' does not exist. Run ./scripts/cluster-up.sh"
kubectl config use-context "kind-$CLUSTER_NAME" >/dev/null

log "Installing KEDA $KEDA_VERSION"
# The release manifest carries its own namespace (keda), three deployments and the CRDs. Nothing of
# this project's is in it, which is why it lives outside deploy/ : it is a cluster add-on, in the
# same category as a CNI or an ingress controller, not part of the application.
kubectl apply --server-side -f "$KEDA_MANIFEST"

log "Waiting for the operator"
kubectl rollout status deployment/keda-operator -n keda --timeout=300s

log "Waiting for the metrics adapter"
# This is the piece that registers itself as an external metrics API server, which is how a stock
# HPA gets to read a number that came from Kafka. If it is not ready, a ScaledObject exists and
# quietly scales nothing.
kubectl rollout status deployment/keda-metrics-apiserver -n keda --timeout=300s

echo
log "KEDA pods"
kubectl get pods -n keda

echo
ok "KEDA installed."
ok "  Scaling rules live with the workload they scale:"
ok "    deploy/overlays/local/tracking-processor-scaledobject.yaml"
ok "  Once applied, watch it work with:"
ok "    kubectl get scaledobject,hpa -n fleet"
