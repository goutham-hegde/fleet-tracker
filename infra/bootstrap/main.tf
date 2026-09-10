data "aws_caller_identity" "current" {}

# ------------------------------------------------------------------------------------------------
# The alarm. Created before anything else, and everything else depends on it.
# ------------------------------------------------------------------------------------------------
#
# A "zero-spend" budget: a nominal one-dollar limit, and an email the moment actual spending
# passes one cent. On a project whose bill is meant to be $0.00, the first cent is the whole
# story -- a percentage-of-budget alert would wait for the damage to be interesting.
#
# It watches the whole account, not only what carries this project's tags. The goal is that the
# account's bill is zero, and a resource created by hand in the console with no tags is exactly
# the kind that would otherwise go unnoticed.
#
# Budgets without actions are free. Its data refreshes a few times a day, so the email can arrive
# hours after the charge began: this is a smoke alarm, not a circuit breaker.
resource "aws_budgets_budget" "zero_spend" {
  name         = "fleet-tracker-zero-spend"
  budget_type  = "COST"
  limit_amount = "1.00"
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  # The line that makes this budget honest. On AWS's credit-based free plan every charge is paid
  # from credits first, so the bill -- and a budget left at its defaults -- reads $0.00 while
  # real money's worth of resources is being used. A charge that credits quietly absorbed is still
  # a failure of "$0", and still has to be found before the credits run out; so credits and
  # refunds are excluded and the budget sees gross cost.
  cost_types {
    include_credit = false
    include_refund = false
  }

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 0.01
    threshold_type             = "ABSOLUTE_VALUE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.budget_email]
  }
}

# ------------------------------------------------------------------------------------------------
# Where every other stack keeps its state.
# ------------------------------------------------------------------------------------------------
#
# State is Terraform's record of which real resource each block in the code corresponds to. Lose
# it and Terraform cannot find what it made: `terraform destroy` reports nothing to do while the
# resources carry on existing. So it lives here rather than on one laptop.
#
# Bucket names are global across every AWS customer, so the account id makes this one unique.
resource "aws_s3_bucket" "state" {
  bucket = "fleet-tracker-tfstate-${data.aws_caller_identity.current.account_id}"

  # Lets `terraform destroy` delete a versioned bucket that still holds objects. Without it the
  # last step of tearing the cloud presence down fails on "BucketNotEmpty" and has to be finished
  # by hand -- and M8's exit criterion is that destroy removes everything cleanly. This stack is
  # only ever destroyed last, after every stack whose state is in here has been destroyed first.
  force_destroy = true

  # Nothing that can cost money before the alarm exists.
  depends_on = [aws_budgets_budget.zero_spend]
}

# Every write keeps the previous version, so a state file corrupted by an interrupted apply or a
# bad manual edit can be rolled back rather than rebuilt by importing resources one at a time.
resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id
  versioning_configuration {
    status = "Enabled"
  }
}

# ...but not every version for ever. State files are kilobytes, so this is tidiness rather than
# cost, and a month of history is more than anybody will reach back for.
resource "aws_s3_bucket_lifecycle_configuration" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    id     = "expire-old-state-versions"
    status = "Enabled"
    filter {}

    noncurrent_version_expiration {
      noncurrent_days = 30
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }

  depends_on = [aws_s3_bucket_versioning.state]
}

# SSE-S3 (AES256, keys held by S3), not SSE-KMS. A customer-managed KMS key costs a dollar a month
# whether or not it is used, which on this project is an infinite percentage increase. New buckets
# are encrypted this way by default anyway; stating it means nobody has to know that.
resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# State can contain secrets in plain text -- anything a resource returns is recorded. It must never
# be reachable from the internet, whatever a later bucket policy or object ACL says.
resource "aws_s3_bucket_public_access_block" "state" {
  bucket                  = aws_s3_bucket.state.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# ACLs off: the account that owns the bucket owns every object in it, and access is decided by
# IAM and bucket policy alone rather than by a second, per-object permission system.
resource "aws_s3_bucket_ownership_controls" "state" {
  bucket = aws_s3_bucket.state.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

# Refuse any request not made over TLS.
resource "aws_s3_bucket_policy" "state" {
  bucket = aws_s3_bucket.state.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "DenyInsecureTransport"
      Effect    = "Deny"
      Principal = "*"
      Action    = "s3:*"
      Resource  = [aws_s3_bucket.state.arn, "${aws_s3_bucket.state.arn}/*"]
      Condition = { Bool = { "aws:SecureTransport" = "false" } }
    }]
  })

  depends_on = [aws_s3_bucket_public_access_block.state]
}
