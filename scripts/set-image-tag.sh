#!/usr/bin/env bash
# Point the GitOps overlay at a particular build.
#
#   ./scripts/set-image-tag.sh <tag>
#
# Rewrites every `newTag:` in deploy/overlays/gitops/kustomization.yaml. That file is what ArgoCD
# reads, so this script is the only thing in the repository that decides which images the cluster
# runs. CI calls it after publishing; a person calls it to roll back.
#
# Deliberately sed rather than `kustomize edit set image`. The standalone kustomize CLI is a
# separate binary from the one built into kubectl -- it is not on this laptop and not guaranteed on
# a GitHub runner -- so using it would mean installing a tool in CI to perform a substitution, and
# would also reformat the file and discard every comment in it. The comments in that file are most
# of its value.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

TAG="${1:-}"
[ -n "$TAG" ] || die "Usage: $(basename "$0") <tag>   (normally a full 40-character commit SHA)"

OVERLAY="$REPO_ROOT/deploy/overlays/gitops/kustomization.yaml"
[ -f "$OVERLAY" ] || die "Not found: $OVERLAY"

# A tag has to be a valid Docker reference, and the failure of an invalid one is remote and late:
# the file commits, ArgoCD syncs it, and a pod sits in ErrImagePull naming something that was never
# going to resolve. Checking here costs nothing.
case "$TAG" in
  *[!A-Za-z0-9_.-]* | "" | [.-]* ) die "Not a usable image tag: '$TAG'" ;;
esac
[ "${#TAG}" -le 128 ] || die "Tag is longer than a Docker tag may be (128 characters)."

# The count is checked rather than assumed. A silent zero-substitution -- because the file was
# reformatted, or a service was renamed -- would leave CI reporting a successful deployment of the
# previous build, which is the single most confusing thing this script could do.
EXPECTED=8

BEFORE="$(grep -c '^\( *\)newTag:' "$OVERLAY" || true)"
[ "$BEFORE" -eq "$EXPECTED" ] || die "Expected $EXPECTED newTag lines in the overlay, found $BEFORE. Refusing to edit."

sed -i "s|^\( *\)newTag: .*|\1newTag: $TAG|" "$OVERLAY"

AFTER="$(grep -c "^ *newTag: $TAG$" "$OVERLAY" || true)"
[ "$AFTER" -eq "$EXPECTED" ] || die "Rewrote $AFTER of $EXPECTED tags. The overlay is now inconsistent -- check it."

ok "deploy/overlays/gitops now runs tag $TAG ($EXPECTED images)"
