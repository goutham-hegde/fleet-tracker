#!/usr/bin/env bash
# Everything this project owns in AWS, found by tag rather than by list.
#
# Both Terraform stacks stamp Project=fleet-tracker on every resource that takes tags (see
# infra/*/versions.tf), so "what do we own" is a query, not a list somebody maintains. That matters
# for M8's fourth exit criterion -- "terraform destroy removes everything cleanly" -- because a
# destroy that succeeds proves only that Terraform is happy with its own state. It says nothing
# about a resource created outside that state, or one Terraform forgot. A tag sweep asks AWS.
#
#   ./scripts/aws-sweep.sh                 # list what exists (run before the teardown)
#   ./scripts/aws-sweep.sh --expect-empty  # fail if anything is left (run after it)
#
# Two regions are swept: ap-south-1, where every resource lives, and us-east-1, because the Resource
# Groups Tagging API reports global services there -- IAM roles and policies, and CloudFront if it is
# ever enabled. Budgets are asked for separately: they are not a tagged resource, and the budget
# alarm is both the first thing created and the last thing destroyed.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

require aws "winget install Amazon.AWSCLI"

EXPECT_EMPTY=false
[ "${1:-}" = "--expect-empty" ] && EXPECT_EMPTY=true

identity_arn="$(aws sts get-caller-identity --query Arn --output text 2>/dev/null)" \
  || die "The AWS CLI has no working credentials. Run: aws login --region ap-south-1"
case "$identity_arn" in *:root) die "Signed in as root. Use the IAM admin user." ;; esac
account="$(aws sts get-caller-identity --query Account --output text)"

found=0

for region in ap-south-1 us-east-1; do
  log "Resources tagged Project=fleet-tracker in $region"
  # --query on the paginated form, so a long list still arrives whole.
  arns="$(aws resourcegroupstaggingapi get-resources \
    --region "$region" \
    --tag-filters Key=Project,Values=fleet-tracker \
    --query 'ResourceTagMappingList[].ResourceARN' --output text 2>/dev/null | tr '\t' '\n' | grep -v '^$' || true)"
  if [ -z "$arns" ]; then
    ok "none"
  else
    while IFS= read -r arn; do
      printf '     %s\n' "$arn"
      found=$((found + 1))
    done <<< "$arns"
  fi
done

# Budgets carry no tags, so the sweep above cannot see them. There is exactly one, named in
# infra/bootstrap; anything else in the account is not this project's and is left alone.
log "Budgets in account $account"
budgets="$(aws budgets describe-budgets --account-id "$account" \
  --query 'Budgets[?contains(BudgetName, `fleet`)].BudgetName' --output text 2>/dev/null | tr '\t' '\n' | grep -v '^$' || true)"
if [ -z "$budgets" ]; then
  ok "none"
else
  while IFS= read -r b; do
    printf '     %s\n' "$b"
    found=$((found + 1))
  done <<< "$budgets"
fi

printf '\n'
if [ "$found" -eq 0 ]; then
  ok "Nothing of this project's exists in AWS."
elif [ "$EXPECT_EMPTY" = true ]; then
  die "$found resource(s) still exist. The teardown is not complete."
else
  log "$found resource(s). Tear them down with ./scripts/infra-down.sh"
fi
