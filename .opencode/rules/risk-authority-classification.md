# Risk & Authority Classification

Every unit of work is classified by **risk**, and that class sets how
autonomously AI may act on it. Autonomy is **earned through verification
strength and track record, not assumed**. Conforms to the AI-in-SDLC spec
(`docs/operations/ai-sdlc-integration-spec.md` §3.3, §19).

## Authority classes

| Class | AI may… | Gate before reintegration |
|-------|---------|---------------------------|
| **Act autonomously** | produce **and** reintegrate | the full [[commit-workflow]] gate chain returns PASS — no human pre-approval |
| **Gated approval** (spec: "act with gated approval") | produce, but not reintegrate alone | gate chain PASS **plus** explicit human / service-owner approval |
| **Advisory only** | propose; a human decides and owns the action | human decision |
| **Reserved** | **MUST NOT act** — the decision belongs **exclusively to a human authority**; explicit human direction is required before acting | — |

These class names are used consistently as `act-autonomously` / `gated-approval`
/ `advisory` / `reserved` throughout the rules.

## Risk dimensions (classify each unit on these)

Blast radius · reversibility · security sensitivity · compliance sensitivity ·
production impact · customer impact · ambiguity · verification coverage/strength ·
novelty · dependency complexity.

## Routing — risk to authority

- **Low risk + strong verification** (local code/docs/tests fully covered by the
  gate chain, reversible) → **MUST** route to **act-autonomously**.
- **Medium risk / weaker verification** (cross-module, partial coverage, novel
  pattern) → **act-autonomously only if the gate chain fully covers the change**;
  otherwise **MUST** route to **gated-approval**.
- **High risk / sensitive / irreversible** → **MUST** route to **advisory or
  reserved**.

### Always at least Gated approval (never autonomous), regardless of size

- Changes to the **controls themselves** — tests/gates, the review constitution,
  model/temperature/effort routing, guardrails, or this risk/authority policy. These are
  the *higher-scrutiny change class*; see [[control-integrity]]. **Separation of
  duties is mandatory**: the agent that authored the change MUST NOT be its
  approving reviewer — a distinct review agent or a human gates reintegration.
  When the change is to an **AI component** (prompt, constitution, routing,
  guardrail, model/version), it must additionally pass the evaluation harness
  before rollout.
- Production-affecting or irreversible actions: releases, production infra /
  Terraform `apply`, secrets/credentials, DNS, data deletion.
- Destructive git (`reset --hard`, `push --force`, history rewrite, `clean -fd`) —
  already requires explicit confirmation, see [[git-safety]].

### Reserved (AI must not act without explicit user direction)

- Irreversible external/publishing actions (e.g. publishing to a public registry,
  sending external communications).
- Policy changes to the authority classes or risk thresholds **themselves** —
  these are owned by the user / policy owner, not self-modifiable by an agent.

## Earned autonomy

A work type's autonomy level **MAY** be promoted only on a track record of
verified success, and **MUST** be demoted on evidence of failure. When
verification cannot reach sufficient confidence for the unit's class, **downgrade**
(autonomous → gated → advisory) and escalate rather than proceeding.

## Escalation

Escalate when ambiguity materially blocks progress, a unit exceeds its authority
class, verification cannot reach confidence, or a policy boundary is hit. Make the
escalation structured (what is unclear · why it matters · recommended option first
· alternatives · impact). See [[operating-model]] (Clarify Well, Rarely) and
[[commit-workflow]].
