resource "aws_iam_role" "release_website" {
  name = "mockserver-release-website"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [merge(
      {
        Effect    = "Allow"
        Principal = { AWS = var.build_account_agent_role_arn }
        Action    = "sts:AssumeRole"
      },
      # Only emit the Condition key when an external id is supplied. Emitting
      # `Condition = null` produces `"Condition": null` in the policy JSON,
      # which the IAM API normalises away — causing perpetual plan drift. When
      # role_external_id is "" (the default) the trust policy stays as-is so the
      # current cross-account apply keeps working until the external id is wired.
      var.role_external_id != "" ? {
        Condition = { StringEquals = { "sts:ExternalId" = var.role_external_id } }
      } : {}
    )]
  })
}

resource "aws_iam_role_policy" "release_website" {
  name = "website-access"
  role = aws_iam_role.release_website.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        # Audit finding F-WEB-02: previously `s3:*` — tightened to the specific
        # verbs the release pipeline needs.
        #
        # Routine release (website.sh, javadoc.sh, schema.sh, helm.sh) uses
        # object-level reads/writes against the active site bucket.
        # Major / minor release (versioned-site.sh) creates a NEW versioned
        # bucket via Terraform and so additionally needs bucket-create verbs.
        Sid    = "WebsiteBuckets"
        Effect = "Allow"
        Action = [
          # Object-level (routine release)
          "s3:GetObject",
          "s3:PutObject",
          "s3:DeleteObject",
          "s3:ListBucket",
          "s3:GetBucketLocation",
          "s3:GetBucketVersioning",
          "s3:GetBucketTagging",
          "s3:GetBucketPolicy",
          "s3:GetBucketPublicAccessBlock",
          "s3:GetBucketOwnershipControls",
          # Bucket-level (versioned-site.sh creates new buckets)
          "s3:CreateBucket",
          "s3:PutBucketPolicy",
          "s3:PutPublicAccessBlock",
          "s3:PutBucketPublicAccessBlock",
          "s3:PutBucketOwnershipControls",
          "s3:PutBucketTagging",
        ]
        Resource = ["arn:aws:s3:::aws-website-mockserver-*", "arn:aws:s3:::aws-website-mockserver-*/*"]
      },
      {
        # Audit finding F-WEB-02: previously `cloudfront:*` — tightened to the
        # specific actions used by `scripts/release/components/website.sh`,
        # `versioned-site.sh`, `javadoc.sh`, and `schema.sh`. Removes account-
        # wide actions never used by the release pipeline (e.g. cloudfront
        # functions, key groups, public keys, realtime log configs).
        # Create/Delete are needed because versioned-site.sh provisions a new
        # distribution and OAI for every new major/minor release.
        Sid    = "CloudFront"
        Effect = "Allow"
        Action = [
          # Cache invalidation (routine release)
          "cloudfront:CreateInvalidation",
          "cloudfront:GetInvalidation",
          "cloudfront:ListInvalidations",
          # Distribution read + update (routine release)
          "cloudfront:GetDistribution",
          "cloudfront:GetDistributionConfig",
          "cloudfront:ListDistributions",
          "cloudfront:UpdateDistribution",
          # Distribution create/delete (versioned-site.sh)
          "cloudfront:CreateDistribution",
          "cloudfront:DeleteDistribution",
          # OAI create/read/delete (versioned-site.sh)
          "cloudfront:CreateCloudFrontOriginAccessIdentity",
          "cloudfront:GetCloudFrontOriginAccessIdentity",
          "cloudfront:GetCloudFrontOriginAccessIdentityConfig",
          "cloudfront:ListCloudFrontOriginAccessIdentities",
          "cloudfront:DeleteCloudFrontOriginAccessIdentity",
          "cloudfront:UpdateCloudFrontOriginAccessIdentity",
          # Tagging (routine + versioned-site)
          "cloudfront:TagResource",
          "cloudfront:UntagResource",
          "cloudfront:ListTagsForResource",
        ]
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
