variable "buildkite_agent_token" {
  description = <<-EOT
    Buildkite agent registration token.

    NEVER write this value to terraform.tfvars. Supply it at apply time via
    an environment variable:

      export TF_VAR_buildkite_agent_token=$(aws ssm get-parameter \
        --name /buildkite/buildkite/agent-token \
        --with-decryption --query Parameter.Value --output text \
        --profile mockserver-build)

    The run.sh wrapper does this automatically.
  EOT
  type        = string
  sensitive   = true
}

variable "region" {
  description = "AWS region"
  type        = string
  default     = "eu-west-2"
}

variable "instance_types" {
  description = "EC2 instance types (comma-separated). First type preferred for on-demand."
  type        = string
  default     = "c5.2xlarge"
}

variable "min_size" {
  description = "Minimum number of agent instances (0 = scale to zero when idle)"
  type        = number
  default     = 0
}

variable "max_size" {
  description = "Maximum number of agent instances"
  type        = number
  default     = 10
}

variable "on_demand_percentage" {
  description = "Percentage of on-demand instances (0 = all spot, 100 = all on-demand)"
  type        = number
  default     = 0
}

variable "release_min_size" {
  description = "Minimum number of release agent instances (0 = scale to zero when idle)"
  type        = number
  default     = 0
}

variable "release_max_size" {
  description = "Maximum number of release agent instances (release queue)"
  type        = number
  default     = 2
}

variable "trigger_instance_types" {
  description = "EC2 instance types for trigger queue (cheap, low-CPU — only runs curl/sleep polling loops)"
  type        = string
  default     = "t3.small"
}

variable "trigger_min_size" {
  description = "Minimum number of trigger agent instances (0 = scale to zero when idle)"
  type        = number
  default     = 0
}

variable "trigger_max_size" {
  description = "Maximum number of trigger agent instances"
  type        = number
  default     = 4
}

variable "perf_instance_types" {
  description = "EC2 instance type for the perf queue — a SINGLE fixed-performance type (no comma list) for reproducible benchmark numbers"
  type        = string
  default     = "c5.4xlarge"
}

variable "perf_min_size" {
  description = "Minimum perf agent instances. MUST be 0 (scale to zero — zero idle cost; AGENTS.md hard constraint)"
  type        = number
  default     = 0
}

variable "perf_max_size" {
  description = "Maximum perf agent instances (1 — never run two perf jobs concurrently so they don't contend)"
  type        = number
  default     = 1
}

variable "alert_email" {
  description = "Email address for infrastructure alerts (SNS notifications)"
  type        = string
  default     = ""
}
