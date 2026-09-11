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
