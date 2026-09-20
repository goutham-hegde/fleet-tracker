variable "region" {
  type    = string
  default = "ap-south-1"
}

# The start of the OIDC subject GitHub issues for this repository's workflow runs, copied from
# GitHub rather than composed by hand:
#
#   gh api repos/goutham-hegde/fleet-tracker/actions/oidc/customization/sub --jq .sub_claim_prefix
#
# It carries the owner's and the repository's numeric ids as well as their names. That is GitHub's
# default for this repository, and it is the better form: the ids are never reused, so a repository
# deleted and recreated under the same name -- by anybody -- issues a different subject and is
# refused. The name-only form ("repo:owner/name") that most examples show would silently never
# match here, and a trust policy that matches nothing looks exactly like one that refuses correctly.
variable "github_subject_prefix" {
  description = "OIDC subject prefix of the only repository whose workflows may assume the CI role."
  type        = string
  default     = "repo:goutham-hegde@181922465/fleet-tracker@1345975529"
}

# Which front door the public view has (ADR 0003, and its addendum).
#
# CloudFront is the design: a cache in front of two origins, so a page view costs an edge request
# rather than an invocation. Creating a distribution needs a verified account, and this account's
# verification has been with AWS since 2026-09-13 with no date on it.
#
# false serves the page from the lookup function's own URL instead. Everything the ADR argues for
# survives -- an archive file is still read once when it lands, never per view -- but the edge cache
# is given up and each page view becomes an invocation. Flip this to true when AWS confirms; the
# plan then adds the distribution and closes the function URL to everything but CloudFront.
variable "cloudfront_enabled" {
  description = "Put CloudFront in front of the public view. Needs an account AWS has verified."
  type        = bool
  default     = false
}
