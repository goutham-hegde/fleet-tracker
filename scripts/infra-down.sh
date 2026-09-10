#!/usr/bin/env bash
# Remove everything this project has in AWS -- M8's "terraform destroy removes everything" -- in
# the reverse of the order infra-up.sh created it.
#
#   1. infra/cloud first. Its state lives in the bootstrap bucket, so destroying the bucket first
#      would leave every cloud resource running with nothing left that knows it exists.
#   2. infra/bootstrap last: the state bucket (emptied, versions and all) and then the budget. The
#      alarm is the last thing to go, for the same reason it was the first thing to exist.
#
# The AWS account itself, the IAM admin user and root's MFA are not Terraform's and stay.
#
# Usage: ./scripts/infra-down.sh [--auto-approve]
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

require terraform "winget install Hashicorp.Terraform"
require aws "winget install Amazon.AWSCLI"

APPROVE=()
[ "${1:-}" = "--auto-approve" ] && APPROVE=(-auto-approve)

INFRA="$REPO_ROOT/infra"
tf() { terraform -chdir="$INFRA/$1" "${@:2}"; }

identity_arn="$(aws sts get-caller-identity --query Arn --output text 2>/dev/null)" \
  || die "The AWS CLI has no working credentials. Run: aws login --region ap-south-1"
case "$identity_arn" in *:root) die "Signed in as root. Use the IAM admin user." ;; esac
eval "$(aws configure export-credentials --format env)"

tf bootstrap init -input=false >/dev/null
# Empty rather than failed when there is no state -- see infra-up.sh.
bucket="$(tf bootstrap output -raw state_bucket 2>/dev/null || true)"
if [ -n "$bucket" ]; then
  log "Destroying the cloud stack (state in s3://$bucket)"
  tf cloud init -input=false -reconfigure -backend-config="bucket=$bucket" >/dev/null
  tf cloud destroy -input=false "${APPROVE[@]}"
else
  warn "No bootstrap state, so no state bucket: skipping the cloud stack."
fi

log "Destroying the bootstrap stack: state bucket, then budget"
tf bootstrap destroy -input=false "${APPROVE[@]}"

if command -v gh >/dev/null 2>&1; then
  gh variable delete AWS_ROLE_ARN >/dev/null 2>&1 && ok "AWS_ROLE_ARN repository variable removed" || true
fi
ok "Nothing of this project's remains in AWS. Check the billing console at the end of the month."
