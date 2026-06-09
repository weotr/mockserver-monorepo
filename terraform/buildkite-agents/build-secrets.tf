# ---------------------------------------------------------------------------
# Secrets Manager secrets and per-secret IAM policies
# ---------------------------------------------------------------------------
# Each secret has its own narrowly-scoped IAM policy so queues receive only
# the credentials they actually consume.  Attachment to queues is in main.tf.
#
# Queue -> secret mapping (verified by grepping .buildkite/scripts):
#   default:  buildkite-api-token-readonly (generate-pipeline.sh change detection),
#             buildkite-api-token (cleanup-closed-pr-builds.sh, via read_build_secrets_default),
#             dockerhub (docker-login.sh for snapshot push),
#             sonatype (java-deploy-snapshot.sh, master-only)
#   trigger:  buildkite-api-token (trigger-pipeline.sh orchestration, write),
#             buildkite-api-token-readonly (perf-test-guard.sh change detection)
#   perf:     buildkite-api-token-readonly (perf-test-guard.sh commit comparison)
#   release:  buildkite-api-token, dockerhub, sonatype, pypi, rubygems,
#             plus release-only secrets in read_release_secrets
#
# The Buildkite API token is split three ways: a READ-ONLY token for change
# detection, a WRITE token (buildkite-api-token) for trigger/cleanup build
# control, and a separate management token (buildkite-tf-token) used only by the
# Terraform provider (never granted to a queue).
# ---------------------------------------------------------------------------

# --- Secret resources -------------------------------------------------------

resource "aws_secretsmanager_secret" "dockerhub" {
  name        = "mockserver-build/dockerhub"
  description = "Docker Hub credentials for pushing mockserver CI and release images"
}

resource "aws_secretsmanager_secret" "buildkite_api_token" {
  name        = "mockserver-build/buildkite-api-token"
  description = "Buildkite API token for Terraform pipeline management (GraphQL + REST scopes)"
}

resource "aws_secretsmanager_secret" "sonatype" {
  name        = "mockserver-build/sonatype"
  description = "Sonatype OSSRH credentials for Maven snapshot and release deployment"
}

resource "aws_secretsmanager_secret" "pypi" {
  name        = "mockserver-build/pypi"
  description = "PyPI API token for publishing mockserver-client Python package"
}

resource "aws_secretsmanager_secret" "rubygems" {
  name        = "mockserver-build/rubygems"
  description = "RubyGems API key for publishing mockserver-client Ruby gem"
}

resource "aws_secretsmanager_secret" "gpg_key" {
  name        = "mockserver-release/gpg-key"
  description = "GPG private key and passphrase for Maven Central artifact signing"
}

resource "aws_secretsmanager_secret" "github_token" {
  name        = "mockserver-release/github-token"
  description = "GitHub PAT for creating releases and Homebrew PRs"
}

resource "aws_secretsmanager_secret" "totp_seed" {
  name        = "mockserver-release/totp-seed"
  description = "TOTP shared secret for release authorization"
}

resource "aws_secretsmanager_secret" "npm_token" {
  name        = "mockserver-release/npm-token"
  description = "npm automation token for publishing packages"
}

resource "aws_secretsmanager_secret" "swaggerhub" {
  name        = "mockserver-release/swaggerhub"
  description = "SwaggerHub API key for publishing OpenAPI spec"
}

resource "aws_secretsmanager_secret" "website_role" {
  name        = "mockserver-release/website-role"
  description = "IAM role ARN for cross-account website access"
}

# --- Per-secret IAM policies ------------------------------------------------

