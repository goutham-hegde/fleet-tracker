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
