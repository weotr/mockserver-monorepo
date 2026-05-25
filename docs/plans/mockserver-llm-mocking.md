# RFC: First-Class LLM & Agent Mocking for MockServer

**Status:** Draft for discussion (updated 2026-05-25 after codebase fact-check)
**Scope:** Two detailed feature designs (LLM Response Builder, Stateful Conversations) + competitive gap analysis and a revised priority list.
**Audience:** MockServer maintainers / platform engineering.

---

## 1. Context

MockServer already has an unusually deep AI story for a general-purpose mock/proxy: an MCP control plane (**22 tools** — see `McpToolRegistry`), mocking of *other* MCP & A2A servers (`mcpMock(...)` / `a2aMock(...)` builders), SSE/WebSocket/JSON-RPC transport mocking (`HttpSseResponse`, `HttpWebSocketResponse`, `JsonRpcBody`), a TLS-intercepting proxy with an LLM-aware Traffic Inspector (Conversation view, SSE timeline, token counts, HAR download via the AppBar button), OpenAPI contract/resiliency testing (`run_contract_test`, `run_resiliency_test`), and `record_llm_fixtures` / `load_expectations_from_file` for replay.

**A generic scenario state machine is already implemented** — `Expectation` has `scenarioName`, `scenarioState`, `newScenarioState` fields backed by `ScenarioManager.java`, exposed in the JSON expectation format, and documented at `jekyll-www.mock-server.com/mock_server/_includes/creating_expectations.html` ("Stateful Scenarios"). This means RFC-2 **Layer A is already shipped**; the remaining gap is Layer B (LLM-conversation sugar that targets that state machine).

The genuine gap is at the **semantic layer for LLM and agent testing**. You can mock the SSE *transport*, but to mock an actual Anthropic or OpenAI response today you must hand-assemble the provider's streaming chunk sequence. And while scenarios exist, there is no LLM-conversation-aware matching layer — branching on `whenLatestMessageContains` / `whenContainsToolResultFor("search")` still has to be done by hand against raw bodies.

This RFC fleshes out the two foundational features that close that gap. Everything else in the priority list composes on top of them.

A strategic note up front: a class of narrow, single-language LLM-mock tools has appeared (see §4). MockServer's differentiation is that it is **one language-agnostic tool** spanning HTTP/HTTPS/gRPC/SSE/WebSocket/JSON-RPC/MCP/A2A with a control plane, proxy, and dashboard. The opportunity is to absorb the LLM-specific *conveniences* those tools pioneered while keeping that general-purpose, proxy-plus-control-plane footing.

---

## 2. RFC-1 — LLM Response Builder (`llmMock`)

### 2.1 Problem

To return an Anthropic Messages or OpenAI Chat Completions response today, you hand-craft either a JSON body or — for streaming — the exact event sequence (`message_start`, `content_block_start`, *N*× `content_block_delta`, `content_block_stop`, `message_delta`, `message_stop` for Anthropic; `chat.completion.chunk` role/content deltas then `finish_reason` for OpenAI). This is fiddly, provider-specific, and drifts as formats change. Yet MockServer **already owns this format knowledge** — the Traffic Inspector decodes these formats. This RFC inverts that decoder into a response *encoder*.

### 2.2 Design principles

