# Licence & IP Provenance

Generated artefacts — especially code — must be checked for **licence / IP
contamination** before reintegration. Conforms to the AI-in-SDLC spec
(`docs/operations/ai-sdlc-integration-spec.md` §16 S13). The project is licensed
**Apache-2.0** (`LICENSE.md`).

## The rule

Before committing generated code:

- **MUST NOT reproduce non-trivial verbatim blocks** of third-party code from
  memory. If a snippet clearly originates from a specific licensed source
  (especially copyleft — GPL / AGPL / LGPL — or any licence incompatible with
  Apache-2.0), do **not** include it: rewrite it independently, or reject and
  escalate.
- **New dependencies MUST carry an Apache-2.0-compatible licence** (permissive:
  Apache-2.0, MIT, BSD, etc.). A copyleft or otherwise incompatible dependency is
  a **blocker** — escalate; do not add it silently. (See
  `docs/operations/security.md` for the dependency policy.)
- **MUST preserve attribution / notices** where code is legitimately adapted from
  a compatibly-licensed source.
- **MUST record provenance** where policy requires it (decision log).

## When in doubt

Treat suspected contamination like a control issue: do **not** silently ship it —
flag and escalate. The authoritative allowed-licence set is an open decision (spec
§22.6); until it is fixed, default to the project's Apache-2.0 posture (permissive
licences only) and escalate anything else.
