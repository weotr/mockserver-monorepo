# Terraform Snippet: Publish Secrets IAM Grants

## TL;DR

Once the five secrets (`mockserver-release/{nuget,crates,vsce,ovsx,jetbrains}`)
exist in Secrets Manager, apply the additions below to
`terraform/buildkite-agents/build-secrets.tf` so the release-queue IAM policy
can `GetSecretValue` and `DescribeSecret` on them.

**Do NOT apply this before the secrets exist** -- the `data` source lookups will
fail during `terraform plan` if the secret does not yet exist in the account.

## How it fits the existing pattern

The existing file uses `data "aws_secretsmanager_secret"` blocks for
out-of-band secrets (cosign-key, ghcr-token, mcp-dns-key) and references their
`.arn` in the `read_release_secrets` policy. The five new secrets follow the
same pattern exactly:

1. Five new `data "aws_secretsmanager_secret"` blocks (lookup by name)
2. Their ARNs added to the `GetSecretValue` statement in `read_release_secrets`
3. Their ARNs added to the `DescribeSecret` statement in `read_release_secrets`
   (because every component script probes with `describe-secret` before reading)

## Diff to apply to `terraform/buildkite-agents/build-secrets.tf`

```diff
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

+# NuGet API key (mockserver-release/nuget, key: api_key) is created out of
+# band. Read by client-dotnet.sh and tc-dotnet.sh (release queue) to push
+# MockServerClient and Testcontainers.MockServer packages to NuGet.org.
+data "aws_secretsmanager_secret" "nuget" {
+  name = "mockserver-release/nuget"
+}
+
+# crates.io API token (mockserver-release/crates, key: token) is created out
+# of band. Read by client-rust.sh and tc-rust.sh (release queue) to publish
+# mockserver-client and testcontainers-mockserver crates to crates.io.
+data "aws_secretsmanager_secret" "crates" {
+  name = "mockserver-release/crates"
+}
+
+# VS Code Marketplace PAT (mockserver-release/vsce, key: token) is created out
+# of band. Read by vscode.sh (release queue) to publish the mockserver-vscode
+# extension via `vsce publish`.
+data "aws_secretsmanager_secret" "vsce" {
+  name = "mockserver-release/vsce"
+}
+
+# Open VSX Registry PAT (mockserver-release/ovsx, key: token) is created out
+# of band. Read by vscode.sh (release queue) to publish the mockserver-vscode
+# extension via `ovsx publish`.
+data "aws_secretsmanager_secret" "ovsx" {
+  name = "mockserver-release/ovsx"
+}
+
+# JetBrains Marketplace token (mockserver-release/jetbrains, key: token) is
+# created out of band. Read by jetbrains.sh (release queue) to publish the
+# mockserver-jetbrains plugin via `./gradlew publishPlugin`.
+data "aws_secretsmanager_secret" "jetbrains" {
+  name = "mockserver-release/jetbrains"
+}
+
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
+          data.aws_secretsmanager_secret.nuget.arn,
+          data.aws_secretsmanager_secret.crates.arn,
+          data.aws_secretsmanager_secret.vsce.arn,
+          data.aws_secretsmanager_secret.ovsx.arn,
+          data.aws_secretsmanager_secret.jetbrains.arn,
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
+        #   - nuget       -> client-dotnet.sh + tc-dotnet.sh NuGet publish gate
+        #   - crates      -> client-rust.sh + tc-rust.sh crates.io publish gate
+        #   - vsce        -> vscode.sh VS Code Marketplace publish gate
+        #   - ovsx        -> vscode.sh Open VSX publish gate
+        #   - jetbrains   -> jetbrains.sh JetBrains Marketplace publish gate
         # Metadata-only; the values are still read via GetSecretValue above.
         Effect = "Allow"
         Action = "secretsmanager:DescribeSecret"
         Resource = [
           data.aws_secretsmanager_secret.cosign.arn,
           data.aws_secretsmanager_secret.ghcr_token.arn,
           data.aws_secretsmanager_secret.mcp_dns_key.arn,
+          data.aws_secretsmanager_secret.nuget.arn,
+          data.aws_secretsmanager_secret.crates.arn,
+          data.aws_secretsmanager_secret.vsce.arn,
+          data.aws_secretsmanager_secret.ovsx.arn,
+          data.aws_secretsmanager_secret.jetbrains.arn,
         ]
       },
```

## Applying

```bash
cd terraform/buildkite-agents
terraform init
terraform plan    # review the policy diff — should show only the IAM policy update
terraform apply
```

The only resource that changes is `aws_iam_policy.read_release_secrets` (in-place
update to the policy document). No secrets, roles, or instance profiles are
created or destroyed.