- **One codec, both directions.** A single `ProviderCodec` interface (`encode(Completion) → wire`, `decode(wire) → Completion`) is the single source of truth, shared by the Traffic Inspector and the new builder. Implementations are dependency-injected so providers can be added/tested in isolation (consistent with MockServer's existing DI-for-testability approach).
- **High-level intent in, correct wire format out.** The caller describes *what* the model says (text, tool calls, usage, stop reason); the codec produces the byte-correct streaming or non-streaming representation.
- **Reuse the SSE engine.** Streaming responses are emitted through the existing `httpSseResponse` machinery, so no new transport code.
- **Mirror existing builders.** Same fluent style as `mcpMock(...)` / `a2aMock(...)`.

### 2.3 Java builder API

```java
import static org.mockserver.client.LlmMockBuilder.llmMock;
import static org.mockserver.model.Completion.completion;
import static org.mockserver.model.ToolUse.toolUse;

// --- Simple text completion ---
llmMock("/v1/messages")
    .withProvider(ANTHROPIC)                 // ANTHROPIC | OPENAI | OPENAI_RESPONSES | GEMINI | BEDROCK | AZURE_OPENAI | OLLAMA
    .withModel("claude-sonnet-4")
    .respondingWith(
        completion()
            .withText("The capital of France is Paris.")
            .withStopReason(END_TURN)
            .withUsage(inputTokens(42), outputTokens(8))
    )
    .applyTo(mockServerClient);

// --- Tool / function call ---
llmMock("/v1/messages")
    .withProvider(ANTHROPIC)
    .respondingWith(
        completion()
            .withText("Let me check the weather.")
            .withToolCall(toolUse("get_weather").withArguments("{\"city\":\"Paris\"}"))
            .withStopReason(TOOL_USE)
    )
    .applyTo(mockServerClient);

// --- Streaming with realistic timing ("streaming physics") ---
llmMock("/v1/chat/completions")
    .withProvider(OPENAI)
    .withModel("gpt-4o")
    .respondingWith(
        completion()
            .withText("Streaming token by token...")
            .streaming()
            .withStreamingPhysics(
                timeToFirstToken(300, MILLISECONDS),
                tokensPerSecond(50),
                jitter(0.2))             // ±20% per-chunk variance
    )
    .applyTo(mockServerClient);

// --- Embeddings (deterministic, seeded from input) ---
llmMock("/v1/embeddings")
    .withProvider(OPENAI)
    .respondingWith(
        embedding()
            .withDimensions(1536)
            .deterministicFromInput()        // same input text → same vector, every run
    )
    .applyTo(mockServerClient);
```

### 2.4 REST / JSON form

A new `httpLlmResponse` action, sibling to `httpSseResponse`:

```json
{
  "httpRequest": { "method": "POST", "path": "/v1/messages" },
  "httpLlmResponse": {
    "provider": "ANTHROPIC",
    "model": "claude-sonnet-4",
    "text": "The capital of France is Paris.",
    "toolCalls": [],
    "stopReason": "end_turn",
    "usage": { "inputTokens": 42, "outputTokens": 8 },
    "streaming": true,
    "streamingPhysics": { "timeToFirstTokenMs": 300, "tokensPerSecond": 50, "jitter": 0.2 }
  }
}
```

When `streaming: true`, MockServer expands the completion into provider-correct SSE events internally — the user never authors chunk-level detail.

### 2.5 MCP tool

```
mock_llm_completion (High)
  provider     string  required   ANTHROPIC|OPENAI|OPENAI_RESPONSES|GEMINI|BEDROCK|AZURE_OPENAI|OLLAMA
  path         string  required
  model        string  optional
  text         string  optional
  toolCalls    array   optional   [{ "name": string, "arguments": string|object }]
  stopReason   string  optional
  usage        object  optional   { "inputTokens": int, "outputTokens": int }
  streaming    boolean optional   default false
```

This lets a coding agent set up a deterministic mock LLM purely from natural language ("make a mock Anthropic endpoint that calls the `search` tool then answers").

### 2.6 Out of scope / open questions

- Provider coverage at launch: Anthropic + OpenAI (Chat Completions) cover the majority; Gemini/Bedrock/Azure/Ollama as fast-followers.
- Should `usage` auto-estimate token counts from text when omitted (tokenizer dependency) or stay explicit? Recommend explicit by default, optional estimate behind a flag to avoid a heavy tokenizer dependency in the core.

---

## 3. RFC-2 — Stateful Scripted Conversations

### 3.1 Problem

Agents are multi-turn loops. A meaningful test needs the mock LLM to answer *differently depending on conversation state* — turn 1 returns a `tool_use`; turn 2 (after the agent sends back a `tool_result`) returns the final answer.

**Layer A (generic scenarios) is already shipped.** `Expectation` exposes `scenarioName`, `scenarioState`, and `newScenarioState`; `ScenarioManager.java` is the in-memory state-machine store; the JSON expectation format already accepts these fields; and the docs site documents "Stateful Scenarios" with multi-step examples (Login, Pagination, Retry, Order lifecycle). What is missing is the **LLM-conversation-aware layer on top** that lets you branch on the parsed `messages` array of the inbound request body without writing custom matchers.

### 3.2 Two layers

**Layer A — generic scenario state machine** (already shipped; documented here for completeness). Expectations support three optional fields:

```json
{
  "scenarioName": "checkout",
  "scenarioState": "cart-created",
  "newScenarioState": "payment-pending",
  "httpRequest":  { "method": "POST", "path": "/pay" },
  "httpResponse": { "statusCode": 200 }
}
```

An expectation only matches when the named scenario is in `scenarioState`; on match it transitions the scenario to `newScenarioState`. Independently useful for any stateful REST workflow — and the foundation Layer B builds on.

> **Naming note:** the field is `scenarioState` (the *required* current state), not WireMock's `requiredScenarioState`. Reusing existing field names avoids a breaking change. A `requiredScenarioState` alias could be added if WireMock-source-compatibility is judged worthwhile.

**Layer B — LLM-conversation sugar** built on Layer A:

```java
llmMock("/v1/messages")
    .withProvider(ANTHROPIC)
    .conversation()                                   // scripted, stateful
        .turn()                                       // turn 1: model decides to use a tool
            .respondingWith(completion()
                .withToolCall(toolUse("search").withArguments("{\"q\":\"weather paris\"}"))
                .withStopReason(TOOL_USE))
        .turn()                                       // turn 2: after tool_result arrives, finalize
            .whenLatestMessageContains("tool_result")
            .respondingWith(completion()
                .withText("It's 18°C and sunny in Paris.")
                .withStopReason(END_TURN))
    .applyTo(mockServerClient);
```

### 3.3 Conversation-aware matching

The matcher operates on the parsed `messages` array of the inbound request body (already parsed for the Conversation view). New predicates:

- `whenTurnIndex(n)` — match the n-th model call in the conversation.
- `whenLatestMessageContains(text|regex)` — match on the most recent message.
- `whenLatestMessageRole(USER|TOOL)` — branch on whether a tool result just arrived.
- `whenContainsToolResultFor("search")` — match when a specific tool's result is present.

These compose with the scenario state machine: each `turn()` implicitly advances scenario state, so the common agent loop needs no explicit state names.

### 3.4 MCP tool

```
create_llm_conversation (High)
  provider   string  required
  path       string  required
  turns      array   required   ordered list of {
                                   match:    { latestMessageContains?, latestMessageRole?, containsToolResultFor? },
                                   response: { text?, toolCalls?, stopReason?, usage? }
                                 }
```

### 3.5 Why this ordering (RFC-1 before RFC-2 Layer B)

RFC-2 Layer B produces responses via the RFC-1 `Completion` model, so the response builder must land first. Layer A (generic scenarios) is **already shipped** — no parallel work needed.

---

## 4. Competitive Gap Analysis

Two cohorts matter: established general-purpose mock tools, and a fast-growing class of **LLM-specific** mock tools that have crystallised what the market now expects for agent testing.

### 4.1 General-purpose tools

| Capability | Who has it | MockServer today | Action |
|---|---|---|---|
| Stateful scenarios (state machine) | WireMock | **DONE** — `scenarioName` / `scenarioState` / `newScenarioState` fields on `Expectation`, backed by `ScenarioManager`, documented as "Stateful Scenarios" on the website | RFC-2 Layer A no longer required |
| Best-in-class fault/chaos injection (random %-rate 5xx, rate-limit simulation, brownouts, dropped connections, malformed responses) | WireMock (+ Cloud "chaos mode") | Per-event delays + error action + resiliency *test* + per-expectation `percentage` probabilistic matching, but no declarative chaos *profile* | **High — new "chaos profile" (see §5 #6)** |
| Data generation / fake-data templating | Mockoon (Faker), WireMock (Handlebars) | Mustache/Velocity templates, no fake-data helpers | Low–med: add Faker-style helpers to templates |
| Sequential / cycling responses per stub | Mockoon, ProxyMock | `times`-based only, no cycle-through-list | Subsumed by RFC-2 |
| Capture-and-diff (mock vs real) | Hoverfly "diff mode" | None | Med — pairs with drift detection (§4.2) |
| Multi-protocol async (Kafka/MQTT via AsyncAPI) | Microcks | HTTP + gRPC only | Out of near-term scope; note it |
| Extension model (custom matchers/transformers) | WireMock | Class/closure callbacks | Largely covered |
| AI-generated sims from spec/traffic/NL | WireMock Cloud | `create_expectation_from_openapi`, `…_from_recorded_traffic`, NL via MCP agent | Largely covered |

### 4.2 LLM-specific tools (the important cohort)

These tools (`fakellm`, CopilotKit `llmock`/`aimock`, `pytest-mockllm`, `llm-rewind`, Speedscale `proxymock`, Docker Cagent) are narrow and single-language, but they reveal the **table-stakes feature set** for LLM/agent testing:

| Capability | Who has it | MockServer today | Action |
|---|---|---|---|
| Provider-correct response synthesis (OpenAI/Anthropic/Gemini/Bedrock/Azure) | llmock, fakellm, pytest-mockllm | None (hand-crafted SSE) | **RFC-1** |
| Sequential / multi-turn stateful fixtures | llmock | Generic scenario state machine present (`scenarioState`/`newScenarioState`), but no LLM-conversation-aware matching layer | **RFC-2 Layer B** |
| Streaming physics: configurable TTFT, tokens/sec, jitter | llmock | Per-event delays only | **Fold into RFC-1** |
| VCR record/replay with **proxy-on-miss** + **strict mode** (fail unmatched in CI) | llmock, pytest-mockllm, proxymock, Cagent | `record_llm_fixtures` + load, but no auto proxy-on-miss, no strict mode | **High — extend §5 #7** |
| Volatile-field normalisation (IDs, timestamps) for determinism | Cagent | None | Fold into #7 |
| Body-level PII / secret redaction in fixtures | pytest-mockllm | Headers only (docs flag body gap) | Fold into #7 |
| Token counting + cost dashboard + **budget assertions** | pytest-mockllm | Dashboard token counts only | **§5 #5** |
| **Drift detection** (CI vs real API to catch format changes) | llmock | None | **New — med (§5 #13)** |
| **Run bisection / diff** (find where a bad run diverged from a good one) | llm-rewind; Hoverfly diff | None | **New — med-high (§5 #14)** |
| Cassette export as portable incident bundle | llm-rewind | Fixtures committable but not framed as shareable incident artefact | Low: easy win on #7 |
| Embeddings API mocking | llmock | None | Fold into RFC-1 (med) |
| Vector DB mocking (Pinecone etc.) | aimock | None (raw HTTP only) | Low — niche |
| Realtime/WebSocket LLM APIs (OpenAI Realtime, Gemini Live) | llmock | Generic WebSocket, no LLM-semantic realtime | Low–med |
| Prometheus `/metrics` (request counts, latencies, fixture match-rate) | llmock | Dashboard, limited machine metrics | Med — useful in CI |

### 4.3 Two genuinely new ideas the scan surfaced

Beyond confirming the existing priority list, the competitive scan adds two items worth promoting:

1. **Drift detection** — periodically replay recorded fixtures against the *real* provider in CI and flag when the live response shape diverges from the fixture. This is the maintenance answer to "cassettes go stale," and it fits MockServer's existing diagnostic-with-remediation pattern.
2. **Run bisection / diff** — given a known-good captured run and a known-bad one, diff the two traces (prompts, tool calls, responses) and surface the first point of divergence. This is the agent-debugging analogue of `git bisect` and a natural companion to the proposed `explain_agent_run` analysis tool.

---

## 5. Revised Priority List

Tiering updated to fold in the competitive findings. Items 1–12 carry over from the initial analysis; 13–14 are new.

**Tier 1 — foundational**
1. **LLM response builder** (`llmMock`) — RFC-1. Unblocks everything below.
2. **Stateful scripted conversations** — RFC-2 Layer B (Layer A already shipped). The agent-loop enabler.
3. **Tool-call assertions** (`verify_tool_call`) — verify which tools were called with what args, understanding provider tool schemas.
4. **Agent-run / LLM-session analysis** (`explain_agent_run`) — ranked diagnosis of loops, duplicate tool calls, retries, token hotspots, context pressure.

**Tier 2 — high value**
5. **Token/cost analytics + budget assertions** — per-model pricing, per-session rollups, assert on token/round-trip budgets.
6. **LLM fault/chaos profiles** — declarative 429/529 + Retry-After, mid-stream truncation, malformed SSE, probabilistic error rates, rate-limit simulation. (Streaming physics from RFC-1 covers slow-TTFT.)
7. **VCR mode + strict mode + body redaction + field normalisation** — auto record-if-missing/replay-if-present keyed on request content; strict-fail in CI; redact body PII/secrets; normalise volatile IDs/timestamps; export portable cassettes.

**Tier 3 — valuable / specialised**
8. **MCP/A2A conformance contract testing** (`run_mcp_contract_test`).
9. **Semantic / normalised prompt matching** for non-deterministic prompts.
10. **OTel GenAI / OpenInference span export** for downstream eval tooling.
11. **Correlated agent-run session / call-graph view** across LLM + MCP + downstream calls.
12. **Prompt-injection / adversarial-response harness** for guardrail testing.
13. **Drift detection** *(new)* — fixtures vs real API in CI.
14. **Run bisection / diff** *(new)* — locate where a bad run diverged from a good one.

---

## 6. Suggested Sequencing

1. **RFC-1 core** (Anthropic + OpenAI, non-streaming then streaming via existing SSE engine; `ProviderCodec` shared with Traffic Inspector).
2. **RFC-2 Layer B** once RFC-1 lands (Layer A already shipped — `scenarioName`/`scenarioState`/`newScenarioState`).
3. Tool-call assertions (#3) and `explain_agent_run` (#4) — both read the parsed message/tool structures introduced by RFC-1 + Layer B.
4. VCR/strict/redaction (#7), then cost (#5) and chaos (#6).
5. Drift detection (#13) and bisection (#14) on top of the mature record/replay + analysis foundation.
