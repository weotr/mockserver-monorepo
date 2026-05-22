variable "region" {
  description = "AWS region (must support SES inbound)"
  type        = string
  default     = "us-east-1"
}

variable "domain" {
  description = "Domain to receive email for (catch-all)"
  type        = string
  default     = "mock-server.com"
}

variable "forward_to" {
  description = "List of email addresses to forward inbound mail to"
  type        = list(string)
  default     = ["jamesdbloom@gmail.com"]

  validation {
    condition     = length(var.forward_to) > 0
    error_message = "forward_to must contain at least one email address."
  }
}

variable "from_address" {
  description = "Verified sender address used in the rewritten From header"
  type        = string
  default     = "noreply@mock-server.com"

  validation {
    condition     = can(regex("^.+@.+\\..+$", var.from_address))
    error_message = "from_address must be a valid email address."
  }
}

variable "email_retention_days" {
  description = "Number of days to retain raw emails in S3 before automatic deletion"
  type        = number
  default     = 30
}

variable "alarm_email" {
  description = "Email address for Lambda error alarm notifications (SNS subscription must be confirmed once via the email AWS sends after the first apply)"
  type        = string
  default     = ""
}

variable "enable_monitoring" {
  description = "Whether to create the SNS alarm topic, email subscription, and CloudWatch error alarm. Set to false in environments where the SNS management API is unreachable (e.g. behind a corporate TLS-inspection proxy). Monitoring can be applied later from a network where SNS is reachable."
  type        = bool
  default     = true
}
