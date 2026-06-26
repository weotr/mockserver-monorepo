# AI Integration Metrics

How the AI-in-SDLC integration is measured over time, so autonomy is expanded (or
pulled back) on evidence — and so we can see **where the time and cost of
delivering a feature/fix actually goes** and target optimisation there. Conforms
to the AI-in-SDLC spec (`docs/operations/ai-sdlc-integration-spec.md` §18,
including §18.6 activity-time and §18.7 parallelism telemetry).

## Discipline

- Capture a **baseline** at adoption where possible, so improvement can be shown.
- Collect on a defined **cadence**; each metric has a named **owner** who
  interprets it and acts.
- **Cadence:** review monthly, and after any material AI-component change
  ([[evaluation-harness]]).
- **Owner:** the AI-in-SDLC platform owner (see the spec's document-control
  header); the owner may delegate per-metric interpretation.

## Metrics

| Metric | Measure | Source | Target | Gate? |
|--------|---------|--------|--------|-------|
| Autonomy rate | % units completed without human intervention | decision logs (needs an "intervention" field) / commit attribution | trend up † | no |
| Verification pass rate | % units whose gate chain passed first time | gate-chain results | high † | no |
| Rework rate | % units needing a follow-up fix/revert within 14 days (by convention) | git history (same files) | trend down † | no |
| Defect-escape rate | incidents/bugs traced to an AI-made change | incident/issue records | trend down † | no |
| Review convergence | mean review iterations to PASS; % hitting the 8-cap with residual risk | decision logs (review section) | low mean; ~0% cap-hits † | no |
| Model-routing fitness | % tasks correctly routed by model class (judged retrospectively) | decision logs + eval | high † | no |
| Temperature-routing fitness | % tasks correctly routed by temperature (judged retrospectively) | decision logs + eval | high † | no |
| Concurrency | avg & peak concurrent subagents | orchestration records | **≤10** (hard cap) | yes |
| Cost per unit | inference cost per completed unit | cost/usage telemetry (to instrument) | stable or down † | no |
| Activity-time breakdown | total time per activity category & validation check-type (cost lens); wait vs work | decision-log telemetry → `.opencode/scripts/aggregate-telemetry.sh` | shrink the dominant sink † | no |
| Critical-path duration | wall-clock on the critical path per unit/feature (duration lens) | decision-log telemetry (`critical_path_s`) | trend down † | no |
| Rework cost | time/tokens in review iterations, re-routes, discarded branches | decision-log telemetry (`rework_s`, `review_iterations`) | trend down † | no |
| Parallelism utilisation | mean/peak achieved concurrency vs the effective limit | orchestration records / decision-log telemetry | high when work is available † | no |
| Serialisation causes | ranked reasons parallelism was lost below the cap | decision-log telemetry (`serialisation.*`) | dependency-bound only † | no |
| Trace completeness | % significant units with a sufficient decision trace | decision-log presence | high † | no |
| Consistency drift | config/convention drift incidents | `scripts/validate_opencode_config.sh` + lint | zero | yes |

This table focuses on the §18.3 MUST process metrics plus the most actionable
§18.2 outcomes; the full outcome inventory (cycle time, throughput, MTTD, quality
signal density, documentation quality) is in spec §18.2. **†** marks directional
placeholders — concrete numeric targets are an open decision (see Thresholds).

## Activity-time & cost instrumentation (§18.6)

Record time and cost per significant stage so optimisation targets the
**largest, most-improvable** sink rather than the most visible one. **Cost and
duration optimise differently — measure for both:**

- **Cost** (total inference spend + wasted effort): attack the categories with the
  largest **total time and tokens**, including rework that never lands first-pass.
- **Duration** (request → reintegrated, verified change — the §18.2 cycle-time
  outcome): attack the **critical path**. Shrinking a fat *off*-critical-path
  stage cuts cost but **not** delivery time. Even LLM latency is optimisable —
  largely by supplying better, denser context ([[operating-model]] Context).

