# Subagent Routing

Routing policy must live in routing configuration, not in skill descriptions.

## Delegate Almost Everything (The Orchestrator Does Little Directly)

The main agent's primary job is to **orchestrate subagents**. It should delegate
the **overwhelming majority of execution — implementation *and* investigation —
to subagents**, keeping its own context for planning, decomposition, routing,
review, and escalation (see [[operating-model]], spec §5.5/§7).

The reason is not only context preservation. **A subagent is where the correct
model, temperature, and reasoning effort are selected for the task** — and that
per-task selection is the primary lever for managing **inference cost** and
**output determinism**. Investigation counts: a read-only "why is X happening"
question routed to a right-sized subagent (e.g. `debugger`,
`pipeline-investigator`) keeps a hard problem on a strong model at high effort
while a mechanical lookup runs cheap, instead of paying the orchestrator's
configuration for everything.

Do work **inline only for the trivial residue** where spinning a subagent would
add no value (a one-line edit, a single-file read to answer a quick question).
When you do delegate, pick the subagent whose configured model/temperature/effort
fits the task's risk and reasoning depth — see the routing table in `AGENTS.md`,
the conversational table below, and the per-agent configuration in
`opencode.jsonc` / `.claude/agents/`.

## Core Rule

- `SKILL.md` files describe what a skill does and how it executes.
- Routing decisions (which agent runs a request) are defined by:
  - command files in `.opencode/commands/` (`agent:` + `subtask: true`),
  - this routing table for conversational requests,
  - permission policy in `opencode.jsonc`.

Do not add caller-only routing directives like:

`MUST be launched as a Task subagent with subagent_type "<type>"`

inside skill descriptions. These directives can cause subagents to attempt self-dispatch.

## Conversational Routing Table

When users ask conversationally (not via slash commands), route as follows:

| Skill | Subagent Type | Typical Requests |
|-------|---------------|------------------|
| `pipeline-investigation` | `pipeline-investigator` | "investigate this build", "why is CI failing" |
| `aws-investigation` | `debugger` | "check build agents", "debug ASG scaling" |

## Concurrency Cap

When fanning out conversational or command-routed work to subagents, respect the
hard caps in [[operating-model]] (Parallelism Limits): **≤10 active subagents and
≤10-way parallelism at any one time**, with a lower effective limit when
warranted (e.g. complexity, cost, contention, model availability — see
[[operating-model]] for the full list). Queue or defer excess work rather than
exceed a cap.

## Implementation Notes

- Slash commands remain authoritative for deterministic routing.
- Conversational routing should use this table when skill descriptions are hidden by permissions.
- If a new subagent-routed skill is added, update:
  1. `.opencode/commands/<skill>.md`
  2. this file's conversational routing table
  3. `scripts/validate_opencode_config.sh` validation assumptions
