# The cloud stack: everything this project runs in AWS. As of S20 that is only the trust that lets
# GitHub Actions act in this account; S21 adds the event archive and S22 the public demo.

terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.64"
    }
  }

  # State in the bucket the bootstrap stack made. The bucket name contains the account id, so it is
  # supplied at init time rather than written here: scripts/infra-up.sh reads it from the bootstrap
  # stack's outputs and passes -backend-config=bucket=...
  #
  # use_lockfile: while one apply runs, a lock object sits next to the state and a second apply is
  # refused rather than both writing and one silently losing. Before Terraform 1.10 this needed a
  # DynamoDB table; now the bucket does it alone.
  backend "s3" {
    key          = "cloud/terraform.tfstate"
    region       = "ap-south-1"
    use_lockfile = true
    encrypt      = true
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project   = "fleet-tracker"
      ManagedBy = "terraform"
      Stack     = "cloud"
    }
  }
}