# Buildkite API token: consumed by trigger-pipeline.sh (trigger queue),
# generate-pipeline.sh (default queue), perf-test-guard.sh (perf queue),
# cleanup-closed-pr-builds.sh (default queue), and release scripts.
#
# This grants the WRITE-scoped CI build-control token (read_builds + write_builds):
# trigger-pipeline.sh (create/cancel builds) and cleanup-closed-pr-builds.sh
# (cancel/delete). Change-detection (last-successful-commit.sh) uses the separate
# READ-ONLY token below. The Terraform provider's pipeline/cluster management uses
# yet another token (mockserver-build/buildkite-tf-token) read only by local admin.
resource "aws_iam_policy" "read_buildkite_api_token" {
  name        = "buildkite-read-buildkite-api-token"
  description = "Allow Buildkite agents to read the Buildkite API token from Secrets Manager"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = "secretsmanager:GetSecretValue"
      Resource = [aws_secretsmanager_secret.buildkite_api_token.arn]
    }]
  })
}

# READ-ONLY Buildkite API token (read_builds + read_pipelines) used by
# last-successful-commit.sh for change detection / perf-guard comparison. Created
# out of band, referenced via a data source for its ARN. Defence-in-depth: the
# change-detection code path physically cannot create/cancel/delete builds.
data "aws_secretsmanager_secret" "buildkite_api_token_readonly" {
  name = "mockserver-build/buildkite-api-token-readonly"
}

resource "aws_iam_policy" "read_buildkite_api_token_readonly" {
  name        = "buildkite-read-buildkite-api-token-readonly"
  description = "Allow Buildkite agents to read the READ-ONLY Buildkite API token (change detection) from Secrets Manager"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = "secretsmanager:GetSecretValue"
      Resource = [data.aws_secretsmanager_secret.buildkite_api_token_readonly.arn]
    }]
  })
}

# Docker Hub credentials are split by purpose: this SNAPSHOT secret
# (mockserver-build/dockerhub) is read by the default queue for snapshot/CI image
# pushes; the RELEASE secret (mockserver-release/dockerhub, data source below) is
# read only by the release queue. So a compromised default-queue agent cannot
# obtain the release-designated credential.
#
# NOTE: Docker Hub personal access tokens cannot be scoped to a single repo/tag
# on the current plan, so both tokens technically have the same Docker Hub push
# rights. The benefit of the split is therefore credential separation: independent
# rotation/revocation, separate audit trail, and the AWS-level guarantee above
# that the default queue physically cannot read the release token.
resource "aws_iam_policy" "read_dockerhub_secret" {
  name        = "buildkite-read-dockerhub-secret"
  description = "Allow Buildkite agents to read Docker Hub credentials from Secrets Manager"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = "secretsmanager:GetSecretValue"
      Resource = [aws_secretsmanager_secret.dockerhub.arn]
    }]
  })
}

# Release-queue Docker Hub credentials (created out of band; referenced via a
# data source for its ARN).
data "aws_secretsmanager_secret" "dockerhub_release" {
  name = "mockserver-release/dockerhub"
}

resource "aws_iam_policy" "read_dockerhub_release_secret" {
  name        = "buildkite-read-dockerhub-release-secret"
  description = "Allow release-queue Buildkite agents to read the RELEASE Docker Hub credentials from Secrets Manager"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = "secretsmanager:GetSecretValue"
      Resource = [data.aws_secretsmanager_secret.dockerhub_release.arn]
    }]
  })
}

# Build secrets for the DEFAULT queue: buildkite-api-token + sonatype.
# Docker Hub is handled by read_dockerhub_secret (separate policy).
resource "aws_iam_policy" "read_build_secrets_default" {
  name        = "buildkite-read-build-secrets-default"
  description = "Allow default-queue Buildkite agents to read build credentials from Secrets Manager"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = "secretsmanager:GetSecretValue"
      Resource = [
        aws_secretsmanager_secret.buildkite_api_token.arn,
        aws_secretsmanager_secret.sonatype.arn,
      ]
    }]
  })
}

# Build secrets for the RELEASE queue: buildkite-api-token + sonatype + pypi + rubygems.
# Docker Hub is handled by read_dockerhub_secret (separate policy).
# Release-only secrets (GPG, GitHub, npm, etc.) are in read_release_secrets.
resource "aws_iam_policy" "read_build_secrets_release" {
  name        = "buildkite-read-build-secrets-release"
  description = "Allow release-queue Buildkite agents to read build credentials from Secrets Manager"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = "secretsmanager:GetSecretValue"
      Resource = [
        aws_secretsmanager_secret.buildkite_api_token.arn,
        aws_secretsmanager_secret.sonatype.arn,
        aws_secretsmanager_secret.pypi.arn,
        aws_secretsmanager_secret.rubygems.arn,
      ]
    }]
  })
}

