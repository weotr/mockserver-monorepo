resource "aws_iam_role" "release_website" {
  name = "mockserver-release-website"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { AWS = var.build_account_agent_role_arn }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy" "release_website" {
  name = "website-access"
  role = aws_iam_role.release_website.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        # Full management of the versioned-site S3 buckets — terraform creates,
        # configures and content-syncs them. Scoped to the website bucket
        # name prefix so the role cannot touch unrelated buckets.
        Sid      = "WebsiteBuckets"
        Effect   = "Allow"
        Action   = "s3:*"
        Resource = ["arn:aws:s3:::aws-website-mockserver-*", "arn:aws:s3:::aws-website-mockserver-*/*"]
      },
      {
        # CloudFront has no resource-level permissions for distribution and
        # origin-access-identity create/list operations, so this is account-wide.
        Sid      = "CloudFront"
        Effect   = "Allow"
        Action   = "cloudfront:*"
        Resource = "*"
      },
      {
        # Route53 alias records for the version subdomains, scoped to the
        # mock-server.com hosted zone.
        Sid    = "Route53Zone"
        Effect = "Allow"
        Action = [
          "route53:ChangeResourceRecordSets",
          "route53:GetHostedZone",
          "route53:ListResourceRecordSets",
          "route53:ListTagsForResource",
        ]
        Resource = "arn:aws:route53:::hostedzone/${var.zone_id}"
      },
      {
        Sid      = "Route53Change"
        Effect   = "Allow"
        Action   = "route53:GetChange"
        Resource = "arn:aws:route53:::change/*"
      },
      {
        # terraform refreshes this role on every apply. Read-only — the role
        # definition itself is only ever changed by an admin apply, never by
        # the release pipeline, so there is no self-escalation path.
        Sid    = "SelfRoleRefresh"
        Effect = "Allow"
        Action = [
          "iam:GetRole",
          "iam:GetRolePolicy",
          "iam:ListRolePolicies",
          "iam:ListAttachedRolePolicies",
          "iam:ListInstanceProfilesForRole",
          "iam:ListRoleTags",
        ]
        Resource = aws_iam_role.release_website.arn
      },
    ]
  })
}
