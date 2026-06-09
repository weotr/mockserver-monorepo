# Documentation Style — Pyramid Principle with Progressive Disclosure

## Purpose

This rule defines the **default shape** for documentation MockServer agents
author or update: architecture docs in `docs/`, ADRs, READMEs, design specs,
investigation reports, and any prose summary returned to the user.

The style is the **Pyramid Principle** (Barbara Minto) combined with
**progressive disclosure**: lead with the conclusion, then layer supporting
detail beneath it so a reader can stop at any depth and still leave with the
right takeaway.

> **One-line definition:** outcome-first, progressively detailed — present the
> decision and high-level flow up front, then enable structured drill-down into
> rationale, then implementation, then deep reference.

## The Principle

A reader's time is the scarce resource. Order content by **how many readers
need it**, not by the order in which you discovered it or the order the system
executes in:

- **The top layer is for everyone** — the answer, the decision, the outcome.
- **Each layer down serves fewer readers in more depth** — supporting options,
  then rationale and trade-offs, then implementation, then appendix-level detail.
- **Never introduce detail before its context.** A section may only assume what
  an earlier (higher) section already established.

This is the inverse of a discovery narrative ("first I looked at X, then Y, and
*therefore* the answer is Z"). Documentation states **Z first**, then X and Y as
support. Save the journey for commit messages and investigation logs, not docs.

## The Reusable Skeleton

Use this as the default outline for any document longer than a screenful.
Collapse or merge layers for shorter docs (see *Applying Judgement*), but never
reorder them so that detail precedes its conclusion.

```
1. Outcome / Decision (TL;DR)   — what this is and the bottom line, in 2-5 lines
2. High-level flow / model      — one Mermaid diagram of the shape of the thing
3. Key options or components    — a table or tight bullet list of the moving parts
4. Rationale / trade-offs       — why it is this way; what was rejected and why
5. Detailed behaviour           — the implementation-level prose, per component
6. Appendix / deep reference    — exhaustive tables, edge cases, config knobs
```

Layers 1–3 are the "executive read" (≈30 seconds to 2 minutes). Layers 4–6 are
the "engineer drill-down" (5+ minutes). A reader who stops after layer 1 must
still come away correct, just less complete.

## Applying Judgement

This is a strong default, **not a rigid form**. Use sense:

- **Short docs** (a small README, a note, a one-screen page) may collapse the
  skeleton to *outcome → detail*. Don't manufacture empty "Rationale" or
  "Appendix" headings to satisfy the template.
- **Reference-only material** (a pure config-property table, a glossary) is
  legitimately flat — but still give it a one-line "what this is and when you'd
  reach for it" opener so the reader can self-select.
- **The conclusion-first rule is non-negotiable even when the structure flexes.**
  Whatever the length, the first thing the reader sees is the takeaway, not the
  build-up to it.

## Scannability

Pyramid structure fails if each layer is a wall of prose. Within every layer:

- Prefer **tables and tight bullet lists** over long paragraphs.
- Keep paragraphs to 3–4 sentences.
- Use **one Mermaid diagram** for the high-level model rather than describing the
  shape in words (see `.opencode/rules/mermaid-diagrams.md` for diagram rules).
- Bold the load-bearing phrase in a bullet so the page is skimmable.

## How This Relates To Other Rules

- **Diagrams** — the layer-2 "flow / model" is almost always a Mermaid diagram.
  Follow `.opencode/rules/mermaid-diagrams.md` (no HTML tags, real newlines).
- **Reports** — structured-JSON reports formatted by the parent agent must lead
  with the verdict/outcome. See `.opencode/rules/report-formatting.md`; its
  mandatory attribution line sits in the layer-1 TL;DR, above everything else.
- **Specs** — `.opencode/skills/ideate/spec-template.md` is the canonical worked
  example of this rule (executive summary first, explicit three-layer disclosure).
- **Docs index** — `docs/README.md` tags each doc with a High/Medium/Low *Level*;
  that column is progressive disclosure applied across the doc set, not just
  within one doc.

## Context

This pattern is what stakeholders expect from platform/strategy and engineering
docs: executives and reviewers read the top, implementers drill down, and nobody
has to reconstruct the conclusion from the bottom up. Anchor terminology on
**"Pyramid Principle with progressive disclosure"** when describing the style to
others.
