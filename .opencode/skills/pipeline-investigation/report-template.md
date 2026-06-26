# Pipeline Investigation Report Template

Use this template to convert the `pipeline-investigation/v1` JSON returned by
the skill subagent into a formatted markdown report.

---

## Markdown Report

Lead with the bottom line (Pyramid Principle — see
`.opencode/rules/documentation-style.md`): the TL;DR below states the outcome
before any metadata or evidence, so a reader who stops after the first screen
already knows the root cause, fix status, and next action.

```markdown
# Pipeline Investigation: {build.pipeline}

> **TL;DR** — {root_cause.summary} Fix status: **{fix_status}**.
> Action: {recommended_fix || "None — fix already applied"}.

**Build Number**: {build.number}
**Branch**: {build.branch}
**Commit**: {build.commit}
**State**: {build.state}
**Failed Job**: {build.failed_job}
**Time**: {build.started_at} - {build.finished_at}

---

## Root Cause

{root_cause.summary}

### Detail

{root_cause.detail}

### Error Excerpt

```
{root_cause.error_excerpt}
```

---

## Fix Status: {fix_status}

### Variant: ALREADY FIXED

Commit `{fix_commit}` on master: "{fix_message}"
The next build should pass.

### Variant: OPEN

No fix found. See Recommended Fix below.

---

## GitHub Actions

<!-- Omit section if github_actions is empty. -->

| Run ID | Workflow | Conclusion | Root Cause |
|--------|----------|------------|------------|
| {run_id} | {workflow} | {conclusion} | {root_cause} |

---

## Recommended Fix

<!-- Omit if fix_status is not OPEN. -->

{recommended_fix}

---

## How this was determined (replay)

<!-- Render commands_run as a fenced block so the reader can replay the exact
     evidence-gathering (D5/R6). Omit only if commands_run is empty. -->

```bash
{commands_run — one command per line}
```

<!-- If root_cause.alternative_hypotheses is non-empty, list the competing
     explanations and what ruled each out: -->

**Alternative hypotheses considered**

| Hypothesis | Ruled out by |
|------------|--------------|
| {hypothesis} | {ruled_out_by} |

---

## Summary

- **Root cause**: {root_cause.summary}
- **Reproduced**: {reproduced ? "yes (deterministic)" : "no (FLAKY / intermittent)"}
- **Fix status**: {fix_status}
- **Action**: {recommended_fix || "None - fix already applied"}
```

---

## File Naming Convention

Save markdown reports to:
```
docs/investigation/pipelines/{YYYY-MM-DD}-build-{build_number}.md
```

---

## Severity-Based Detail

- **fix_status = OPEN**: Full detail on all sections including recommended fix.
- **fix_status = ALREADY_FIXED**: Abbreviated report. Root cause + fix commit. Omit recommended fix.
