// The S3 backend (terraform state) lives in the build account, so terraform
// runs with build-account credentials. Website-account resources are reached
// by having the provider assume the release-website role.
//
// In CI, scripts/release/components/versioned-site.sh passes the role ARN as
// TF_VAR_website_role_arn and the provider assumes it. Running manually, leave
// website_role_arn empty and `export AWS_PROFILE=mockserver-website` instead.
provider "aws" {
  region = "us-east-1"
  dynamic "assume_role" {
    for_each = var.website_role_arn == "" ? [] : [1]
    content {
      role_arn = var.website_role_arn
    }
  }
}

provider "aws" {
  alias  = "eu-west-2"
  region = "eu-west-2"
  dynamic "assume_role" {
    for_each = var.website_role_arn == "" ? [] : [1]
    content {
      role_arn = var.website_role_arn
    }
  }
}
