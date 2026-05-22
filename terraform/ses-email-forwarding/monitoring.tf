locals {
  # Fall back to the first forwarding address when alarm_email is not set.
  alarm_email = coalesce(var.alarm_email, var.forward_to[0])
}

# ──────────────────────────────────────────────────────────────────────────────
# SNS Topic — alarm notifications for the email forwarder
# ──────────────────────────────────────────────────────────────────────────────

resource "aws_sns_topic" "forwarder_alarms" {
  count = var.enable_monitoring ? 1 : 0

  name         = "${replace(var.domain, ".", "-")}-email-forwarder-alarms"
  display_name = "${var.domain} Email Forwarder Alarms"
}

resource "aws_sns_topic_policy" "forwarder_alarms" {
  count = var.enable_monitoring ? 1 : 0

  arn = aws_sns_topic.forwarder_alarms[0].arn

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "AllowCloudWatchAlarms"
        Effect = "Allow"
        Principal = {
          Service = "cloudwatch.amazonaws.com"
        }
        Action   = "SNS:Publish"
        Resource = aws_sns_topic.forwarder_alarms[0].arn
        Condition = {
          StringEquals = {
            "aws:SourceAccount" = data.aws_caller_identity.current.account_id
          }
        }
      }
    ]
  })
}

# The SNS email subscription must be confirmed once via the email that AWS
# sends to the alarm_email address after the first apply.
resource "aws_sns_topic_subscription" "forwarder_alarms_email" {
  count = var.enable_monitoring ? 1 : 0

  topic_arn = aws_sns_topic.forwarder_alarms[0].arn
  protocol  = "email"
  endpoint  = local.alarm_email
}

# ──────────────────────────────────────────────────────────────────────────────
# CloudWatch Alarm — Lambda forwarder errors
# ──────────────────────────────────────────────────────────────────────────────

resource "aws_cloudwatch_metric_alarm" "forwarder_errors" {
  count = var.enable_monitoring ? 1 : 0

  alarm_name          = "${replace(var.domain, ".", "-")}-email-forwarder-errors"
  alarm_description   = "Email forwarder Lambda is experiencing errors -- check CloudWatch Logs for details"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = 1
  threshold           = 1
  treat_missing_data  = "notBreaching"

  metric_name = "Errors"
  namespace   = "AWS/Lambda"
  period      = 300
  statistic   = "Sum"

  dimensions = {
    FunctionName = aws_lambda_function.forwarder.function_name
  }

  alarm_actions = [aws_sns_topic.forwarder_alarms[0].arn]
  ok_actions    = [aws_sns_topic.forwarder_alarms[0].arn]
}
