variable "region" {
  type    = string
  default = "ap-south-1"
}

variable "github_repository" {
  description = "owner/name of the only repository whose workflows may assume the CI role."
  type        = string
  default     = "goutham-hegde/fleet-tracker"
}
