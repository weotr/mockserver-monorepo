output "ses_domain_identity_arn" {
  description = "ARN of the SES domain identity"
  value       = aws_ses_domain_identity.domain.arn
}

output "ses_verification_token" {
  description = "SES domain verification token"
  value       = aws_ses_domain_identity.domain.verification_token
}

output "ses_dkim_tokens" {
  description = "DKIM tokens for DNS verification"
  value       = aws_ses_domain_dkim.domain.dkim_tokens
}

output "mx_value" {
  description = "MX record value configured for inbound email"
  value       = "10 inbound-smtp.${var.region}.amazonaws.com"
}

output "mail_bucket_name" {
  description = "S3 bucket storing raw inbound emails"
  value       = aws_s3_bucket.mail.id
}

output "lambda_function_name" {
  description = "Name of the email forwarder Lambda function"
  value       = aws_lambda_function.forwarder.function_name
}

output "alarm_sns_topic_arn" {
  description = "ARN of the SNS topic for forwarder error alarms"
  value       = var.enable_monitoring ? aws_sns_topic.forwarder_alarms[0].arn : null
}

output "alarm_name" {
  description = "Name of the CloudWatch alarm for forwarder Lambda errors"
  value       = var.enable_monitoring ? aws_cloudwatch_metric_alarm.forwarder_errors[0].alarm_name : null
}

output "dns_records_created" {
  description = "Summary of DNS records managed by this stack"
  value = {
    ses_verification = "${aws_route53_record.ses_verification.name} TXT"
    dkim_cnames      = [for r in aws_route53_record.ses_dkim : "${r.name} CNAME"]
    mx               = "${aws_route53_record.mx.name} MX"
    dmarc            = "${aws_route53_record.dmarc.name} TXT"
  }
}
