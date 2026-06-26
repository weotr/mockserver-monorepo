# Client Feature-Parity Roadmap

## Outcome / Decision

> **Status (2026-06-24): core parity ACHIEVED.** Every substantive gap this roadmap tracked is now
> closed — stateful scenarios, control-plane auth + TLS/mTLS, editor JSON-schema sync, and the last
> real gap (**callbacks**, `e55ccedb3`). Only **optional consistency follow-ups** and **doc-nav
> polish** (both below) remain — nice-to-haves, no functional client gap.

The 8 MockServer client libraries were **not** at feature parity with the Java client / core
model. The **stateful-scenario** gap was closed first (typed scenario APIs in every client +
a Docker validation harness + runnable examples + docs — see the Stateful Scenarios work). This
roadmap tracked the **remaining** parity gaps, each validated through the
`examples/validate/run.sh` Docker harness; all are now delivered (see below).

## What was just delivered (scenarios — DONE)

- Typed scenario support in all 8 clients: `scenarioName`/`scenarioState`/`newScenarioState`,
  `httpResponses` + `responseMode`, `responseWeights`, `switchAfter`, `crossProtocolScenarios`,
  and a `scenario(name)` REST helper (`state`/`set`/`set-timed`/`trigger`) + `scenarios()` list.
- Core bug fixed: `crossProtocolScenarios` was modelled and handled at runtime but missing from the
  expectation validation schema, so the server rejected it (HTTP 400). Added to
  `expectation.json` + `mock-server-openapi-embedded-model.yaml`.
- `examples/<collection>/scenario/` runnable, self-asserting examples for all 10 collections,
  validated end-to-end by `examples/validate/run.sh` (every client passes against a freshly-built
  MockServer image).

## Gap re-verification (2026-06-22) — the breadth survey was substantially wrong

A precise, file:line re-verification of every claimed gap found **most were already complete**:

| Area | Survey said | Reality |
|------|-------------|---------|
| OpenAPI import / matcher | missing in Go, PHP | **already COMPLETE** in both (`openapi.go`, `OpenAPIExpectation.php`) |
| LLM mocking builders | missing in Go | **already COMPLETE** (`llm.go`) |
| Load-scenario injection | missing in PHP | **already COMPLETE** (`MockServerClient.php` loadScenario/status/stop + `LoadScenario.php`) |
| Breakpoint / debugging | missing in PHP | **infeasible by design** — PHP client is REST-only (no WebSocket); breakpoints need a bidirectional callback WS. Go/.NET/Rust/Python/Node/Java all have it. |
| `responseWeights`/`switchAfter` setters | confirm | done in the scenario work |

## DONE in the parity waves

- **Control-plane auth + TLS/mTLS** across Go, .NET, Rust, PHP, Node, Python: control-plane bearer
  token (static + supplier), CA trust, client cert (mTLS). Additive; unit-tested per client (header
  attach + cert/TLS wiring with real cert material).
- **Editor JSON Schema sync**: regenerated `mockserver-vscode` + `mockserver-jetbrains` schemas (now
  carry `crossProtocolScenarios`/`responseWeights`/`switchAfter`/`rateLimit`/full `responseMode`);
  fixed the generator's reference-file list (`rateLimit`, `conditionalRequestDefinition`, `recoverAfter`).

## The ONE real remaining gap: callbacks — ✅ DONE (`e55ccedb3`)

Callbacks across the clients (class + object/closure) shipped in `e55ccedb3`
(*"feat(clients): callbacks across the clients (class + object/closure)"*). Class callbacks
(declarative, REST-only) and object/closure callbacks (over the callback WebSocket) were added to
the clients that lacked them; PHP remains documented as object/closure-infeasible (REST-only, no WS),
as designed. The parity roadmap's headline gap is closed.

Callback wire contract (retained for reference, from `httpClassCallback.json` / `httpObjectCallback.json` + `CallbackWebSocketServerHandler`): class = `{callbackClass, delay?, primary?}`; object = `{clientId, responseCallback, delay?, primary?}`; the WS registers a clientId, the server sends `{type:"org.mockserver.model.HttpRequest", value}` and the client replies `{type:"org.mockserver.model.HttpResponse", value}` carrying a `WebSocketCorrelationId` header. Reference: Node `webSocketClient.js`, Java `BreakpointWebSocketClient`.

## Optional consistency follow-up

- **Consumer OpenAPI** (`jekyll-www.mock-server.com/mockserver-openapi.yaml`) is a minimal control-plane
  spec; optionally extend its expectation model with `crossProtocolScenarios` for the generated
  Postman/Bruno collections.
- **Wire the editor-schema generator into the JetBrains gradle build** (VS Code already runs it on
  `vscode:prepublish`) so the schema can't drift again.

## Documentation navigation follow-ups (from the nav audit)

The Stateful Scenarios work also delivered a nav audit. Top fixes applied: a new self-navigating
Stateful Scenarios page; a feature-overview table added to `response_templates.html`. Remaining
high-value fixes:

| Page | Lines | Fix | Size |
|------|-------|-----|------|
| `configuration_properties.html` | ~2,418 | top-of-page searchable property index across the 12 includes | M |
| `chaos_testing.html` | ~1,770 | feature/overview matrix + wrap long example sequences in accordions | M |
| `using_openapi.html` | ~1,508 | capability feature table (generate / match / verify / clear × OpenAPI / WSDL) | S |
| `debugging_issues.html` | ~904 | top feature table of retrieval methods | S |
| `proxy/configuring_sut.html` | ~448 | anchor every h3 + brief proxy-type TOC | S |

## How to validate each wave

Use `examples/validate/run.sh <client>` — it builds `mockserver-under-test:local`, starts it on a
private Docker network, and runs that client's `examples/<lang>/...` against it inside the matching
toolchain container (mounting the host CA bundle so dependency fetches work behind a TLS-inspecting
proxy). Add a `scenario`-style runnable, self-asserting example for each new feature so the harness
gates it.
