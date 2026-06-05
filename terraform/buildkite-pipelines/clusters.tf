# Buildkite cluster migration
# -----------------------------------------------------------------------------
# Buildkite is deprecating unclustered (organization-level) agents, so the org's
# UI no longer exposes org-level agent tokens. All MockServer pipelines and
# agents are migrated onto the pre-existing "Default cluster":
#
#   - every pipeline is assigned to the cluster (cluster_id in pipelines.tf)
#   - the four agent queues are created as cluster queues
#   - a single cluster agent token is minted and published to SSM, where the
#     buildkite-agents stack's run.sh reads it (TF_VAR_buildkite_agent_token).
#     This also removes the inline token from buildkite-agents/terraform.tfvars
#     (audit finding: agent token must not live on local disk).
#
# Apply ORDER: this stack first (creates queues + token + SSM param + assigns
# pipelines), then terraform/buildkite-agents (picks up the new token from SSM
# and rolls the launch templates). Agents are scale-to-zero, so the cutover has
# no in-flight builds to disrupt.

data "buildkite_cluster" "default" {
  name = "Default cluster"
}

# Agent queues. These KEYS must match `buildkite_queue` in
# terraform/buildkite-agents/main.tf (the elastic-ci-stack modules tag their
# agents with these queue keys). The cluster's auto-created "default-queue" is
# left as-is; our agents use the "default" key below.
locals {
  agent_queues = ["default", "trigger", "release", "perf"]
}

resource "buildkite_cluster_queue" "agents" {
  for_each    = toset(local.agent_queues)
  cluster_id  = data.buildkite_cluster.default.id
  key         = each.value
  description = "MockServer ${each.value} agents (terraform/buildkite-agents)"
}

# Cluster-scoped agent registration token. Replaces the legacy org-level token.
# The value is sensitive and surfaced only via SSM below.
resource "buildkite_cluster_agent_token" "agents" {
  cluster_id  = data.buildkite_cluster.default.id
  description = "mockserver elastic-ci-stack agents (managed by terraform)"
}

# Publish the token to SSM so the buildkite-agents stack can read it at apply
# time (its run.sh exports TF_VAR_buildkite_agent_token from this parameter).
# SecureString — encrypted at rest; the value never appears in tfvars.
resource "aws_ssm_parameter" "agent_token" {
  name        = "/buildkite/buildkite/agent-token"
  description = "Buildkite cluster agent token for the mockserver elastic-ci-stack agents"
  type        = "SecureString"
  value       = buildkite_cluster_agent_token.agents.token
}
