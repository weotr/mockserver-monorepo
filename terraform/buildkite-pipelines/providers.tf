# Dedicated Terraform management token (read_pipelines, write_pipelines, graphql),
# stored in Secrets Manager so `terraform plan`/`apply` can run unattended now and
# in future. This MUST be a classic Buildkite API Access Token — the `bk` CLI login
# is an OAuth token the GraphQL/Terraform API rejects. Create/rotate it at
# https://buildkite.com/user/api-access-tokens (org: mockserver) and store the value
# in mockserver-build/buildkite-tf-token; the value is managed manually in AWS, not
# by Terraform.
#
# Deliberately NOT readable by any build agent: no build-queue IAM policy grants this
# secret (the read-build-secrets / read-release-secrets policies use explicit per-secret
# ARN allowlists that exclude it — see terraform/buildkite-agents/build-secrets.tf). It
# is read only by a local admin (AdministratorAccess SSO) running this stack. Kept
# separate from mockserver-build/buildkite-api-token, the narrow CI build-control token
# (read_builds/write_builds) that agents DO read and which must never manage pipelines.
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
