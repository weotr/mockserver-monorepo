# Security hardening for the mockserver-build account.
#
# Adds account-wide detection controls and EBS default encryption.
# Audit findings addressed: F-BLD-02, F-BLD-04, F-BLD-06, F-BLD-07
# (GuardDuty, Access Analyzer, EBS default encryption, VPC flow logs).
#
# Run with `terraform apply` from this directory after review.

# --- F-BLD-02 — Enable GuardDuty ----------------------------------------------
# The `datasources` block on aws_guardduty_detector is deprecated in
# hashicorp/aws v6+ — use aws_guardduty_detector_feature resources instead.
resource "aws_guardduty_detector" "this" {
  enable                       = true
  finding_publishing_frequency = "FIFTEEN_MINUTES"
}

resource "aws_guardduty_detector_feature" "s3_data_events" {
  detector_id = aws_guardduty_detector.this.id
  name        = "S3_DATA_EVENTS"
  status      = "ENABLED"
}

# Other features (RUNTIME_MONITORING, EKS_AUDIT_LOGS, EBS_MALWARE_PROTECTION,
# LAMBDA_NETWORK_LOGS) left at their account defaults. Add explicit
# aws_guardduty_detector_feature resources here to flip individual ones.

# --- F-BLD-04 — Enable Access Analyzer ---------------------------------------
resource "aws_accessanalyzer_analyzer" "account" {
  analyzer_name = "mockserver-build-account-analyzer"
  type          = "ACCOUNT"
}

# --- F-BLD-06 — Enable EBS encryption by default -----------------------------
resource "aws_ebs_encryption_by_default" "this" {
  enabled = true
}

# --- F-BLD-07 — VPC flow logs to CloudWatch Logs -----------------------------
# The Buildkite stack module creates a VPC per ASG. Iterate over module outputs.
locals {
  agent_vpc_ids = compact([
    try(module.buildkite_stack.vpc_id, null),
    try(module.buildkite_trigger_stack.vpc_id, null),
    try(module.buildkite_release_stack.vpc_id, null),
    try(module.buildkite_perf_stack.vpc_id, null),
  ])
}

resource "aws_cloudwatch_log_group" "vpc_flow_logs" {
  for_each          = toset(local.agent_vpc_ids)
  name              = "/aws/vpc/flow-logs/${each.value}"
  retention_in_days = 30
}

resource "aws_iam_role" "vpc_flow_log" {
  count = length(local.agent_vpc_ids) > 0 ? 1 : 0
  name  = "buildkite-mockserver-vpc-flow-log"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "vpc-flow-logs.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy" "vpc_flow_log" {
  count = length(local.agent_vpc_ids) > 0 ? 1 : 0
  name  = "vpc-flow-log-publish"
  role  = aws_iam_role.vpc_flow_log[0].id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        "logs:CreateLogGroup",
        "logs:CreateLogStream",
        "logs:PutLogEvents",
        "logs:DescribeLogGroups",
        "logs:DescribeLogStreams",
      ]
      Resource = "arn:aws:logs:${var.region}:${data.aws_caller_identity.current.account_id}:log-group:/aws/vpc/flow-logs/*"
    }]
  })
}

resource "aws_flow_log" "vpc" {
  for_each = toset(local.agent_vpc_ids)

  log_destination_type = "cloud-watch-logs"
  log_destination      = aws_cloudwatch_log_group.vpc_flow_logs[each.value].arn
  iam_role_arn         = aws_iam_role.vpc_flow_log[0].arn
  traffic_type         = "ALL"
  vpc_id               = each.value
}
