#!/usr/bin/env bash
# Publish the public view: the two Lambda functions' code, and the dashboard built in archive mode.
#
# This is what CI's publish-public job runs on every merge to main, and what a person runs to fill a
# freshly created public view without waiting for a merge. One definition of "publish", in one
# place, the same reasoning as set-image-tag.sh.
#
#   1. Functions. The zip Maven builds replaces both functions' code, and the script waits until
#      each reports the update live. The indexer and the lookup share one zip: one module, two
#      entry points.
#   2. Assets first. Their file names carry a content hash, so they can be uploaded before anything
#      refers to them and cached for a year.
#   3. index.html next, which is what switches viewers to the new assets. Cached for a minute.
#   4. Only then are the previous build's assets deleted. The other order leaves a window in which
#      a cached index.html names a file that is gone.
#   5. The edge is told index.html changed. One invalidation per publish; 1,000 paths a month free.
#
# Usage: ./scripts/public-publish.sh [--site-only]
#   Where to publish comes from PUBLIC_SITE_BUCKET and PUBLIC_DISTRIBUTION_ID (CI sets both from
#   repository variables) or, failing that, from the cloud stack's Terraform outputs.
# Needs: a signed-in AWS CLI with the permissions infra/cloud grants the CI role, Java, Node.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

require aws "winget install Amazon.AWSCLI"
require npm "install Node.js"

SITE_ONLY=false
[ "${1:-}" = "--site-only" ] && SITE_ONLY=true

# The function names are fixed in infra/cloud/public-view.tf and carry no account id, so they are
# stated here rather than looked up.
FUNCTIONS=(fleet-tracker-public-index fleet-tracker-public-lookup)
ZIP="$REPO_ROOT/functions/public-view/target/public-view.zip"

# OpenFreeMap: vector tiles with no key and no signup. OpenStreetMap's own raster tiles, which the
# live map uses, are volunteer-run servers whose usage policy excludes exactly this: a public site.
BASEMAP="https://tiles.openfreemap.org/styles/liberty"

if [ -z "${PUBLIC_SITE_BUCKET:-}" ] || [ -z "${PUBLIC_DISTRIBUTION_ID:-}" ]; then
  require terraform "winget install Hashicorp.Terraform"
  state_bucket="$(terraform -chdir="$REPO_ROOT/infra/bootstrap" output -raw state_bucket 2>/dev/null || true)"
  [ -n "$state_bucket" ] || die "No PUBLIC_SITE_BUCKET set and no bootstrap state to read it from. Run ./scripts/infra-up.sh first."
  terraform -chdir="$REPO_ROOT/infra/cloud" init -input=false -reconfigure \
    -backend-config="bucket=$state_bucket" >/dev/null
  PUBLIC_SITE_BUCKET="$(terraform -chdir="$REPO_ROOT/infra/cloud" output -raw public_site_bucket 2>/dev/null || true)"
  PUBLIC_DISTRIBUTION_ID="$(terraform -chdir="$REPO_ROOT/infra/cloud" output -raw public_distribution_id 2>/dev/null || true)"
  # Empty rather than failed when the stack predates S22 -- see infra-up.sh on `output -raw`.
  [ -n "$PUBLIC_SITE_BUCKET" ] && [ -n "$PUBLIC_DISTRIBUTION_ID" ] \
    || die "The cloud stack has no public view yet. Apply infra/cloud first (./scripts/infra-up.sh)."
fi

# ------------------------------------------------------------------------------------------------
if [ "$SITE_ONLY" = false ]; then
  log "Building the functions' package"
  (cd "$REPO_ROOT" && ./mvnw -B -ntp -q -pl functions/public-view -am -DskipTests package) \
    || die "Maven could not build $ZIP"

  for fn in "${FUNCTIONS[@]}"; do
    log "Deploying $fn"
    aws lambda update-function-code --function-name "$fn" \
      --zip-file "fileb://$ZIP" --query 'CodeSha256' --output text >/dev/null
    # An update is asynchronous: the call returns while the new code is still being prepared, and
    # a second update to the same function in that window is refused. Waiting also means "published"
    # at the end of this script is true rather than pending.
    aws lambda wait function-updated --function-name "$fn"
    ok "$fn is running the new code"
  done
fi

# ------------------------------------------------------------------------------------------------
log "Building the dashboard in archive mode"
(
  cd "$REPO_ROOT/dashboard"
  [ -d node_modules ] || npm ci --no-audit --no-fund
  VITE_ARCHIVE_MODE=true VITE_BASEMAP_STYLE="$BASEMAP" npm run build
) || die "The dashboard did not build"
DIST="$REPO_ROOT/dashboard/dist"

# The same greps a person would do on the bundle, as a gate: a build that still talks to localhost or
# opens the live stream would publish a page that is broken for every viewer and fine in every test.
bundle="$(cat "$DIST"/assets/*.js)"
case "$bundle" in *localhost:18083*) die "The archive build still points at localhost:18083" ;; esac
case "$bundle" in *EventSource*) die "The archive build still opens the live stream" ;; esac
case "$bundle" in *tiles.openfreemap.org*) ;; *) die "The archive build is not using OpenFreeMap" ;; esac

target="s3://$PUBLIC_SITE_BUCKET"
log "Uploading to $target"
aws s3 cp "$DIST/assets" "$target/assets" --recursive --only-show-errors \
  --cache-control "public, max-age=31536000, immutable"
aws s3 sync "$DIST" "$target" --only-show-errors --exclude "assets/*" --exclude index.html \
  --cache-control "public, max-age=3600"
aws s3 cp "$DIST/index.html" "$target/index.html" --only-show-errors \
  --cache-control "public, max-age=60" --content-type "text/html; charset=utf-8"
# --size-only so this pass only deletes: every file it would compare was uploaded seconds ago.
aws s3 sync "$DIST" "$target" --delete --size-only --only-show-errors
ok "site uploaded"

invalidation="$(aws cloudfront create-invalidation --distribution-id "$PUBLIC_DISTRIBUTION_ID" \
  --paths "/index.html" "/" --query 'Invalidation.Id' --output text)"
ok "edge refresh requested ($invalidation); live within a minute or two"

domain="$(aws cloudfront get-distribution --id "$PUBLIC_DISTRIBUTION_ID" \
  --query 'Distribution.DomainName' --output text 2>/dev/null || true)"
[ -n "$domain" ] && ok "https://$domain"
