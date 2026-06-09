# ---------------------------------------------------------------------------
# S3 results store for the periodic performance-regression pipeline
# ---------------------------------------------------------------------------
# Holds one JSON per run at runs/<branch>/<iso>__<sha>.json. perf-test-run.sh /
# perf-test-microbench.sh produce a run result; perf-test-compare.sh persists it
# here and pulls the last N PRIOR runs to compute the rolling median+MAD baseline.
#
# Unlike the disposable dependency cache, these are HISTORY: versioning is
# enabled and current versions are retained indefinitely (the baseline needs a
# long horizon). Only noncurrent versions and incomplete uploads are expired.
# ---------------------------------------------------------------------------

resource "aws_s3_bucket" "perf_results" {
  bucket = "mockserver-ci-perf-results"

  tags = {
    Name    = "mockserver-ci-perf-results"
    Purpose = "CI performance-regression result history - rolling baseline"
  }
}

resource "aws_s3_bucket_versioning" "perf_results" {
  bucket = aws_s3_bucket.perf_results.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "perf_results" {
  bucket = aws_s3_bucket.perf_results.id

  rule {
    id     = "retain-history"
    status = "Enabled"

    # Keep the current version of every run indefinitely (baseline history).
    # Tidy superseded versions and abandoned uploads only.
    noncurrent_version_expiration {
      noncurrent_days = 365
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "perf_results" {
  bucket = aws_s3_bucket.perf_results.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "perf_results" {
  bucket = aws_s3_bucket.perf_results.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# ---------------------------------------------------------------------------
# IAM Policy: allow perf agents to read/write the results bucket
# ---------------------------------------------------------------------------

resource "aws_iam_policy" "perf_results" {
  name        = "buildkite-perf-results"
  description = "Allow Buildkite perf agents to read/write the performance-regression results bucket"

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
        aws_s3_bucket.perf_results.arn,
        "${aws_s3_bucket.perf_results.arn}/*",
      ]
    }]
  })
}
