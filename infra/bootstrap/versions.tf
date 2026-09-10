# The bootstrap stack: the two things that must exist before anything else in AWS does.
#
#   1. A budget that emails when real spending passes one cent. First, because the rule on this
#      project is that nothing which can cost money is created before the alarm that says so.
#   2. The S3 bucket every other stack keeps its Terraform state in.
#
# Its own state is a local file, gitignored, and that is the unavoidable bootstrap: a stack cannot
# keep its state in a bucket that it is itself about to create. Losing that file costs little -- a
# budget and an empty-ish bucket, both importable -- which is exactly why nothing else lives here.

terraform {
  # 1.10 is the first release whose S3 backend can lock with a lock *file* in the bucket
  # (use_lockfile), so the other stacks need no DynamoDB table -- one fewer resource, one fewer
  # thing to be free or not.
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.64"
    }
  }
}

provider "aws" {
  region = var.region

  # Stamped on every resource that supports tags. "Everything this project owns" then becomes a
  # filter in the console and in Cost Explorer rather than a list somebody has to keep.
  default_tags {
    tags = {
      Project   = "fleet-tracker"
      ManagedBy = "terraform"
      Stack     = "bootstrap"
    }
  }
}
