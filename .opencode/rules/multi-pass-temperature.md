# Multi-Pass Temperature Pipeline

For work that benefits from controlled exploration before convergence — ideation,
specs, design options, naming, genuinely hard problems — use a **descending-
temperature** pipeline rather than a single pass. Conforms to the AI-in-SDLC spec
(`docs/operations/ai-sdlc-integration-spec.md` §9.4).

## The pipeline: explore → refine → validate

1. **Explore** at **higher** temperature — generate diverse options / approaches.
2. **Refine** at **low** temperature — synthesise, pick, and tighten the best.
3. **Validate** at **very low** temperature — final correctness / consistency check.

Rules:

- A high-temperature stage **MUST NOT** emit the final output directly —
  convergence **MUST** pass through a low / very-low refine-and-validate stage
  before completion. This is a structured creativity-to-convergence pipeline, not
  ad-hoc randomness.
- Use it **where exploration adds value** (ideation, spec/design, ambiguous
  problems). **Skip it** for deterministic/rote work — run a single low-temperature
  pass.
- On this project the per-agent temperatures already lay this out (see
  `docs/operations/opencode-configuration.md`): `council-seat` (0.7) is an explore
  stage; `taskify-agent` / `docs-writer` (0.3–0.4) refine; review/verify agents
  (0.1) validate. On the Claude harness, where per-subagent sampling temperature
  is unavailable, **reasoning effort plays the analogous determinism/quality role**
  (low for rote stages, high for hard reasoning and validation) — it is set
  explicitly on every `.claude/agents/` definition. A single agent can also
  self-stage by running explore → refine → validate passes in sequence.
- Whether a multi-pass strategy was used **MUST** be recorded in the decision log
  ([[decision-log]]).
