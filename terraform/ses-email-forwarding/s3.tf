# ──────────────────────────────────────────────────────────────────────────────
# S3 Bucket — stores raw inbound emails written by SES
# ──────────────────────────────────────────────────────────────────────────────

resource "aws_s3_bucket" "mail" {
  bucket = "${replace(var.domain, ".", "-")}-mail-${data.aws_caller_identity.current.account_id}"

  tags = {
    Name = "${var.domain} inbound mail"
  }
}

resource "aws_s3_bucket_public_access_block" "mail" {
  bucket                  = aws_s3_bucket.mail.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "mail" {
  bucket = aws_s3_bucket.mail.id

  rule {
    apply_server_side_encryption_by_default {
      # AES256 (SSE-S3) is a deliberate choice: AWS-managed keys with no extra
      # cost, sufficient for this low-volume personal email forwarder.
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "mail" {
  bucket = aws_s3_bucket.mail.id

  rule {
    id     = "expire-old-emails"
    status = "Enabled"

    # Empty filter applies the rule to all objects in the bucket (required by
    # the AWS provider to avoid warnings/errors on plan/apply).
    filter {}

    expiration {
      days = var.email_retention_days
    }
  }
}

# ──────────────────────────────────────────────────────────────────────────────
# Bucket Policy — allow SES to write inbound emails
# ──────────────────────────────────────────────────────────────────────────────

resource "aws_s3_bucket_policy" "mail" {
  bucket = aws_s3_bucket.mail.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "AllowSESPuts"
        Effect    = "Allow"
        Principal = { Service = "ses.amazonaws.com" }
        Action    = "s3:PutObject"
        Resource  = "${aws_s3_bucket.mail.arn}/*"
        Condition = {
          StringEquals = {
            "aws:SourceAccount" = data.aws_caller_identity.current.account_id
          }
        }
      },
      {
        # Audit finding F-WEB-29 (caught by 2026-05-27 re-audit): deny any
        # non-TLS access to the mail bucket. SES delivers over HTTPS and the
        # Lambda forwarder uses the AWS SDK (HTTPS); this is defence-in-depth.
        Sid       = "DenyInsecureTransport"
        Effect    = "Deny"
        Principal = "*"
        Action    = "s3:*"
        Resource  = [aws_s3_bucket.mail.arn, "${aws_s3_bucket.mail.arn}/*"]
        Condition = {
          Bool = { "aws:SecureTransport" = "false" }
        }
      }
    ]
  })
}
