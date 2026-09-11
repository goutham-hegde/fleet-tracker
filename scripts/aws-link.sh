#!/usr/bin/env bash
# Connect the running Kind cluster to AWS, so that pods in it can prove who they are.
#
#   1. Publish the cluster's public signing key to the issuer bucket, where AWS STS fetches it to
#      check the signature on a pod's token. Kind generates a new key pair every time the cluster is
#      created, so this must run after every cluster-up.sh -- and a token signed by a key AWS has not
#      seen is refused with "InvalidIdentityToken", which names neither the key nor this script.
#   2. Write the archive-destination ConfigMap: which bucket, which region, which roles. These come
#      from Terraform's outputs, so nothing in the repository names the account.
#   3. Restart the archiver, if it exists, so it picks both up.
#
# Usage: ./scripts/aws-link.sh
#
# Needs: the cloud stack applied (./scripts/infra-up.sh), the cluster running, and a signed-in AWS
# CLI (`aws login --region ap-south-1`). Idempotent. Writes to S3, not to IAM.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

require kubectl "Ships with Docker Desktop."
require terraform "winget install Hashicorp.Terraform"
require aws "winget install Amazon.AWSCLI"
require curl "Ships with Git for Windows."

identity_arn="$(aws sts get-caller-identity --query Arn --output text 2>/dev/null)" \
  || die "The AWS CLI has no working credentials. Run: aws login --region ap-south-1"
case "$identity_arn" in *:root) die "Signed in as root. Use the IAM admin user." ;; esac
eval "$(aws configure export-credentials --format env)"

INFRA="$REPO_ROOT/infra"
tf() { terraform -chdir="$INFRA/$1" "${@:2}"; }

# ------------------------------------------------------------------------------------------------
log "Reading Terraform's outputs"
tf bootstrap init -input=false >/dev/null
state_bucket="$(tf bootstrap output -raw state_bucket 2>/dev/null || true)"
[ -n "$state_bucket" ] || die "The bootstrap stack is not applied. Run ./scripts/infra-up.sh first."
tf cloud init -input=false -reconfigure -backend-config="bucket=$state_bucket" >/dev/null

# Empty rather than failed when absent -- see infra-up.sh -- so each is tested.
output() {
  local v
  v="$(tf cloud output -raw "$1" 2>/dev/null || true)"
  [ -n "$v" ] || die "Terraform output '$1' is empty. Apply the cloud stack: ./scripts/infra-up.sh"
  printf '%s' "$v"
}
issuer="$(output cluster_issuer)"
issuer_bucket="$(output cluster_issuer_bucket)"
archive_bucket="$(output archive_bucket)"
archiver_role="$(output archiver_role_arn)"
replay_role="$(output archive_replay_role_arn)"
region="$(aws configure get region 2>/dev/null || echo ap-south-1)"
ok "issuer $issuer"
ok "archive s3://$archive_bucket"

# ------------------------------------------------------------------------------------------------
log "Checking the cluster signs tokens as that issuer"
kubectl cluster-info >/dev/null 2>&1 || die "The cluster is not reachable. Run ./scripts/cluster-start.sh"
# The API server publishes its own discovery document. Its `issuer` is the flag in
# deploy/kind-cluster.yaml, and it must match Terraform's byte for byte: AWS compares the `iss` in
# every token against the provider it was told to trust, and a mismatch is a flat refusal.
cluster_issuer="$(kubectl get --raw /.well-known/openid-configuration \
  | sed -n 's/.*"issuer":"\([^"]*\)".*/\1/p')"
if [ "$cluster_issuer" != "$issuer" ]; then
  die "The cluster's issuer is '$cluster_issuer', but AWS trusts '$issuer'.
     The cluster predates S21's issuer flag, or the flag and Terraform disagree. Recreate the cluster
     (./scripts/cluster-down.sh && ./scripts/stack-up.sh) after checking deploy/kind-cluster.yaml."
fi
ok "the cluster issues tokens as $cluster_issuer"

# ------------------------------------------------------------------------------------------------
log "Publishing the cluster's public key"
jwks="$(mktemp)"
trap 'rm -f "$jwks"' EXIT
kubectl get --raw /openid/v1/jwks > "$jwks"
grep -q '"kid"' "$jwks" || die "The cluster returned no signing keys. Is it healthy?"
# Windows-style temp path for the Windows aws.exe.
aws s3 cp "$(cygpath -w "$jwks" 2>/dev/null || echo "$jwks")" "s3://$issuer_bucket/openid/v1/jwks" \
  --content-type application/json --only-show-errors
# Read it back the way STS will: anonymously, over HTTPS, from the public address.
public="$(curl -fsS "$issuer/openid/v1/jwks")" || die "The key set is not publicly readable at $issuer/openid/v1/jwks"
[ "$public" = "$(cat "$jwks")" ] || die "The public key set does not match the cluster's. S3 may be serving a stale copy; retry in a minute."
kid="$(sed -n 's/.*"kid":"\([^"]*\)".*/\1/p' "$jwks")"
ok "published key $kid, readable anonymously"

# ------------------------------------------------------------------------------------------------
log "Writing the archive-destination ConfigMap"
kubectl get namespace fleet >/dev/null 2>&1 || die "No fleet namespace. Run ./scripts/platform-up.sh"
kubectl create configmap archive-destination -n fleet \
  --from-literal=bucket="$archive_bucket" \
  --from-literal=region="$region" \
  --from-literal=archiver-role-arn="$archiver_role" \
  --from-literal=replay-role-arn="$replay_role" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
ok "fleet/archive-destination"

if kubectl get deployment/archiver -n fleet >/dev/null 2>&1; then
  kubectl rollout restart deployment/archiver -n fleet >/dev/null
  ok "archiver restarted to pick it up"
fi
