variable "region" {
  description = "Region for the state bucket. Budgets are global and ignore it."
  type        = string
  default     = "ap-south-1"
}

variable "budget_email" {
  description = "Where the spending alert is sent. Set in terraform.tfvars, which is gitignored."
  type        = string

  validation {
    condition     = can(regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", var.budget_email))
    error_message = "budget_email must be an email address."
  }
}
