# The Kind cluster on the laptop, trusted by AWS the same way GitHub is -- with no stored credential.
#
# S20 let GitHub Actions into this account by trusting tokens GitHub signs. This does the same for
# the cluster, because Kubernetes already signs tokens too: every pod can be handed a short-lived
# JSON Web Token, signed with the cluster's private service-account key, stating "I am service
# account X in namespace Y". AWS can verify that signature if it can fetch the matching public key,
# and then exchange the token for temporary credentials exactly as it does for GitHub's.
#
# What AWS needs is two small public documents at an HTTPS address -- the "issuer":
#
#   <issuer>/.well-known/openid-configuration   what this issuer is and where its keys are
#   <issuer>/openid/v1/jwks                      the public half of the cluster's signing key
#
# A laptop behind a home router cannot serve those, so they live in an S3 bucket. The documents
# are public on purpose: they hold a *public* key, and publishing it is the whole mechanism. The
# private key never leaves the Kind node.
#
# The cluster must stamp that same address into every token it issues, which is the
# service-account-issuer flag in deploy/kind-cluster.yaml. That is why the bucket name is fixed and
# written out rather than derived from the account id: the cluster config is committed, the
# account id is not, and the two must agree.

locals {
  cluster_issuer_bucket = "fleet-tracker-oidc"
  cluster_issuer_host   = "${local.cluster_issuer_bucket}.s3.${var.region}.amazonaws.com"
  cluster_issuer        = "https://${local.cluster_issuer_host}"
}

resource "aws_s3_bucket" "cluster_issuer" {
  bucket = local.cluster_issuer_bucket

  # Two documents, both regenerable; destroy should never be stopped by them.
  force_destroy = true
}

# Public *policy* allowed, public *ACLs* still blocked. Access is granted only by the bucket policy
# below, which names two keys; nothing else in this bucket, and no object uploaded with a public ACL
# by mistake, can become readable.
resource "aws_s3_bucket_public_access_block" "cluster_issuer" {
  bucket                  = aws_s3_bucket.cluster_issuer.id
  block_public_acls       = true
  ignore_public_acls      = true
  block_public_policy     = false
  restrict_public_buckets = false
}

data "aws_iam_policy_document" "cluster_issuer" {
  # Anyone may read the two discovery documents. Nothing else.
  statement {
    sid     = "PublicDiscoveryDocuments"
    effect  = "Allow"
    actions = ["s3:GetObject"]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    resources = [
      "${aws_s3_bucket.cluster_issuer.arn}/.well-known/openid-configuration",
      "${aws_s3_bucket.cluster_issuer.arn}/openid/v1/jwks",
    ]
  }

  statement {
    sid     = "TlsOnly"
    effect  = "Deny"
    actions = ["s3:*"]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    resources = [aws_s3_bucket.cluster_issuer.arn, "${aws_s3_bucket.cluster_issuer.arn}/*"]
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "cluster_issuer" {
  bucket = aws_s3_bucket.cluster_issuer.id
  policy = data.aws_iam_policy_document.cluster_issuer.json

  # A public policy is refused while the account's default public-access block still applies.
  depends_on = [aws_s3_bucket_public_access_block.cluster_issuer]
}

# The discovery document is fixed, so Terraform owns it. The key set is NOT here: it is the public
# half of a key the cluster generates when it is created, so it changes every time Kind is
# recreated, which Terraform does not see. scripts/aws-link.sh reads it from the running cluster and
# uploads it.
resource "aws_s3_object" "cluster_discovery" {
  bucket       = aws_s3_bucket.cluster_issuer.id
  key          = ".well-known/openid-configuration"
  content_type = "application/json"
  content = jsonencode({
    issuer                                = local.cluster_issuer
    jwks_uri                              = "${local.cluster_issuer}/openid/v1/jwks"
    response_types_supported              = ["id_token"]
    subject_types_supported               = ["public"]
    id_token_signing_alg_values_supported = ["RS256"]
  })
}

resource "aws_iam_openid_connect_provider" "cluster" {
  url            = local.cluster_issuer
  client_id_list = ["sts.amazonaws.com"]

  # IAM reads the discovery document when the provider is created.
  depends_on = [aws_s3_object.cluster_discovery, aws_s3_bucket_policy.cluster_issuer]
}

# ------------------------------------------------------------------------------------------------
# One role per service account, and each trusts exactly one.
# ------------------------------------------------------------------------------------------------
#
# The subject of a Kubernetes token is "system:serviceaccount:<namespace>:<name>". Matching it
# exactly means a pod running under any other service account -- the dashboard, Kafka, a pod
# somebody starts by hand in the default namespace -- presents a perfectly valid, correctly signed
# token and is refused. StringEquals, never StringLike, for the same reason as the CI role.

locals {
  cluster_workloads = {
    archiver       = "system:serviceaccount:fleet:archiver"
    archive_replay = "system:serviceaccount:fleet:archive-replay"
  }
}

data "aws_iam_policy_document" "cluster_trust" {
  for_each = local.cluster_workloads

  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.cluster.arn]
    }
    # The token was requested for AWS STS: the pod's projected volume asks for this audience.
    condition {
      test     = "StringEquals"
      variable = "${local.cluster_issuer_host}:aud"
      values   = ["sts.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "${local.cluster_issuer_host}:sub"
      values   = [each.value]
    }
  }
}

resource "aws_iam_role" "archiver" {
  name                 = "fleet-tracker-archiver"
  description          = "Assumed only by ${local.cluster_workloads.archiver} in the Kind cluster. Writes the event archive."
  assume_role_policy   = data.aws_iam_policy_document.cluster_trust["archiver"].json
  max_session_duration = 3600
}

resource "aws_iam_role" "archive_replay" {
  name                 = "fleet-tracker-archive-replay"
  description          = "Assumed only by ${local.cluster_workloads.archive_replay} in the Kind cluster. Reads the event archive."
  assume_role_policy   = data.aws_iam_policy_document.cluster_trust["archive_replay"].json
  max_session_duration = 3600
}
