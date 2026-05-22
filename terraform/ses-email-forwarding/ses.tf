# ──────────────────────────────────────────────────────────────────────────────
# SES Domain Identity + DKIM
# ──────────────────────────────────────────────────────────────────────────────

resource "aws_ses_domain_identity" "domain" {
  domain = var.domain
}

resource "aws_route53_record" "ses_verification" {
  zone_id = data.aws_route53_zone.domain.zone_id
  name    = "_amazonses.${var.domain}"
  type    = "TXT"
  ttl     = 600
  records = [aws_ses_domain_identity.domain.verification_token]
}

resource "aws_ses_domain_dkim" "domain" {
  domain = aws_ses_domain_identity.domain.domain
}

# SES Easy DKIM always returns exactly three CNAME tokens. `count = 3` keeps the
# instance count static (known at plan time) — a `for_each` over dkim_tokens fails
# to plan because the token values are only known after apply.
resource "aws_route53_record" "ses_dkim" {
  count = 3

  zone_id = data.aws_route53_zone.domain.zone_id
  name    = "${aws_ses_domain_dkim.domain.dkim_tokens[count.index]}._domainkey.${var.domain}"
  type    = "CNAME"
  ttl     = 600
  records = ["${aws_ses_domain_dkim.domain.dkim_tokens[count.index]}.dkim.amazonses.com"]
}

# ──────────────────────────────────────────────────────────────────────────────
# MX Record — route inbound email to SES
# ──────────────────────────────────────────────────────────────────────────────

resource "aws_route53_record" "mx" {
  zone_id = data.aws_route53_zone.domain.zone_id
  name    = var.domain
  type    = "MX"
  ttl     = 600
  records = ["10 inbound-smtp.${var.region}.amazonaws.com"]
}

# ──────────────────────────────────────────────────────────────────────────────
# DMARC Record
# ──────────────────────────────────────────────────────────────────────────────

resource "aws_route53_record" "dmarc" {
  zone_id = data.aws_route53_zone.domain.zone_id
  name    = "_dmarc.${var.domain}"
  type    = "TXT"
  ttl     = 600
  records = ["v=DMARC1; p=none;"]
}

# ──────────────────────────────────────────────────────────────────────────────
# Verify destination addresses (required while SES is in sandbox mode)
# ──────────────────────────────────────────────────────────────────────────────

resource "aws_ses_email_identity" "forward_to" {
  for_each = toset(var.forward_to)
  email    = each.value
}

# ──────────────────────────────────────────────────────────────────────────────
# SES Receipt Rule — catch-all: write to S3 then invoke Lambda
# ──────────────────────────────────────────────────────────────────────────────

resource "aws_ses_receipt_rule_set" "main" {
  rule_set_name = "${replace(var.domain, ".", "-")}-inbound"
}

resource "aws_ses_active_receipt_rule_set" "main" {
  rule_set_name = aws_ses_receipt_rule_set.main.rule_set_name
}

resource "aws_ses_receipt_rule" "forward" {
  name          = "${replace(var.domain, ".", "-")}-forward"
  rule_set_name = aws_ses_receipt_rule_set.main.rule_set_name
  recipients    = [var.domain]
  enabled       = true
  scan_enabled  = true

  s3_action {
    position          = 1
    bucket_name       = aws_s3_bucket.mail.id
    object_key_prefix = "incoming/"
  }

  lambda_action {
    position        = 2
    function_arn    = aws_lambda_function.forwarder.arn
    invocation_type = "Event"
  }

  depends_on = [
    aws_s3_bucket_policy.mail,
    aws_lambda_permission.ses,
  ]
}
