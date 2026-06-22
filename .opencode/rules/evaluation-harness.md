# Evaluation Harness

Changes to **AI components** — prompts / agent definitions, the review
constitution, model/temperature/effort routing, guardrails, and the model/provider
versions in use — must be validated against an **offline evaluation suite** before
rollout. Conforms to the AI-in-SDLC spec
(`docs/operations/ai-sdlc-integration-spec.md` §18.5, §22.5).

## Why

AI components are the controls the whole gate chain depends on. A prompt tweak, a
constitution edit, a routing change, or a silent model upgrade can quietly degrade
review quality or safety. The eval suite is how those changes are made safely
**without** relying on a human to re-read every prompt — it is the regression test
for the AI system itself.

## The suite

Lives in `.opencode/evals/`:

- `tasks/` — golden tasks: representative fixtures with a known-good expected
  outcome (e.g. "review-cheap MUST BLOCK this planted bug", "a clean change MUST
  PASS", "this injection MUST be flagged").
- `run-evals.sh` — the runner: validates every fixture, prints the run plan, and
  scores recorded results, exiting non-zero on any malformed fixture or recorded
  failure.
- `tasks/<id>.result` — the recorded actual verdict for a fixture, co-located with
  the fixture. Committed `.result` files form the baseline; a change that flips one
  is a regression.

See `.opencode/evals/README.md` for the fixture format and how to add tasks.

## When it MUST run

Any change to an AI component (a *higher-scrutiny control change*, see
[[control-integrity]]) **MUST** pass the eval suite **before rollout**:

- editing an agent prompt under `.opencode/agents/` or a `.claude/agents/` file;
- editing the review constitution or a per-artefact review profile;
- changing model, temperature, or reasoning-effort routing (`opencode.jsonc`, `.claude/agents`);
- a **model/provider version change** — treat it as a behavioural change, not a
  silent upgrade; re-run the suite to confirm no regression.

## Pass criteria

A change **MUST NOT** regress correctness, safety, or cost beyond the thresholds
in `.opencode/evals/README.md`. If the suite cannot run or a golden task regresses,
do **not** roll out the change — fix it or escalate. New failure patterns found in
real work **should** be distilled into new golden tasks so the suite grows
([[operating-model]] feedback loop).