# The cosign signing key (mockserver-release/cosign-key, keys: key + password)
# is created out of band — it holds a private signing key whose value we don't
# want in Terraform state — so it's referenced as a data source purely to get
# its ARN for the IAM grant below. Both docker.sh and helm.sh (release queue)
# read it to sign published images/charts.
data "aws_secretsmanager_secret" "cosign" {
  name = "mockserver-release/cosign-key"
}

# GHCR token (mockserver-release/ghcr-token, keys: username + token) is created
# out of band and read by helm.sh (release queue) to `helm registry login
# ghcr.io` and push the OCI chart to oci://ghcr.io/mock-server/charts.
# Referenced as a data source purely for its ARN in the IAM grant below.
data "aws_secretsmanager_secret" "ghcr_token" {
  name = "mockserver-release/ghcr-token"
}

# MCP registry DNS key (mockserver-release/mcp-dns-key, key: private_key) is
# created out of band — it holds an ed25519 private key we don't want in
# Terraform state. Read by scripts/release/components/mcp.sh (release queue) to
# `mcp-publisher login dns` and publish server.json to the official MCP registry
# under the DNS-verified com.mock-server namespace; the matching public key is
# in the mock-server.com apex TXT record (terraform/website/mcp-dns.tf).
# Referenced as a data source purely for its ARN in the IAM grant below.
data "aws_secretsmanager_secret" "mcp_dns_key" {
  name = "mockserver-release/mcp-dns-key"
}

# Release-only secrets.
resource "aws_iam_policy" "read_release_secrets" {
  name        = "buildkite-read-release-secrets"
  description = "Allow Buildkite agents to read release credentials from Secrets Manager"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = "secretsmanager:GetSecretValue"
        Resource = [
          aws_secretsmanager_secret.gpg_key.arn,
          aws_secretsmanager_secret.github_token.arn,
          aws_secretsmanager_secret.totp_seed.arn,
          aws_secretsmanager_secret.npm_token.arn,
          aws_secretsmanager_secret.swaggerhub.arn,
          aws_secretsmanager_secret.website_role.arn,
          data.aws_secretsmanager_secret.cosign.arn,
          data.aws_secretsmanager_secret.ghcr_token.arn,
          data.aws_secretsmanager_secret.mcp_dns_key.arn,
        ]
      },
      {
        # Several release components gate on a value-free
        # `aws secretsmanager describe-secret` probe ("is this configured?")
        # before reading the value. DescribeSecret is a distinct action from
        # GetSecretValue, so without this grant the probe fails with AccessDenied
        # and the feature silently skips:
        #   - cosign-key  -> docker.sh + helm.sh image/chart signing
        #   - ghcr-token  -> docker.sh GHCR image mirror (MIRROR_GHCR gate)
        #   - mcp-dns-key -> mcp.sh MCP registry publish gate
        # Metadata-only; the values are still read via GetSecretValue above.
        Effect = "Allow"
        Action = "secretsmanager:DescribeSecret"
        Resource = [
          data.aws_secretsmanager_secret.cosign.arn,
          data.aws_secretsmanager_secret.ghcr_token.arn,
          data.aws_secretsmanager_secret.mcp_dns_key.arn,
        ]
      },
      {
        # Cross-account assume of the website-release role.
        # Account ID is the mockserver-website account (014848309742). The
        # target role's trust policy is already scoped to a specific build-account
        # role ARN, so this is defence-in-depth on the source side.
        Effect   = "Allow"
        Action   = "sts:AssumeRole"
        Resource = "arn:aws:iam::014848309742:role/mockserver-release-website"
      }
    ]
  })
}
