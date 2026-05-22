provider "aws" {
  region  = var.region
  profile = "mockserver-website"
}

data "aws_route53_zone" "domain" {
  name = var.domain
}

data "aws_caller_identity" "current" {}
