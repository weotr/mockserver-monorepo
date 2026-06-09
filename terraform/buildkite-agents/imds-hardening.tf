# IMDS hop-limit hardening for the Buildkite build agents.
#
# WHY: build containers run untrusted PR code. The agents already run Docker with
# user-namespace remapping (container-root is an unprivileged host UID) and the
# Docker socket is withheld from PR builds, but a container can still reach the
# instance metadata service (169.254.169.254) and steal the agent's instance-role
# AWS credentials. The elastic-ci-stack launch template hardcodes the IMDS
# response hop limit to 2 (no module variable to change it), which lets a
# container — one network hop from the host — reach IMDS.
#
# FIX: a bootstrap script (run on each instance at boot via the module's
# `bootstrap_script_url`) sets the hop limit to 1. The agent runs ON the host
# (hop 0) and keeps IMDS access; build containers (hop 1 beyond the host) can no
# longer reach IMDS. Best-effort: a failure logs and leaves the boot succeeding
# rather than breaking the agent.

# Dedicated bucket holding the boot script the instances fetch at launch.
resource "aws_s3_bucket" "ci_bootstrap" {
  bucket = "mockserver-ci-bootstrap-${local.account_id}"
}

resource "aws_s3_bucket_public_access_block" "ci_bootstrap" {
  bucket                  = aws_s3_bucket.ci_bootstrap.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "ci_bootstrap" {
  bucket = aws_s3_bucket.ci_bootstrap.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_versioning" "ci_bootstrap" {
  bucket = aws_s3_bucket.ci_bootstrap.id
  versioning_configuration {
    status = "Enabled"
  }
}

locals {
  imds_bootstrap_key = "block-imds-from-containers.sh"
  imds_bootstrap_url = "s3://${aws_s3_bucket.ci_bootstrap.id}/${local.imds_bootstrap_key}"

  imds_bootstrap_script = <<-EOT
    #!/bin/bash
    # Set the IMDS response hop limit to 1 so build containers cannot reach
    # 169.254.169.254 and steal the agent instance-role credentials. The host
    # (this script) is hop 0 and keeps access. Best-effort — never fail the boot.
    set -uo pipefail
    log() { echo "[imds-hardening] $*"; }
    TOKEN=$(curl -sS -m 5 -X PUT "http://169.254.169.254/latest/api/token" \
      -H "X-aws-ec2-metadata-token-ttl-seconds: 300" 2>/dev/null) || { log "no IMDS token"; exit 0; }
    IMD() { curl -sS -m 5 -H "X-aws-ec2-metadata-token: $TOKEN" "http://169.254.169.254/latest/meta-data/$1" 2>/dev/null; }
    IID=$(IMD instance-id);          [ -n "$IID" ]    || { log "no instance-id"; exit 0; }
    REGION=$(IMD placement/region);  [ -n "$REGION" ] || { log "no region"; exit 0; }
    if aws ec2 modify-instance-metadata-options --region "$REGION" --instance-id "$IID" \
         --http-tokens required --http-endpoint enabled --http-put-response-hop-limit 1 >/dev/null 2>&1; then
      log "hop limit set to 1 on $IID"
    else
      log "WARNING: could not set hop limit (continuing)"
    fi
    exit 0
  EOT
}

resource "aws_s3_object" "imds_bootstrap" {
  bucket       = aws_s3_bucket.ci_bootstrap.id
  key          = local.imds_bootstrap_key
  content      = local.imds_bootstrap_script
  content_type = "text/x-shellscript"
  # Re-upload (and so re-version) whenever the script changes.
  etag = md5(local.imds_bootstrap_script)
}

# Grants each agent instance the ability to (a) fetch the boot script and
# (b) lower its OWN metadata hop limit. ModifyInstanceMetadataOptions is scoped
# to in-account instances; a container can't abuse it because — by the time the
# script has run — containers can no longer reach IMDS to obtain these creds.
resource "aws_iam_policy" "imds_hardening" {
  name        = "buildkite-imds-hardening"
  description = "Let agents fetch the IMDS-hardening boot script and lower their own metadata hop limit"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = "s3:GetObject"
        Resource = "${aws_s3_bucket.ci_bootstrap.arn}/*"
      },
      {
        Effect   = "Allow"
        Action   = "ec2:ModifyInstanceMetadataOptions"
        Resource = "arn:aws:ec2:*:${local.account_id}:instance/*"
      },
    ]
  })
}
