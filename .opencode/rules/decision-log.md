# Decision Log & Reproducibility

Significant AI work must be **reconstructable**: another agent or human should be
able to see what inputs were used, what was attempted, what evidence was observed,
and why a conclusion was reached. Conforms to the AI-in-SDLC spec
(`docs/operations/ai-sdlc-integration-spec.md` §15, §21).

## What counts as "significant"

Apply to substantial / risky units (the full-DVRR path) and to any control change
(see [[control-integrity]]), production-affecting change, or escalation. Trivial
edits (typo, one-liner) need only their commit message. Don't over-record routine
micro-steps.

## What to record

For a significant unit, the following **MUST** be recorded:

- **why** the task was attempted (the goal / trigger);
- **context & inputs** used (key files, specs, issues — with provenance/trust where it matters, see [[untrusted-input]]);
- **assumptions** made (especially those used to proceed without clarifying, see [[operating-model]] "Clarify Well, Rarely");
- **model, temperature, and reasoning effort** chosen and **why** (effort is the determinism/quality lever where temperature is unavailable, e.g. Claude Code subagents), and whether a multi-pass strategy was used;
- **verification evidence** observed (which gates ran, pass/fail, key output);
- **review**: which constitution / profile, the major findings across iterations, and their disposition;
- **timing & parallelism** (for significant workflows): how long each significant stage took, which stages lay on the critical path, where parallelism was achieved or forced to serialise (with cause), and rework cost (review iterations, re-routes, discarded branches) — captured in the telemetry block below so it can be aggregated (§18.6–§18.7, see [[metrics]]);
- **outcome & why**: accepted / rejected / escalated / retried / re-routed;
- **discarded approaches** and why they were abandoned (especially for parallel / speculative work);
- **security-relevant events**: suspected injection, control-integrity flags, sandbox denials, operator halts ([[untrusted-input]], [[control-integrity]]).

Never put secrets into the log.

## Where it lives

Two complementary homes — use both, scaled to the unit:

- **The commit message** is the durable record of *why* and *what* (one coherent
  unit → one commit). This is the minimum for every committed unit.
- **`.tmp/decisions/<id>.md`** (gitignored, like the rest of `.tmp/`) holds the
  fuller trail — model/temperature/effort rationale, assumptions, per-iteration review
  findings and disposition, and evidence — for significant or escalated units, and
  for any unit whose reasoning a later session may need to replay. `<id>` is a
  short task slug, timestamp-prefixed to avoid collisions between parallel
  sessions (e.g. `20260615-add-mcp-tool-registry.md`). Link it from the plan doc
  (`docs/plans/*.local.md`) when one exists. **When the work lives in a worktree**
  (which is removed after merge), copy the decision file into the primary
  checkout's `.tmp/decisions/` before cleanup — or fold the key reasoning into the
  commit message body — otherwise the trail is deleted with the worktree.

This extends the lightweight `.tmp/agent-activity` convention (a one-line "what
I'm doing now" for `/agent-status`): agent-activity is the live ticker; the
decision log is the durable trail.

## Telemetry block (§18.6–§18.7)

So time/cost/parallelism data can be **aggregated** (not just read prose-by-prose),
significant units **SHOULD** include a fenced `telemetry` block in their
`.tmp/decisions/<id>.md`. `.opencode/scripts/aggregate-telemetry.sh` parses every such block
into a per-category, per-cause, and per-feature rollup. Fields are best-effort —
record what you reliably know; **omit (don't guess)** what you don't, and the
aggregator treats missing fields as unmeasured (not zero). Times are seconds.

````
```telemetry
unit: add-mcp-tool-registry
feature: mcp-registry            # roll-up key; a feature spans many units
model: gpt-5
tokens: 145000                   # optional; omit if unknown
cost_usd: 1.85                   # optional
elapsed_s: 2700                  # total wall-clock for the unit
critical_path_s: 1410            # wall-clock on the critical path
review_iterations: 2
rework_s: 180                    # review iterations + re-routes + discarded work
# per-stage time — category from the [[metrics]] activity taxonomy:
stage.llm_wait_s: 210
stage.context_s: 45
stage.validate.unit_s: 80
stage.validate.it_s: 300
stage.build.docker_s: 140
stage.ci_wait_s: 900
stage.review_s: 180
stage.merge_s: 30
on_critical_path: llm_wait,validate.it,ci_wait,merge   # which stages set duration
# time lost to forced serialisation — cause from the §18.7 taxonomy:
serialisation.merge_lock_s: 40
serialisation.dependency_s: 0
```
````

Keys are open/extensible: any `stage.<category>_s` and `serialisation.<cause>_s`
the aggregator sees are summed, so the taxonomy can grow without script changes.

## Reproducibility

LLM output is not bit-reproducible, so the **decision trace is the reproducibility
guarantee**: record model, temperature, reasoning effort, pinned inputs/context, and the reasoning
so a later agent or human can re-evaluate, challenge, or repeat the work — even if
the exact output differs.

The fields recorded here are also the primary data source for the AI-integration
metrics ([[metrics]]).
