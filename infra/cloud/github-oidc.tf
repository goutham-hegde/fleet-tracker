# GitHub Actions acting in this AWS account with no stored credential anywhere.
#
# How it works, in the order it happens:
#
#   1. A workflow job that asks for `id-token: write` can request a short-lived token from GitHub,
#      signed by GitHub, stating facts about the run: which repository, which branch, which event.
#   2. The job hands that token to AWS STS and asks to become the role below.
#   3. AWS checks the signature against GitHub's published keys (that is what the OIDC provider
#      resource registers), then checks the stated facts against the role's trust policy.
#   4. If both pass, STS returns credentials that expire within the hour.
#
# Nothing is stored in the repository, in repository secrets, or on a runner. There is no access key
# to leak, rotate, or forget to revoke when a laptop is lost. That is the same argument S18 made
# about registry passwords, one level up.

# Registers GitHub as an identity provider this account trusts to sign tokens. One per account.
#
# No thumbprint_list: AWS validates GitHub's certificate against its own library of trusted
# certificate authorities for this provider, and a pinned thumbprint would only become the thing
# that breaks when GitHub rotates its certificate.
resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
}

# The trust policy is the security boundary, and every line of it matters.
data "aws_iam_policy_document" "github_actions_trust" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }

    # The token was minted for AWS STS, not for some other service that also accepts GitHub tokens.
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    # Which runs may become this role: a push to main of this repository, and nothing else.
    #
    # Without this condition, *any* GitHub Actions workflow in *any* repository on github.com holds
    # a token this account would accept -- every one of them is signed by the same GitHub. That is
    # the classic OIDC misconfiguration, and it has been exploited in the wild.
    #
    # StringEquals, not StringLike with a wildcard. "repo:owner/name:*" would admit pull requests,
    # and a pull request runs whatever code the pull request contains -- so anybody able to open
    # one could run code as this role. Only main is reviewed and protected, so only main may act.
    #
    # Note the shape changes if a job declares a GitHub `environment:` -- its subject becomes
    # "repo:owner/name:environment:<name>" and this condition would refuse it.
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repository}:ref:refs/heads/main"]
    }
  }
}

resource "aws_iam_role" "github_actions" {
  name               = "fleet-tracker-github-actions"
  description        = "Assumed by GitHub Actions on pushes to main of ${var.github_repository}, via OIDC."
  assume_role_policy = data.aws_iam_policy_document.github_actions_trust.json

  # An hour is the default and the ceiling a job will ever need; the CI jobs finish in minutes.
  max_session_duration = 3600
}

# Deliberately no permissions attached yet. Being able to *become* the role and being able to *do*
# anything as it are separate grants, and as of S20 CI needs only the first -- which is enough to
# prove the trust works, since `sts get-caller-identity` needs no permission at all. S21 and S22
# attach exactly what their jobs need, and each grant then has a reason next to it.
