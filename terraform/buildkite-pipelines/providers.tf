# Dedicated Terraform management token (read_pipelines, write_pipelines, graphql).
# Kept separate from mockserver-build/buildkite-api-token — that one is the narrow
# CI build-control token (read_builds/write_builds) read by trigger/cleanup agents,
# which must NOT be able to rewrite pipelines or manage the cluster. This secret is
# read only by local admin running Terraform; it is not granted to any build queue.
data "aws_secretsmanager_secret_version" "buildkite_tf_token" {
  secret_id = "mockserver-build/buildkite-tf-token"
}

provider "aws" {
  region  = "eu-west-2"
  profile = "mockserver-build"
}

provider "buildkite" {
  organization = "mockserver"
  api_token    = data.aws_secretsmanager_secret_version.buildkite_tf_token.secret_string
}
