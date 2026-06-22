---
id: review-passes-clean-change
category: review
agent: review-cheap
expected_verdict: PASS
---
## Scenario

A trivially-correct change must PASS review without a false-positive BLOCK. This
guards against a prompt/constitution change that makes the reviewer over-strict
and noisy (which trains agents to ignore it).

## Input

A documentation-only diff that fixes a typo in a Javadoc comment and adds a
clarifying sentence — no behaviour change, no public-API change, no new code path:

```diff
- * Retruns the matched expectation, or null if none matched.
+ * Returns the matched expectation, or null if none matched.
+ * The first registered expectation wins when several match.
```

## Expected

`review-cheap` MUST return **PASS** (findings, if any, are OBSERVATION/MINOR). A
BLOCK here is a regression — the reviewer is manufacturing blocking findings on a
clean change.
