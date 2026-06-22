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
- **model & temperature** chosen and **why**, and whether a multi-pass strategy was used;
- **verification evidence** observed (which gates ran, pass/fail, key output);
- **review**: which constitution / profile, the major findings across iterations, and their disposition;
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

## Reproducibility

LLM output is not bit-reproducible, so the **decision trace is the reproducibility
guarantee**: record model, temperature, reasoning effort, pinned inputs/context, and the reasoning
so a later agent or human can re-evaluate, challenge, or repeat the work — even if
the exact output differs.

The fields recorded here are also the primary data source for the AI-integration
metrics ([[metrics]]).
