output "github_actions_role_arn" {
  description = "Stored as the AWS_ROLE_ARN repository variable -- a variable, not a secret: it is an address, not a credential."
  value       = aws_iam_role.github_actions.arn
}

output "github_actions_trusted_subject" {
  description = "Stored as the AWS_TRUSTED_SUBJECT repository variable, for the pull-request refusal check."
  value       = local.trusted_subject
}

# Read by scripts/aws-link.sh, which writes them into the cluster's archive-destination ConfigMap.
output "archive_bucket" {
  value = aws_s3_bucket.archive.bucket
}

output "archiver_role_arn" {
  value = aws_iam_role.archiver.arn
}

output "archive_replay_role_arn" {
  value = aws_iam_role.archive_replay.arn
}

output "cluster_issuer" {
  description = "Must equal the service-account-issuer flag in deploy/kind-cluster.yaml."
  value       = local.cluster_issuer
}

output "cluster_issuer_bucket" {
  value = aws_s3_bucket.cluster_issuer.bucket
}

# The public view (S22). The first is the address to open; the next two become repository variables
# the publish-public job reads, set by scripts/infra-up.sh like AWS_ROLE_ARN.
#
# The address is whichever front door is built: CloudFront's name when there is a distribution, the
# lookup function's own URL when there is not. Both are HTTPS with a certificate that came free.
output "public_url" {
  value = (
    var.cloudfront_enabled
    ? "https://${aws_cloudfront_distribution.public[0].domain_name}"
    : trimsuffix(aws_lambda_function_url.lookup.function_url, "/")
  )
}

output "public_site_bucket" {
  value = aws_s3_bucket.public_site.bucket
}

# Empty without CloudFront, which publish-public reads as "no edge to refresh". Terraform's
# `output -raw` exits 0 with empty stdout for this, so callers test emptiness -- see infra-up.sh.
output "public_distribution_id" {
  value = var.cloudfront_enabled ? aws_cloudfront_distribution.public[0].id : ""
}

output "public_table" {
  value = aws_dynamodb_table.public.name
}

output "public_index_function" {
  value = aws_lambda_function.public["index"].function_name
}