**Activity taxonomy** (extensible — add a category when a new time sink appears):
`llm_wait`, `context` (assembly/retrieval), `validate.*` broken down **by check
type** (`unit`, `it`, `contract`, `static`, `lint`, `policy`), `build` (build &
packaging, incl. `build.docker`), `ci_wait`, `tool_exec`, `review`, `merge`,
`escalation_wait`, `queue_idle` (waiting under the §8 caps). Use the *same* keys
in the decision-log telemetry block ([[decision-log]]) so the aggregator does not
split one sink across two buckets (e.g. docker build is always `build.docker`,
never `validate.docker`). **Waiting** is recorded distinctly from
**active work**, and time **on the critical path** (extends duration) distinctly
from time that **overlaps** other units (does not).

**Rework cost** — review iterations (count + time to PASS), re-routes/fallbacks,
and discarded speculative branches — is recorded separately because it is often
the dominant cost/duration driver and is invisible in first-pass timing.

Everything rolls up to the **feature/fix** level (a feature spans many units) and
reconciles to cycle time. Agents fill a small machine-readable telemetry block in
the decision log ([[decision-log]]); `.opencode/scripts/aggregate-telemetry.sh` aggregates
the blocks per category, per cause, and per feature. Capture is scoped to
**significant** stages and may be sampled — any sampling is itself recorded so a
near-zero category is not mistaken for "fully measured".

## Parallelism utilisation (§18.7)

Wall-clock time tells you *how long*; these tell you *whether concurrency was the
reason it was slow*. The decisive signal is **when parallelism was not possible**
— each forced serialisation is a candidate for better decomposition or reduced
contention. Recorded:

- **Achieved parallelism** — mean/peak active subagents and concurrent units vs
  the **effective limit** in force (the §8.1 hard cap or a lower §8.2 dynamic
  limit), so **under-utilisation** (limit not the constraint) is distinguishable
  from **saturation** (limit is).
- **Serialisation cause** — whenever a unit runs serially or below the cap, *why*:
  `dependency`, `contention`, `dynamic_limit`, `cap_queue`, `merge_lock`,
  `not_parallelisable`, `escalation`. Aggregated to rank the **dominant reason
  parallelism is lost** (the merge lock is one such site — [[worktree-workflow]]).
- **Cap-bound vs decomposition-bound** — only cap-bound time argues for raising
  the [[operating-model]] caps; the rest argues for better decomposition or
  contention reduction.
- **Duration tax** — actual critical-path time vs the theoretical best (infinite
  parallelism, zero contention) — the primary quantitative target for duration.

## Data source

The **decision log** ([[decision-log]]) is the primary data source — its fields
(model, temperature, review findings/iterations, outcome, security events) are
exactly what most of these metrics aggregate. The evaluation harness
([[evaluation-harness]]) supplies routing-fitness evidence; the config validator
supplies drift.

### Derivable now vs needs light instrumentation

- **Now**, from git + decision logs + eval + validator + `/agent-status`: rework
  rate, review convergence, concurrency (observed against the cap), consistency
  drift, trace completeness.
- **Needs light instrumentation** (fill the decision-log telemetry block so it can
  be aggregated by `.opencode/scripts/aggregate-telemetry.sh`): autonomy rate, cost per
  unit, routing fitness, **activity-time breakdown, critical-path duration, rework
  cost, parallelism utilisation, and serialisation causes** (§18.6–§18.7).

## Thresholds

Concrete numeric targets (beyond the hard ≤10 concurrency cap and zero-drift) are
an open decision (spec §22.6). Until set, trend each metric against its baseline
and act on adverse trends — feeding findings into autonomy promotion/demotion
([[risk-authority-classification]]) and the [[operating-model]] feedback loop.

Cost/budget enforcement is likewise **convention-level today** (the OP5 budget /
OP11 liveness conventions in [[operating-model]]) pending a framework token/step
accounting API; it is tracked with these open thresholds (§22.6), not yet hard-enforced.
