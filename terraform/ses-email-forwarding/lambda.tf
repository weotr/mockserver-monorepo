# ──────────────────────────────────────────────────────────────────────────────
# Lambda Function — SES email forwarder
# ──────────────────────────────────────────────────────────────────────────────

data "archive_file" "forwarder" {
  type        = "zip"
  source_dir  = "${path.module}/lambda"
  output_path = "${path.module}/.terraform/tmp/forwarder.zip"
}

resource "aws_lambda_function" "forwarder" {
  function_name    = "${replace(var.domain, ".", "-")}-email-forwarder"
  filename         = data.archive_file.forwarder.output_path
  source_code_hash = data.archive_file.forwarder.output_base64sha256
  handler          = "index.handler"
  runtime          = "nodejs20.x"
  timeout          = 30
  memory_size      = 256
  role             = aws_iam_role.forwarder.arn

  environment {
    variables = {
      MAIL_BUCKET     = aws_s3_bucket.mail.id
      MAIL_KEY_PREFIX = "incoming/"
      FROM_ADDRESS    = var.from_address
      FORWARD_TO      = join(",", var.forward_to)
    }
  }

  depends_on = [aws_cloudwatch_log_group.forwarder]
}

# ──────────────────────────────────────────────────────────────────────────────
# IAM Role + Policy for the Lambda function
# ──────────────────────────────────────────────────────────────────────────────

resource "aws_iam_role" "forwarder" {
  name = "${replace(var.domain, ".", "-")}-email-forwarder"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect    = "Allow"
        Principal = { Service = "lambda.amazonaws.com" }
        Action    = "sts:AssumeRole"
      }
    ]
  })
}

resource "aws_iam_role_policy" "forwarder" {
  name = "email-forwarder"
  role = aws_iam_role.forwarder.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "ReadMailFromS3"
        Effect   = "Allow"
        Action   = "s3:GetObject"
        Resource = "${aws_s3_bucket.mail.arn}/*"
      },
      {
        Sid      = "SendForwardedEmail"
        Effect   = "Allow"
        Action   = "ses:SendRawEmail"
        Resource = "*"
      },
      {
        Sid    = "CloudWatchLogs"
        Effect = "Allow"
        Action = [
          "logs:CreateLogGroup",
          "logs:CreateLogStream",
          "logs:PutLogEvents",
        ]
        Resource = "arn:aws:logs:*:${data.aws_caller_identity.current.account_id}:*"
      }
    ]
  })
}

# ──────────────────────────────────────────────────────────────────────────────
# Lambda Permission — allow SES to invoke the function
# ──────────────────────────────────────────────────────────────────────────────

resource "aws_lambda_permission" "ses" {
  statement_id   = "AllowSESInvoke"
  action         = "lambda:InvokeFunction"
  function_name  = aws_lambda_function.forwarder.function_name
  principal      = "ses.amazonaws.com"
  source_account = data.aws_caller_identity.current.account_id
}

# ──────────────────────────────────────────────────────────────────────────────
# CloudWatch Log Group
# ──────────────────────────────────────────────────────────────────────────────

resource "aws_cloudwatch_log_group" "forwarder" {
  name              = "/aws/lambda/${replace(var.domain, ".", "-")}-email-forwarder"
  retention_in_days = 30
}
