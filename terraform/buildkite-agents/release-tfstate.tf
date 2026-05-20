# The versioned-site release step runs terraform against the terraform/website
# stack, whose state lives in this account's mockserver-terraform-state bucket
# under website/terraform.tfstate. Grant the release-queue agent access to that
# state object (and its S3-native lock) so `terraform init`/`plan`/`apply` can
# read and write it. Scoped to the website/ prefix so the agent cannot read
# other stacks' state (e.g. buildkite-agents/terraform.tfstate).
resource "aws_iam_policy" "release_website_tfstate" {
  name        = "buildkite-release-website-tfstate"
  description = "Allow the release Buildkite agent to read/write the terraform/website state in mockserver-terraform-state"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
        Resource = "arn:aws:s3:::mockserver-terraform-state/website/*"
      },
      {
        Effect   = "Allow"
        Action   = ["s3:ListBucket", "s3:GetBucketLocation"]
        Resource = "arn:aws:s3:::mockserver-terraform-state"
      },
    ]
  })
}
