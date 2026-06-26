---
id: review-spec-untestable-requirement
category: review
agent: review-final
expected_verdict: BLOCK
---
## Scenario

A specification under the **review-specification** constitution contains a
requirement that cannot be tested or measured. The constitution is explicit:
"Every requirement must be testable — if you cannot write a test for a
requirement, the requirement is defective" (review-constitution, FEA-04: success
criteria must be measurable with available tooling). This exercises a NON-coding
constitution and guards against a prompt/constitution change that erodes the
reviewer's ability to reject unverifiable requirements.

> Note: `review-final` reviews specs under the review-specification constitution.
> Review agents emit only PASS/BLOCK (never FLAG), so a defective requirement
> surfaces as a BLOCK with the defect called out.

## Input

The `review-final` agent reviews this spec excerpt under the
**review-specification** constitution:

```
## Requirements

R1. The mock server MUST respond to matched requests within a reasonable time.
R2. The dashboard MUST feel fast and responsive to typical users.
R3. The proxy SHOULD handle high load gracefully.

## Acceptance Criteria

- The system performs well under normal conditions.
```

None of these state a measurable threshold, a defined population, or an
observable pass/fail condition: "reasonable time", "feel fast", "typical users",
"high load", "gracefully", and "performs well under normal conditions" are all
unverifiable. There is no number, no tool, and no scenario that could decide
whether the requirement is met.

## Expected

`review-final` MUST return **BLOCK** and identify the untestable / unmeasurable
requirements: every one of R1–R3 and the acceptance criterion fails the
testability lens (no measurable threshold, no available-tooling success metric —
FEA-04). A PASS here is a regression — the spec reviewer has accepted
requirements it cannot verify, exactly what the review-specification constitution
forbids. (Cross-refs: review-constitution review-specification lane, FEA-04.)
