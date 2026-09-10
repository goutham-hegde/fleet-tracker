#!/usr/bin/env bash
# Create or update everything this project has in AWS, in the only order that works.
#
#   1. infra/bootstrap -- the zero-spend budget, then the Terraform state bucket. Local state.
#   2. infra/cloud     -- everything else, with its state in that bucket.
#   3. The CI role's ARN is stored as the AWS_ROLE_ARN repository variable, where the workflow
#      reads it. A variable rather than a secret: an ARN is an address, not a credential.
#
# Usage:
#   ./scripts/infra-up.sh              # plan and apply, asking before each apply
#   ./scripts/infra-up.sh --plan       # plan only; changes nothing anywhere
#   ./scripts/infra-up.sh --auto-approve
#
# Needs a signed-in AWS CLI (`aws login --region ap-south-1`) as an IAM user -- never root.
# Idempotent: on an unchanged account both applies report no changes.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

require terraform "winget install Hashicorp.Terraform"
require aws "winget install Amazon.AWSCLI"

MODE="apply"
APPROVE=()
case "${1:-}" in
  --plan) MODE="plan" ;;
  --auto-approve) APPROVE=(-auto-approve) ;;
  "") ;;
  *) die "unknown argument '$1' (expected --plan or --auto-approve)" ;;
esac

INFRA="$REPO_ROOT/infra"

log "Checking who this is"
identity_arn="$(aws sts get-caller-identity --query Arn --output text 2>/dev/null)" \
  || die "The AWS CLI has no working credentials. Run: aws login --region ap-south-1"
# Root can do anything, including close the account, and cannot be restricted by any policy. It
# signs in to add MFA and to fix the account; it does not run Terraform.
case "$identity_arn" in
  *:root) die "Signed in as the root user. Sign in as the IAM admin user instead (aws login)." ;;
esac
ok "$identity_arn"

# Hand Terraform the CLI's current credentials as environment variables. `aws login` stores a
# console session the CLI knows how to refresh; exporting resolves it to plain temporary keys,
# which every version of the Terraform provider can read. They last long enough for an apply --
# minutes -- and are never written to disk.
eval "$(aws configure export-credentials --format env)"

[ -f "$INFRA/bootstrap/terraform.tfvars" ] \
  || die "infra/bootstrap/terraform.tfvars is missing. Copy terraform.tfvars.example and set budget_email."

tf() { terraform -chdir="$INFRA/$1" "${@:2}"; }

# ------------------------------------------------------------------------------------------------
log "Bootstrap: budget, then state bucket"
tf bootstrap init -input=false >/dev/null
if [ "$MODE" = plan ]; then
  tf bootstrap plan -input=false
else
  tf bootstrap apply -input=false "${APPROVE[@]}"
fi

# Without an applied bootstrap there is no bucket to keep the cloud stack's state in, so a plan-only
# run on a fresh account stops here rather than failing on a backend that does not exist yet.
#
# Tested for emptiness, not by exit code: with no state at all, `terraform output -raw` prints a
# warning, writes nothing to stdout and exits 0 -- so an unapplied bootstrap looks like success with
# a bucket named "", and the backend then fails with a message about empty values.
bucket="$(tf bootstrap output -raw state_bucket 2>/dev/null || true)"
if [ -z "$bucket" ]; then
  warn "Bootstrap is not applied yet, so the cloud stack cannot be planned. Apply first."
  exit 0
fi
ok "state bucket: $bucket"

# ------------------------------------------------------------------------------------------------
log "Cloud stack (state in s3://$bucket)"
# -reconfigure because the bucket name is only known now; it is not stored in any committed file.
tf cloud init -input=false -reconfigure -backend-config="bucket=$bucket" >/dev/null
if [ "$MODE" = plan ]; then
  tf cloud plan -input=false
  exit 0
fi
tf cloud apply -input=false "${APPROVE[@]}"

role_arn="$(tf cloud output -raw github_actions_role_arn)"
ok "CI role: $role_arn"

# ------------------------------------------------------------------------------------------------
if command -v gh >/dev/null 2>&1; then
  log "Storing the role ARN as the AWS_ROLE_ARN repository variable"
  gh variable set AWS_ROLE_ARN --body "$role_arn" >/dev/null
  ok "done"
else
  warn "gh not found. Set it by hand: Settings > Secrets and variables > Actions > Variables > AWS_ROLE_ARN"
fi
