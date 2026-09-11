# The event archive: what the platform received, outliving the cluster that received it.
#
# Everything here is shaped by the bill. The budget counts gross cost and alerts at one cent, and
# S3 on this account is paid from credits, so "free" means staying under a cent a month -- roughly
# 2,000 uploads, or 0.4 GB kept for a month, or some mix of the two. The archiver writes one file per
# topic per hour (a few hundred uploads a month), and the rules below keep what is stored bounded.

data "aws_caller_identity" "current" {}

resource "aws_s3_bucket" "archive" {
  # Bucket names are global; the account id makes this one unique. Unlike the issuer bucket, nothing
  # committed needs to know this name: the archiver is told it through a ConfigMap written from
  # Terraform's outputs.
  bucket = "fleet-tracker-archive-${data.aws_caller_identity.current.account_id}"

  # M8's exit criterion is that `terraform destroy` removes everything cleanly, and a bucket that
  # still holds objects cannot be deleted without this. The archive is a copy, never the only copy
  # of anything the platform needs to run.
  force_destroy = true
}

resource "aws_s3_bucket_public_access_block" "archive" {
  bucket                  = aws_s3_bucket.archive.id
  block_public_acls       = true
  ignore_public_acls      = true
  block_public_policy     = true
  restrict_public_buckets = true
}

# SSE-S3: encrypted at rest with keys S3 manages, at no charge. A customer-managed KMS key would be
# $1 a month whether used or not (ADR 0001).
resource "aws_s3_bucket_server_side_encryption_configuration" "archive" {
  bucket = aws_s3_bucket.archive.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# Deliberately no versioning. Nothing overwrites an archive file -- every name is unique -- so
# versions would only keep a second copy of what the lifecycle rule has just expired.

data "aws_iam_policy_document" "archive_tls_only" {
  statement {
    sid     = "TlsOnly"
    effect  = "Deny"
    actions = ["s3:*"]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    resources = [aws_s3_bucket.archive.arn, "${aws_s3_bucket.archive.arn}/*"]
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "archive" {
  bucket     = aws_s3_bucket.archive.id
  policy     = data.aws_iam_policy_document.archive_tls_only.json
  depends_on = [aws_s3_bucket_public_access_block.archive]
}

# A rolling window, per topic, sized by volume. Positions are nearly all of the bytes -- a fix every
# ten simulated seconds per truck -- so they are kept for three days: long enough to rebuild a
# recreated cluster, short enough that a busy week cannot outgrow a cent. The other three topics are
# a small fraction of that and are kept for a month. Expiry is free; it is the storage that costs.
locals {
  archive_retention_days = {
    "position.events.v1"  = 3
    "status.events.v1"    = 30
    "shipment.derived.v1" = 30
    "exceptions.v1"       = 30
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "archive" {
  bucket = aws_s3_bucket.archive.id

  dynamic "rule" {
    for_each = local.archive_retention_days
    content {
      id     = "expire-${rule.key}"
      status = "Enabled"
      filter {
        prefix = "archive/${rule.key}/"
      }
      expiration {
        days = rule.value
      }
    }
  }

  # The archiver never uploads in parts, but a part upload abandoned by anything else is stored and
  # billed while being invisible to a listing. Free insurance.
  rule {
    id     = "abort-incomplete-uploads"
    status = "Enabled"
    filter {}
    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }
}

# ------------------------------------------------------------------------------------------------
# What each role may do. Write-only and read-only, and neither may delete.
# ------------------------------------------------------------------------------------------------

# The archiver can put objects under archive/ and do nothing else -- not read them back, not list
# them, not delete them. A compromised archiver pod could add junk to the archive and nothing more.
data "aws_iam_policy_document" "archiver" {
  statement {
    sid       = "WriteArchiveFiles"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.archive.arn}/archive/*"]
  }
}

resource "aws_iam_role_policy" "archiver" {
  name   = "write-archive"
  role   = aws_iam_role.archiver.id
  policy = data.aws_iam_policy_document.archiver.json
}

# The replay can list and read under archive/ and nothing else. Listing is a permission on the
# bucket rather than on objects, so it is narrowed to the prefix with a condition.
data "aws_iam_policy_document" "archive_replay" {
  statement {
    sid       = "ReadArchiveFiles"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.archive.arn}/archive/*"]
  }
  statement {
    sid       = "ListArchiveFiles"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.archive.arn]
    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["archive/*"]
    }
  }
}

resource "aws_iam_role_policy" "archive_replay" {
  name   = "read-archive"
  role   = aws_iam_role.archive_replay.id
  policy = data.aws_iam_policy_document.archive_replay.json
}
