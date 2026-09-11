# The public view: a page anybody can open, fed by what the laptop archived.
#
# The live dashboard cannot be public. Its API runs on a Kind cluster on a laptop behind a home
# router, which has no public address -- the same fact that made deployment pulled rather than pushed
# in S19. So the public page is fed by something AWS already holds, the S21 archive, and it says so
# on screen.
#
#   archiver -> S3 archive/ --(file finished)--> index function --(folds it once)--> DynamoDB table
#                                                                                        ^
#   browser -> CloudFront -+- /*     -> site bucket: the dashboard, built in archive mode  |
#                          +- /api/* -> lookup function (function URL) --------------------+
#
# Every part is chosen for how it bills (ADR 0001):
#
#   * Each archive file is read ONCE, when it lands. What S3 is asked for rises with what the laptop
#     produced, never with how many people look.
#   * Visitors cost CloudFront, Lambda and DynamoDB requests, all inside allowances that do not
#     expire: 1 TB and 10M requests, 1M invocations and 400,000 GB-seconds, 25 read and 25 write
#     capacity units.
#   * The table's capacity is PROVISIONED, not on-demand. On-demand bills per request and has no
#     free allowance; provisioned capacity inside 25 units is free, and exceeding it throttles
#     rather than bills. A flood of visitors slows the page down instead of running up a charge.
#   * No custom domain. A Route 53 hosted zone is $0.50 a month whether used or not; the
#     *.cloudfront.net name comes with a certificate for nothing.

locals {
  public_name = "fleet-tracker-public"

  # The three topics the page is made of. Status readings are archived but not indexed, so a status
  # file never wakes the indexer: the notification below filters on these prefixes.
  public_indexed_topics = ["position.events.v1", "shipment.derived.v1", "exceptions.v1"]

  # Built by `./mvnw -pl functions/public-view -am package`; scripts/infra-up.sh builds it if it is
  # missing. Terraform uploads it once, when a function is created. After that the code belongs to
  # CI (the publish-public job), and Terraform is told to ignore it -- see the lifecycle blocks.
  public_view_zip = "${path.module}/../../functions/public-view/target/public-view.zip"

  public_functions = {
    index = {
      name    = "${local.public_name}-index"
      handler = "com.fleettracking.publicview.index.IndexHandler::handleRequest"
      # An hour of positions is tens of thousands of lines to parse and a few dozen rows to write
      # against ten write units. Seconds, normally; the headroom is for a throttled table.
      timeout = 120
    }
    lookup = {
      name    = "${local.public_name}-lookup"
      handler = "com.fleettracking.publicview.lookup.LookupHandler::handleRequest"
      timeout = 10
    }
  }
}

# ------------------------------------------------------------------------------------------------
# The table
# ------------------------------------------------------------------------------------------------

resource "aws_dynamodb_table" "public" {
  name = local.public_name

  # Ten of each. The always-free allowance is 25 of each across the whole account, so this leaves
  # room for one more table before anything is billed. Unused capacity also banks up to five
  # minutes of burst, which is what absorbs the indexer writing a whole hour at once.
  billing_mode   = "PROVISIONED"
  read_capacity  = 10
  write_capacity = 10

  # One partition per shipment, one row per fact. See PublicTable.java for the layout and why.
  hash_key  = "shipmentId"
  range_key = "fact"

  attribute {
    name = "shipmentId"
    type = "S"
  }
  attribute {
    name = "fact"
    type = "S"
  }

  # Rows carry an epoch-seconds expiry thirty days ahead, stamped on every write, and DynamoDB
  # deletes them after it at no charge. Thirty days is what the archive keeps of everything but
  # positions.
  ttl {
    attribute_name = "expiresAt"
    enabled        = true
  }

  # Everything here can be rebuilt from the archive with scripts/public-backfill.sh, and M8's exit
  # criterion is that destroy removes everything. Point-in-time recovery would bill per GB-month for
  # a backup of a copy.
  deletion_protection_enabled = false
  point_in_time_recovery {
    enabled = false
  }
}

# ------------------------------------------------------------------------------------------------
# The two functions
# ------------------------------------------------------------------------------------------------

data "aws_iam_policy_document" "lambda_trust" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
    # Only a function in this account. Without it the confused-deputy case is open: Lambda acting
    # for some other account's function could be handed this role by a misconfiguration.
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}

resource "aws_iam_role" "public" {
  for_each             = local.public_functions
  name                 = each.value.name
  description          = "Assumed by the ${each.value.name} Lambda function."
  assume_role_policy   = data.aws_iam_policy_document.lambda_trust.json
  max_session_duration = 3600
}

