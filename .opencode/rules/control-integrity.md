# Control Integrity

The controls AI is judged by — tests, build/CI gates, the review constitution,
model/temperature/effort routing, guardrails, and the risk/authority policy — **must not
be weakened, disabled, or gamed** to make a gate pass. Conforms to the AI-in-SDLC
spec (`docs/operations/ai-sdlc-integration-spec.md` §12 V7, §19.5).

## No gaming the gates

Agents **MUST NOT** weaken, disable, delete, skip, or otherwise game a
verification control to obtain a PASS. Prohibited "passing by lowering the bar"
includes:

- deleting or skipping a failing test, or loosening its assertions, instead of fixing the code;
- updating golden/snapshot files or test fixtures to match incorrect output instead of fixing the underlying code;
- narrowing a test's scope, adding `@Ignore` / `assumeTrue(false)`, or shrinking coverage to dodge a gate;
- suppressing a lint / static-analysis or policy/security rule to silence a finding;
- relaxing the review constitution, lowering a confidence threshold, or routing to a weaker model/temperature/effort to get an easier PASS;
- dispositioning a MAJOR finding as "accepted" without the required approval (see [[review-constitution]] Iteration Protocol).

A gate satisfied by **reducing its strength is a failure, not a pass.**

## Higher-scrutiny change class

Changes **to the controls themselves** are a distinct, higher-scrutiny class:
they are classified as **at least gated-approval** — **never act-autonomously**
(see [[risk-authority-classification]]). This
covers changes to: tests and test infrastructure, CI/build gates, the review
constitution, model/temperature/effort routing, guardrails/rules, and the risk/authority
policy.

For any such change:

- It **MUST** be reviewed under a constitution that confirms **the control still
  detects the failures it exists to catch** — not merely that the change is "clean".
- **Separation of duties is mandatory**: the agent that **benefits from** the
  change (whose PASS depends on it) **MUST NOT** approve it — a distinct review
  agent or a human gates it; two colluding agents do not satisfy this.
- If the change is to an **AI component** (prompt, review constitution, routing
  policy, guardrail, model/provider version), it **MUST additionally pass the
  evaluation harness** before rollout (see [[evaluation-harness]]).

## When a control really is wrong

Fixing a genuinely incorrect control (a flaky or wrong test, an over-strict rule)
is legitimate, but **MUST** follow the higher-scrutiny path: classify the change
explicitly, state *why the old control was wrong* and *what failure the revised
control still catches*, obtain a **separate** review under [[review-constitution]],
and record the rationale. The line is: **fix controls in the open with extra
scrutiny; never weaken them to make your own change pass.**

## When gaming is detected

A reviewer who detects any of the gaming behaviours above **MUST** treat it as a
CRITICAL finding under [[review-constitution]], **block** reintegration, and
escalate per [[operating-model]] failure-handling. The gate chain does **not**
produce a PASS for a weakened gate.
