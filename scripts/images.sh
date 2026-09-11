#!/usr/bin/env bash
# Build the eight container images and put them where the Kind node can see them.
#
# Kind runs Kubernetes inside a Docker container, and that container has its own image store: an
# image sitting in the host's Docker daemon is invisible to it. `kind load docker-image` copies one
# across. Without that step every pod sits in ImagePullBackOff trying to pull from ghcr.io, where
# these images do not exist yet -- that is M7's job.
#
# Idempotent, and the expensive parts are cached. Jib rebuilds only the layers that changed, which
# for a code-only edit is a few hundred kilobytes rather than a 90 MB fat jar; the dashboard's
# `npm ci` layer is reused until the lockfile moves.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

require docker "Docker Desktop must be running."
require kind "winget install Kubernetes.kind"

# The five JVM services, built by Jib from the classes Maven already produced. No Dockerfile: the
# classpath, the main class and the base image all come from the POM.
log "Building service images (Jib)"
"$REPO_ROOT/mvnw" -q -Pimages -DskipTests package -f "$REPO_ROOT/pom.xml"
ok "six service images and the simulator built"

# The dashboard is the exception: static files and an nginx, so there is no main class to point Jib
# at and it has a Dockerfile of its own.
#
# The build context is `.` from inside the directory rather than a path, and that is not a style
# choice. Docker Desktop on Windows wants a Windows path; Git Bash hands it a Unix one such as
# /g/project/fleet-tracking/dashboard and the daemon answers "path not found", which reads like a
# missing directory rather than a translation problem. A relative path avoids the translation.
log "Building dashboard image (Dockerfile)"
( cd "$REPO_ROOT/dashboard" && docker build -q \
    -t ghcr.io/goutham-hegde/fleet-tracker/dashboard:0.1.0-SNAPSHOT . >/dev/null )
ok "dashboard image built"

IMAGES=(
  ingest-gateway
  tracking-processor
  shipment-service
  exception-service
  dashboard-api
  archiver
  dashboard
  fleet-simulator
)

if ! kind get clusters 2>/dev/null | grep -qx "$CLUSTER_NAME"; then
  warn "Cluster '$CLUSTER_NAME' does not exist, so nothing was loaded into it."
  warn "Run ./scripts/cluster-up.sh, then this script again."
  exit 0
fi

log "Loading into the Kind node"
for image in "${IMAGES[@]}"; do
  ref="ghcr.io/goutham-hegde/fleet-tracker/$image:0.1.0-SNAPSHOT"
  kind load docker-image "$ref" --name "$CLUSTER_NAME" >/dev/null
  ok "$image"
done

echo
ok "Eight images built and loaded."
ok "  A running pod does NOT pick up a new image by itself: the deployment's image reference is"
ok "  unchanged, so nothing tells Kubernetes anything happened. Restart it explicitly:"
ok "    kubectl rollout restart deployment/<name> -n fleet"
