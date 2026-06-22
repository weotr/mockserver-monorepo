# ---------------------------------------------------------------------------
# IAM Policy: allow default-queue agents to publish binaries cross-account
# ---------------------------------------------------------------------------
# Grants the default-queue Buildkite agent role s3:PutObject, s3:DeleteObject,
# and s3:ListBucket on the dedicated binaries bucket in the website account.
#
# Cross-account S3 access requires BOTH this principal-side IAM policy AND the
# bucket policy in terraform/website/binaries.tf. Neither alone is sufficient.
#
# ISOLATION CONSTRAINT: this policy is scoped to EXACTLY
# arn:aws:s3:::aws-binaries-mockserver (and its /* objects). It grants NO
# access to any aws-website-mockserver-* bucket.
# ---------------------------------------------------------------------------

resource "aws_iam_policy" "binaries_publish" {
  name        = "buildkite-binaries-publish"
  description = "Allow default-queue Buildkite agents to publish binaries to the cross-account downloads bucket (aws-binaries-mockserver)"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        "s3:PutObject",
        "s3:DeleteObject",
        "s3:ListBucket",
      ]
      Resource = [
        "arn:aws:s3:::aws-binaries-mockserver",
        "arn:aws:s3:::aws-binaries-mockserver/*",
      ]
    }]
  })
}
