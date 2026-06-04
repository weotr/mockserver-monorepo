# ---------------------------------------------------------------------------
# S3 Dependency Cache for CI Builds
# ---------------------------------------------------------------------------
# Provides a shared S3 bucket for caching Maven, npm, pip, and Bundler
# dependencies across ephemeral scale-to-zero build agents.  Without this,
# every fresh EC2 instance downloads all dependencies from the internet.
#
# Cache keys are derived from lockfiles (pom.xml, package-lock.json, etc.)
# so a cache entry is invalidated when dependencies change.
#
# Objects expire after 14 days via lifecycle policy to limit storage cost.
# ---------------------------------------------------------------------------

resource "aws_s3_bucket" "dependency_cache" {
  bucket = "mockserver-ci-dependency-cache"

  tags = {
    Name    = "mockserver-ci-dependency-cache"
    Purpose = "CI dependency cache for Maven/npm/pip/Bundler"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "dependency_cache" {
  bucket = aws_s3_bucket.dependency_cache.id

  rule {
    id     = "expire-stale-cache"
    status = "Enabled"

    expiration {
      days = 14
    }

    # Clean up incomplete multipart uploads after 1 day
    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }
}

resource "aws_s3_bucket_versioning" "dependency_cache" {
  bucket = aws_s3_bucket.dependency_cache.id

  versioning_configuration {
    # Cache objects are disposable -- no need for versioning
    status = "Suspended"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "dependency_cache" {
  bucket = aws_s3_bucket.dependency_cache.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "dependency_cache" {
  bucket = aws_s3_bucket.dependency_cache.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# ---------------------------------------------------------------------------
# IAM Policy: allow agents to read/write the cache bucket
# ---------------------------------------------------------------------------

resource "aws_iam_policy" "dependency_cache" {
  name        = "buildkite-dependency-cache"
  description = "Allow Buildkite agents to read/write the CI dependency cache bucket"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        "s3:GetObject",
        "s3:PutObject",
        "s3:ListBucket",
      ]
      Resource = [
        aws_s3_bucket.dependency_cache.arn,
        "${aws_s3_bucket.dependency_cache.arn}/*",
      ]
    }]
  })
}