# Logs, explicitly: a log group Terraform owns, with a retention, rather than the one Lambda creates
# on first run and keeps for ever. CloudWatch Logs' always-free allowance is 5 GB, and "for ever" is
# how a free allowance is eventually outgrown.
resource "aws_cloudwatch_log_group" "public" {
  for_each          = local.public_functions
  name              = "/aws/lambda/${each.value.name}"
  retention_in_days = 3
}

data "aws_iam_policy_document" "public_index" {
  # Read archive files. Not list them, not write them, not delete them.
  statement {
    sid       = "ReadArchiveFiles"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.archive.arn}/archive/*"]
  }
  # Write rows. UpdateItem only: every write the indexer makes is one conditional update.
  statement {
    sid       = "WriteFacts"
    actions   = ["dynamodb:UpdateItem"]
    resources = [aws_dynamodb_table.public.arn]
  }
  statement {
    sid       = "Log"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.public["index"].arn}:*"]
  }
}

data "aws_iam_policy_document" "public_lookup" {
  # Read rows, and nothing else anywhere: the function that faces the internet can change nothing.
  statement {
    sid       = "ReadFacts"
    actions   = ["dynamodb:Scan", "dynamodb:Query"]
    resources = [aws_dynamodb_table.public.arn]
  }
  statement {
    sid       = "Log"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.public["lookup"].arn}:*"]
  }
}

resource "aws_iam_role_policy" "public_index" {
  name   = "index-archive"
  role   = aws_iam_role.public["index"].id
  policy = data.aws_iam_policy_document.public_index.json
}

resource "aws_iam_role_policy" "public_lookup" {
  name   = "read-public-table"
  role   = aws_iam_role.public["lookup"].id
  policy = data.aws_iam_policy_document.public_lookup.json
}

resource "aws_lambda_function" "public" {
  for_each      = local.public_functions
  function_name = each.value.name
  role          = aws_iam_role.public[each.key].arn
  handler       = each.value.handler
  timeout       = each.value.timeout

  # Java 21 on Graviton. Nothing in the zip is native code, so the architecture is a price choice:
  # arm64 is billed about 20% less per GB-second once past the free allowance.
  runtime       = "java21"
  architectures = ["arm64"]

  # Lambda hands out CPU in proportion to memory, and a JVM starting cold is CPU-bound: at 512 MB a
  # cold start is several seconds, at 1 GB about half that. 400,000 GB-seconds a month is 400,000
  # seconds at this size, against invocations that take well under one.
  memory_size = 1024

  filename = local.public_view_zip

  environment {
    variables = {
      TABLE_NAME = aws_dynamodb_table.public.name
      # Stop the JIT at its first tier. The full optimizing compiler pays off over minutes of
      # running; a function that lives for one request mostly pays for starting it. This is AWS's
      # own advice for Java cold starts.
      JAVA_TOOL_OPTIONS = "-XX:+TieredCompilation -XX:TieredStopAtLevel=1"
    }
  }

  logging_config {
    log_format = "Text"
    log_group  = aws_cloudwatch_log_group.public[each.key].name
  }

  lifecycle {
    # The code is deployed by CI on every merge to main. Without this, the next `terraform apply`
    # from a laptop would put back whatever zip that laptop last built.
    ignore_changes = [filename, source_code_hash]
  }

  depends_on = [
    aws_iam_role_policy.public_index,
    aws_iam_role_policy.public_lookup,
  ]
}

# ------------------------------------------------------------------------------------------------
# Archive -> indexer
# ------------------------------------------------------------------------------------------------

resource "aws_lambda_permission" "index_from_archive" {
  statement_id   = "InvokedByArchiveBucket"
  action         = "lambda:InvokeFunction"
  function_name  = aws_lambda_function.public["index"].function_name
  principal      = "s3.amazonaws.com"
  source_arn     = aws_s3_bucket.archive.arn
  source_account = data.aws_caller_identity.current.account_id
}

# One notification configuration per bucket: this resource owns all of the archive bucket's. Three
# rules, one per indexed topic, because a rule filters on a single prefix and the prefixes must not
# overlap. Asynchronous: S3 does not wait, and Lambda retries a failed invocation twice.
resource "aws_s3_bucket_notification" "archive" {
  bucket = aws_s3_bucket.archive.id

  dynamic "lambda_function" {
    for_each = toset(local.public_indexed_topics)
    content {
      id                  = "index-${lambda_function.value}"
      lambda_function_arn = aws_lambda_function.public["index"].arn
      events              = ["s3:ObjectCreated:*"]
      filter_prefix       = "archive/${lambda_function.value}/"
      filter_suffix       = ".ndjson.gz"
    }
  }

  # S3 test-invokes the function when the rule is saved and refuses the rule if it cannot.
  depends_on = [aws_lambda_permission.index_from_archive]
}

# ------------------------------------------------------------------------------------------------
# The front door
# ------------------------------------------------------------------------------------------------

# The lookup's URL requires AWS signatures. Nothing on the internet can call it directly; CloudFront
# signs its own requests (origin access control, below) and the permissions after it admit exactly
# this distribution.
resource "aws_lambda_function_url" "lookup" {
  function_name      = aws_lambda_function.public["lookup"].function_name
  authorization_type = "AWS_IAM"
}

# BOTH of these are needed, and the second is the one that gets forgotten. Since October 2025 a new
# function URL checks lambda:InvokeFunction as well as lambda:InvokeFunctionUrl; with only the first,
# CloudFront gets a 403 that looks exactly like a signing problem.
resource "aws_lambda_permission" "lookup_url_from_cloudfront" {
  statement_id           = "CloudFrontInvokeFunctionUrl"
  action                 = "lambda:InvokeFunctionUrl"
  function_name          = aws_lambda_function.public["lookup"].function_name
  principal              = "cloudfront.amazonaws.com"
  source_arn             = aws_cloudfront_distribution.public.arn
  function_url_auth_type = "AWS_IAM"
}

resource "aws_lambda_permission" "lookup_invoke_from_cloudfront" {
  statement_id  = "CloudFrontInvokeFunction"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.public["lookup"].function_name
  principal     = "cloudfront.amazonaws.com"
  source_arn    = aws_cloudfront_distribution.public.arn
}

resource "aws_s3_bucket" "public_site" {
  bucket = "${local.public_name}-site-${data.aws_caller_identity.current.account_id}"

  # A build of the dashboard. CI uploads a fresh one on every merge.
  force_destroy = true
}

# Private. The site is public through CloudFront and only through CloudFront.
resource "aws_s3_bucket_public_access_block" "public_site" {
  bucket                  = aws_s3_bucket.public_site.id
  block_public_acls       = true
  ignore_public_acls      = true
  block_public_policy     = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "public_site" {
  bucket = aws_s3_bucket.public_site.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

data "aws_iam_policy_document" "public_site" {
  # CloudFront may read objects, on behalf of this distribution only. Not a public policy -- the
  # principal is a service, narrowed to one distribution -- which is why the public access block
  # above can stay fully on.
  statement {
    sid       = "CloudFrontReadsTheSite"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.public_site.arn}/*"]
    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.public.arn]
    }
  }

  statement {
    sid     = "TlsOnly"
    effect  = "Deny"
    actions = ["s3:*"]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    resources = [aws_s3_bucket.public_site.arn, "${aws_s3_bucket.public_site.arn}/*"]
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "public_site" {
  bucket     = aws_s3_bucket.public_site.id
  policy     = data.aws_iam_policy_document.public_site.json
  depends_on = [aws_s3_bucket_public_access_block.public_site]
}

resource "aws_cloudfront_origin_access_control" "public_site" {
  name                              = "${local.public_name}-site"
  description                       = "CloudFront signs its reads of the site bucket."
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

resource "aws_cloudfront_origin_access_control" "public_lookup" {
  name                              = "${local.public_name}-lookup"
  description                       = "CloudFront signs its calls to the lookup function URL."
  origin_access_control_origin_type = "lambda"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

# How /api/* is cached. The lookup answers max-age=60, and this honours that within 0-300 seconds.
#
# The cache key is the path plus one query parameter, `open`, the only one the API reads. Every other
# query string is dropped before the cache is consulted, so ?x=1, ?x=2 ... cannot each become a
# fresh invocation. No headers and no cookies are forwarded; in particular not Host, which the
# function URL checks against its own name when verifying CloudFront's signature.
resource "aws_cloudfront_cache_policy" "public_api" {
  name        = "${local.public_name}-api"
  comment     = "Path plus ?open, honouring the lookup's max-age"
  min_ttl     = 0
  default_ttl = 60
  max_ttl     = 300

  parameters_in_cache_key_and_forwarded_to_origin {
    enable_accept_encoding_gzip   = true
    enable_accept_encoding_brotli = true
    cookies_config {
      cookie_behavior = "none"
    }
    headers_config {
      header_behavior = "none"
    }
    query_strings_config {
      query_string_behavior = "whitelist"
      query_strings {
        items = ["open"]
      }
    }
  }
}

# AWS-managed policies, looked up by name rather than pasted in as ids.
data "aws_cloudfront_cache_policy" "caching_optimized" {
  name = "Managed-CachingOptimized"
}

data "aws_cloudfront_response_headers_policy" "security_headers" {
  name = "Managed-SecurityHeadersPolicy"
}

resource "aws_cloudfront_distribution" "public" {
  enabled             = true
  comment             = "fleet-tracker public view"
  default_root_object = "index.html"
  is_ipv6_enabled     = true
  http_version        = "http2and3"

  # Which edge locations serve it. The cheapest class, 100, covers North America and Europe only,
  # which would serve a page about Indian freight lanes from Frankfurt. 200 adds India. The free
  # allowance applies to requests and bytes wherever they are served.
  price_class = "PriceClass_200"

  origin {
    origin_id                = "site"
    domain_name              = aws_s3_bucket.public_site.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.public_site.id
  }

  origin {
    origin_id = "lookup"
    # The function URL is https://<id>.lambda-url.<region>.on.aws/ and an origin wants the host.
    domain_name              = trimsuffix(trimprefix(aws_lambda_function_url.lookup.function_url, "https://"), "/")
    origin_access_control_id = aws_cloudfront_origin_access_control.public_lookup.id
    custom_origin_config {
      http_port              = 80
      https_port             = 443
      origin_protocol_policy = "https-only"
      origin_ssl_protocols   = ["TLSv1.2"]
    }
  }

  # The dashboard. Its asset names carry a content hash, so they are cached for as long as CloudFront
  # likes; index.html is the one file that changes under the same name, and CI invalidates it.
  default_cache_behavior {
    target_origin_id           = "site"
    viewer_protocol_policy     = "redirect-to-https"
    allowed_methods            = ["GET", "HEAD"]
    cached_methods             = ["GET", "HEAD"]
    cache_policy_id            = data.aws_cloudfront_cache_policy.caching_optimized.id
    response_headers_policy_id = data.aws_cloudfront_response_headers_policy.security_headers.id
    compress                   = true
  }

  # The lookup. GET and HEAD only, at the edge: a POST is refused by CloudFront and never reaches the
  # function, which has no write path anyway.
  ordered_cache_behavior {
    path_pattern               = "/api/*"
    target_origin_id           = "lookup"
    viewer_protocol_policy     = "redirect-to-https"
    allowed_methods            = ["GET", "HEAD"]
    cached_methods             = ["GET", "HEAD"]
    cache_policy_id            = aws_cloudfront_cache_policy.public_api.id
    response_headers_policy_id = data.aws_cloudfront_response_headers_policy.security_headers.id
    compress                   = true
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    cloudfront_default_certificate = true
  }
}

# ------------------------------------------------------------------------------------------------
# What CI may do: its first real permissions
# ------------------------------------------------------------------------------------------------
#
# S20 gave the CI role no permissions at all and promised each grant would arrive with its reason.
# These are the publish-public job's, and each names exactly one resource.

data "aws_iam_policy_document" "github_actions_public" {
  # Upload the dashboard build, and remove files the new build no longer has (`aws s3 sync --delete`,
  # which also needs to list what is there).
  statement {
    sid       = "PublishSite"
    actions   = ["s3:PutObject", "s3:DeleteObject"]
    resources = ["${aws_s3_bucket.public_site.arn}/*"]
  }
  statement {
    sid       = "ListSite"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.public_site.arn]
  }
  # Tell the edge that index.html has changed. The first 1,000 invalidation paths a month are free,
  # and a merge uses one.
  statement {
    sid       = "RefreshEdge"
    actions   = ["cloudfront:CreateInvalidation", "cloudfront:GetInvalidation"]
    resources = [aws_cloudfront_distribution.public.arn]
  }
  # Replace the two functions' code, and wait until the replacement is live. Not their
  # configuration, not their role, not their permissions: those are Terraform's.
  statement {
    sid       = "DeployFunctionCode"
    actions   = ["lambda:UpdateFunctionCode", "lambda:GetFunctionConfiguration"]
    resources = [for f in aws_lambda_function.public : f.arn]
  }
}

resource "aws_iam_role_policy" "github_actions_public" {
  name   = "publish-public-view"
  role   = aws_iam_role.github_actions.id
  policy = data.aws_iam_policy_document.github_actions_public.json
}
