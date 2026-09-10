output "github_actions_role_arn" {
  description = "Stored as the AWS_ROLE_ARN repository variable -- a variable, not a secret: it is an address, not a credential."
  value       = aws_iam_role.github_actions.arn
}

output "github_actions_trusted_subject" {
  description = "Stored as the AWS_TRUSTED_SUBJECT repository variable, for the pull-request refusal check."
  value       = local.trusted_subject
}
