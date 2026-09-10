output "state_bucket" {
  description = "Passed to the other stacks' S3 backend by scripts/infra-up.sh."
  value       = aws_s3_bucket.state.bucket
}

output "region" {
  value = var.region
}
