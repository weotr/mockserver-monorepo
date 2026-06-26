# AWS Investigation Report Template

Use this template to convert the `aws-investigation/v1` JSON returned by
the skill subagent into a formatted markdown report.

---

## Markdown Report

Lead with the bottom line (Pyramid Principle — see
`.opencode/rules/documentation-style.md`): the TL;DR below states the outcome
before any metadata or evidence, so a reader who stops after the first screen
already knows the root cause, current capacity, and next action.

```markdown
# AWS Infrastructure Investigation

> **TL;DR** — {root_cause.summary} ASG: {asg.desired_capacity}/{asg.max_size} instances.
> Action: {recommended_fix}.

**Timestamp**: {timestamp}
**Active Stack**: {active_stack}

---

## AutoScaling Group: {asg.name}

| Property | Value |
|----------|-------|
| Region | {asg.region} |
| Desired Capacity | {asg.desired_capacity} |
| Min Size | {asg.min_size} |
| Max Size | {asg.max_size} |
| Suspended Processes | {asg.suspended_processes — join with ", " or "None"} |

### Instances

| Instance ID | State | Health | AZ |
|-------------|-------|--------|----|
| {instance_id} | {state} | {health} | {availability_zone} |

<!-- Omit table if instances array is empty. Show "No instances running" instead. -->

---

## Autoscaling Lambda

| Property | Value |
|----------|-------|
| Function | {lambda.function_name} |
| State | {lambda.state} |
| Runtime | {lambda.runtime} |
| Last Invocation | {lambda.last_invocation} |

### Recent Errors

<!-- Omit section if recent_errors is empty. Otherwise render each error as a separate bullet: -->
<!-- - {error1} -->
<!-- - {error2} -->

---

## Root Cause

{root_cause.summary}

**Category**: {root_cause.category}

### Detail

{root_cause.detail}

### Evidence

```
{root_cause.evidence}
```

---

## Recommended Fix

{recommended_fix}

---

## How this was determined (replay)

<!-- Render commands_run as a fenced block so the reader can replay the exact
     evidence-gathering (D5/R6). Omit only if commands_run is empty. -->

```bash
{commands_run — one command per line}
```

<!-- Show the baseline comparison if present (D8). Omit if baseline_comparison is null. -->

**Baseline comparison**: {baseline_comparison}

<!-- If root_cause.alternative_hypotheses is non-empty, list the competing
     explanations and what ruled each out: -->

**Alternative hypotheses considered**

| Hypothesis | Ruled out by |
|------------|--------------|
| {hypothesis} | {ruled_out_by} |

---

## Warnings

<!-- Omit section if warnings is empty. Otherwise render each warning as a separate bullet: -->
<!-- - {warning1} -->
<!-- - {warning2} -->

---

## Summary

- **Active stack**: {active_stack}
- **Root cause**: {root_cause.summary}
- **ASG status**: {asg.desired_capacity}/{asg.max_size} instances
- **Action**: {recommended_fix}
```

---

## File Naming Convention

Save markdown reports to:
```
.tmp/aws-investigation-{YYYY-MM-DD}.md
```

---

## Rendering Rules

- **Arrays** (`warnings`, `recent_errors`, `suspended_processes`, `instances`, `commands_run`, `root_cause.alternative_hypotheses`): iterate and render each element as a separate line/row. Never render raw JSON arrays.
- **Empty arrays**: omit the section entirely and show nothing, or show "None" for inline fields.
- **Null values**: show "N/A".

## Severity-Based Detail

- **No instances running**: Full detail on all sections including Lambda logs and recommended fix.
- **Instances running but unhealthy**: Focus on instance status and console output.
- **Healthy and operational**: Abbreviated report. ASG status + any warnings.
