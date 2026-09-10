output "github_actions_role_arn" {
  description = "Stored as the AWS_ROLE_ARN repository variable -- a variable, not a secret: it is an address, not a credential."
  value       = aws_iam_role.github_actions.arn
}
