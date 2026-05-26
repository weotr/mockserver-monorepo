# RFC: First-Class LLM & Agent Mocking for MockServer

**Status:** Draft for discussion (updated 2026-05-26 after adversarial spec review)
**Scope:** Two detailed feature designs (LLM Response Builder, Stateful Conversations) + competitive gap analysis, revised priority list, failure-mode contracts, and an affected-artifacts inventory.
**Audience:** MockServer maintainers / platform engineering.

**Revision notes (2026-05-26):**
- §2.1 / §2.2 corrected: LLM format knowledge currently lives in the **TypeScript UI** (`mockserver-ui/src/lib/llmTraffic.ts`), not in Java. The proposed `ProviderCodec` is net-new Java work, not a refactor of an existing decoder.
- §2.3 pins down `deterministicFromInput()`, jitter distribution, and the SSE engine integration for streaming physics.
- §2.4 commits to `httpLlmResponse` as a **first-class action type** (new `Action.Type.LLM_RESPONSE`, new `Expectation` field, new DTO, dashboard rendering).
- §2.7 (new) — Failure modes.
- §3.3 specifies provider detection (inherited from the expectation's `withProvider(...)`), parse-failure semantics, body-size limits, and conversation isolation via `isolateBy(...)`.
- §7 (new) — Affected artifacts inventory.

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

To return an Anthropic Messages or OpenAI Chat Completions response today, you hand-craft either a JSON body or — for streaming — the exact event sequence (`message_start`, `content_block_start`, *N*× `content_block_delta`, `content_block_stop`, `message_delta`, `message_stop` for Anthropic; `chat.completion.chunk` role/content deltas then `finish_reason` for OpenAI). This is fiddly, provider-specific, and drifts as formats change.

MockServer has partial format knowledge **on the UI side** — the Traffic Inspector parses Anthropic and OpenAI request/response shapes in `mockserver-ui/src/lib/llmTraffic.ts` (TypeScript) for the Conversation view, token counts, and SSE timeline. There is currently **no Java-side LLM format code** in `mockserver-core` or `mockserver-netty`. This RFC introduces that Java-side format code in both directions (encode for the builder, decode for conversation-aware matchers in RFC-2 Layer B) and identifies the TS-vs-Java parser duplication as a deliberate, time-limited compromise (see §2.2).

### 2.2 Design principles

- **One Java codec, both directions — within Java.** A single `ProviderCodec` interface in `mockserver-core` exposes both:
  - `encode(Completion, EncodingHints) → HttpResponse | List<SseEvent>` — used by `httpLlmResponse` to produce the wire form.
  - `decode(HttpRequest) → ParsedConversation` — used by RFC-2 Layer B conversation-aware matchers to read the inbound `messages` array.

  Implementations are registered via a `ProviderCodecRegistry` (DI-friendly, consistent with MockServer's existing patterns) and unit-testable in isolation per provider.
- **Glossary:** `Completion` is a **MockServer-specific, provider-neutral abstraction** of a single model turn. It is not the OpenAI `completion` object. It maps to Anthropic `Message`, OpenAI `ChatCompletion`, OpenAI Responses `output`, etc. via the codec. See §7.4 for the canonical class.
- **TS-vs-Java parser duplication is accepted, time-limited.** The existing TypeScript parser (`mockserver-ui/src/lib/llmTraffic.ts`) is kept as-is for now; the Java decoder is independent. Convergence (either porting the TS parser to call a `/api/llm/parse` endpoint backed by the Java decoder, or generating both from a shared JSON Schema) is tracked as a follow-up task in §7.
- **High-level intent in, correct wire format out.** The caller describes *what* the model says (text, tool calls, usage, stop reason); the codec produces the byte-correct streaming or non-streaming representation.
- **Reuse the SSE engine.** Streaming responses are emitted through the existing `httpSseResponse` machinery (`HttpSseResponseActionHandler`), so no new transport code. See §2.3.4 for the explicit mapping from streaming-physics parameters to `SseEvent.delay`.
- **Mirror existing builders.** Same fluent style as `mcpMock(...)` / `a2aMock(...)` — both already live in `mockserver-client-java`, so `LlmMockBuilder` belongs in the same package (`org.mockserver.client`).

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
            // optional: .withSeed(42)       // additional salt; default 0
    )
    .applyTo(mockServerClient);
```

### 2.3.1 `deterministicFromInput()` — algorithm

Pinned to remove cross-JVM flakiness:

1. Normalise input: UTF-8 bytes of the raw input text (no trimming, no case folding).
2. Compute `hash = SHA-256(seedBytes ++ inputBytes)` where `seedBytes = UTF-8(String.valueOf(seed))` (default `seed=0`).
3. Seed `java.util.Random` with the first 8 bytes of `hash` interpreted as a big-endian long.
4. Generate `dimensions` floats in `[-1.0, 1.0]` via `random.nextDouble() * 2 - 1`.
5. L2-normalise the vector to unit length.

This produces reproducible, plausible-looking vectors across JVMs and JDK versions. It is a test utility, not a real embedding model. Same input + same `dimensions` + same `seed` → identical vector. Different `dimensions` values produce **independent** vectors (not prefix-compatible).

### 2.3.2 Streaming physics — units, distribution, and SSE engine integration

| Parameter | Type | Units / range | Default | Semantics |
|---|---|---|---|---|
| `timeToFirstToken` | `Delay` | any `TimeUnit` | `0 ms` | The first SSE event MUST be emitted after `Action.getDelay()` has elapsed AND then a further `timeToFirstToken` delay — total wall-clock delay = `Action.getDelay() + timeToFirstToken` from when the request is received. The two are sequential, not parallel. |
| `tokensPerSecond` | int | `1..10_000` | `50` | Base inter-event rate. Each SSE event represents one token (chunking strategy: one token per `content_block_delta` for Anthropic, one token per `chat.completion.chunk` for OpenAI). Per-event base delay = `1000 / tokensPerSecond` ms. |
| `jitter` | double | `[0.0, 1.0]` | `0.0` | Fractional uniform deviation. Per-event delay = `baseDelay * (1 + Uniform(-jitter, +jitter))`. `0.0` ⇒ deterministic timing. |
| `seed` | long | any | system nanos | Seed for the jitter PRNG. Pin to a fixed value for reproducible tests. |

**Implementation contract:** the LLM codec expands a streaming `Completion` into a `List<SseEvent>` where each event's `Delay` is pre-computed using the formulas above before the list is handed to `HttpSseResponseActionHandler` (verified to honour per-event delay at `HttpSseResponseActionHandler.java:68`). No changes to the SSE handler are required.

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

**`httpLlmResponse` is a first-class action type.** Implementation:
- New `Action.Type.LLM_RESPONSE` enum value in `mockserver/mockserver-core/src/main/java/org/mockserver/model/Action.java`.
- New `HttpLlmResponse` model class in `org.mockserver.model` (sibling to `HttpSseResponse`).
- New `httpLlmResponse` field on `Expectation`, `ExpectationDTO`, and JSON Schema.
- New `HttpLlmResponseActionHandler` in `org.mockserver.mock.action.http` that delegates to the codec and reuses `HttpSseResponseActionHandler` for the streaming path.
- Dashboard renders an "LLM Response" badge with provider + model + text preview in the expectation row.

The persisted form preserves intent so `verify_tool_call` (Tier 1 #3) and `explain_agent_run` (Tier 1 #4) can introspect provider/model/tool-call structure without re-parsing SSE.

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

### 2.6 Provider scope and resolved questions

- **Provider coverage at launch: all 7 from §2.3.** ANTHROPIC, OPENAI (Chat Completions), OPENAI_RESPONSES, GEMINI, BEDROCK, AZURE_OPENAI, OLLAMA. Implementation phasing within RFC-1 — Phase 1: ANTHROPIC + OPENAI; Phase 2: OPENAI_RESPONSES (separate `Completion` variant since `output`/`reasoning` blocks differ materially from Chat Completions); Phase 3: GEMINI, BEDROCK, AZURE_OPENAI, OLLAMA. AZURE_OPENAI is mostly an OpenAI codec with header/path differences. All phases ship before declaring RFC-1 "done."
- **Provider version pinning:** each codec targets a single API version at launch, declared in `ProviderCodec.apiVersion()`. Anthropic: `2024-10-22`. OpenAI Chat Completions: 2025 stable. Mismatched `anthropic-version` headers on inbound requests log a warning but do not fail matching; outbound responses are emitted in the codec's pinned version.
- **Token-count auto-estimation:** `usage` stays **explicit by default**. Optional `.withEstimatedUsage()` enables a heuristic estimator (≈ `text.length / 4`, no tokenizer dependency in core). A real tokenizer integration is out of scope.
- **Multi-modal content (images, audio, documents):** **out of scope for RFC-1.** `Completion` carries text + tool calls + usage only. Multi-modal is tracked as a Tier 3 follow-up.
- **Tool definitions and system prompts:** matchers in §3.3 operate on the `messages` array. `whenSystemPromptContains(...)` and `whenToolDefined(...)` are deferred to RFC-2 Layer C (post-Layer-B).

### 2.7 Failure modes

| Failure | When | Behaviour |
|---|---|---|
| Unsupported `provider` value | At expectation registration (`PUT /mockserver/expectation`) | `400 Bad Request` with body `{"error": "unsupported LLM provider: <name>", "supported": [...]}`. Fail-fast — never registered. |
| Provider not yet implemented in this phase | At registration | Same as above. Registry reports only providers with shipped codecs. |
| `toolCalls[].arguments` is malformed JSON when declared as object | At registration | `400` with field path. String form is always allowed. |
| Negative or out-of-range `usage` (`inputTokens < 0`, `> Integer.MAX_VALUE`) | At registration | `400` with field path. |
| `tokensPerSecond` outside `[1, 10_000]` or `jitter` outside `[0.0, 1.0]` | At registration | `400` with field path. Prevents division-by-zero in the per-event delay formula and contains unbounded jitter. |
| Streaming client disconnects mid-stream | Runtime | Existing SSE handler closes the channel cleanly (verified in `HttpSseResponseActionHandler`); no new code. Pending `SseEvent` delays are cancelled. |
| Codec internal failure during encode (defensive) | Runtime | Return `502 Bad Gateway` with `{"error": "llm codec encode failed", "provider": "..."}`; log full stack at WARN. Action is not retried. |
| Conversation request body fails to parse (RFC-2 §3.3) | Runtime, during matching | Treat as **no match** (fail-closed). Log at DEBUG with truncated body sample. Subsequent expectations are tried normally. |
| Conversation request body exceeds `mockserver.maxLlmConversationBodySize` (default `1048576` bytes = 1 MiB) | Runtime, during matching | Treat as **no match**, log at INFO. Prevents DoS via crafted huge JSON. |
| `whenLatestMessageContains(...)` regex throws | At registration | `400` with regex compile error. |
| `isolateBy(...)` attribute missing on inbound request | Runtime | Falls back to the shared scenario key (same as not declaring `isolateBy`). Log at DEBUG. |

All `400`s are deterministic — clients can rely on early validation rather than runtime failure.

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

The matcher operates on the parsed `messages` array of the inbound request body. Parsing is done by the same Java `ProviderCodec.decode()` introduced for RFC-1 — there is no separate parser.

**Provider detection — inherited from the expectation.** The provider is taken from the `llmMock(...).withProvider(...)` setter on the enclosing expectation. The matcher does **not** sniff the request body or path to guess the provider. If the inbound request does not conform to the declared provider's schema, the codec's `decode()` returns an empty `ParsedConversation` and the matcher treats this as **no match** (fail-closed) — the next expectation is tried. This means one expectation per `(path, provider)` combination is the normal pattern; multi-provider endpoints register multiple expectations.

**Matcher predicates (operate on the parsed `messages` array):**

- `whenTurnIndex(n)` — match the n-th model call in the conversation. Composes with the scenario state machine (AND).
- `whenLatestMessageContains(text|regex)` — substring/regex match against the *serialised string content* of the most recent message's text blocks (Anthropic: concatenated `content[].text`; OpenAI: `messages[-1].content` string or concatenated content parts).
- `whenLatestMessageRole(USER|TOOL|ASSISTANT)` — branch on the most recent message's `role`.
- `whenContainsToolResultFor("search")` — match when the inbound history contains a `tool_result` block (Anthropic) or `tool` role message (OpenAI) whose `tool_use_id` / `tool_call_id` refers to a prior call of the named tool.

All predicates compose with AND. The scenario state machine acts as an additional AND-gate: each `turn()` block implicitly sets `scenarioState`/`newScenarioState` to auto-generated values of the form `__llm_conv_<expectationId>_turn_<n>`. Users who need explicit state names can drop down to Layer A directly.

**Parse failure semantics:** if the request body is not valid JSON, exceeds the configured body-size limit, or does not satisfy the declared provider's schema, the matcher treats the predicate as failing. Errors are logged at DEBUG with a 256-byte body sample. Matching never throws.

**Concurrency / isolation — `isolateBy(...)`.** By default, two agents calling the same mocked endpoint share scenario state (current `ScenarioManager` behaviour, keyed on scenario name alone — verified in `ScenarioManager.java:9,37`). To isolate per-agent or per-session conversations, declare an isolation key:

```java
llmMock("/v1/messages")
    .withProvider(ANTHROPIC)
    .conversation()
        .isolateBy(header("x-session-id"))            // or: queryParameter("agent"), cookie("sid")
        .turn()…
```

This extends `ScenarioManager` with a composite key `(scenarioName, isolationValue)` while remaining backward-compatible (no `isolateBy` ⇒ old single-string key). When the configured attribute is absent on an inbound request, the matcher falls back to the shared key (no-isolation fallback — see §2.7) so a missing header degrades gracefully rather than failing match.

**Body-size limit:** matchers consult the new `mockserver.maxLlmConversationBodySize` configuration property. The value is an **integer number of bytes** (consistent with other MockServer size properties); default `1048576` (1 MiB); accepted range `[16384, 67108864]` (16 KiB – 64 MiB, values outside the range are clamped at startup). Env-var form: `MOCKSERVER_MAX_LLM_CONVERSATION_BODY_SIZE`. Bodies larger than this skip conversation-aware parsing entirely and are treated as no-match.

**Atomic state transitions:** the composite-keyed `ScenarioManager` implementation MUST preserve the existing per-key atomic read-modify-write contract (the current code uses `ConcurrentHashMap.compute()` at `ScenarioManager.java:37`). A coarser global lock is not acceptable — it would serialise all concurrent conversations.

**Lifecycle / clear semantics:** `ScenarioManager.reset()` clears all composite-keyed state. `ScenarioManager.clear(scenarioName)` clears **every isolation variant** for the given scenario name. The existing REST `PUT /mockserver/reset` and `PUT /mockserver/clear` endpoints retain their current contracts. The `clear_expectations` MCP tool also clears the associated scenario state for any LLM expectations it removes.

**Operability note:** because parse failures (wrong-provider body, malformed JSON) produce a no-match, an LLM expectation that is the only registered match for a path will return `404` to the caller. Users debugging unexpected 404s should enable DEBUG logging on `org.mockserver.matchers.LlmConversationMatcher`. Parseable JSON that fails the provider-schema check is logged at INFO (not DEBUG) because it is almost always a user configuration error worth surfacing.

### 3.4 MCP tool

```
create_llm_conversation (High)
  provider     string  required
  path         string  required
  isolateBy    object  optional   { source: "header"|"queryParameter"|"cookie", name: string }
  turns        array   required   ordered list of {
                                     match:    { turnIndex?, latestMessageContains?, latestMessageRole?, containsToolResultFor? },
                                     response: { text?, toolCalls?, stopReason?, usage?, streaming?, streamingPhysics? }
                                   }
```

Notes:
- `provider` is inherited by every `match` predicate in `turns`; no per-turn provider override at launch.
- `isolateBy` is optional; omitting it preserves the legacy shared-scenario behaviour.
- The MCP tool returns the generated scenario name and per-turn scenario state values so an agent can subsequently call `clear_scenario` or `reset_scenario` if it exists, or `clear` with appropriate filter.

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

---

## 7. Affected Artifacts Inventory

Enumerates every file/area that must change for RFC-1 + RFC-2 Layer B. Modelled on the existing `httpSseResponse` implementation as a baseline — anything that pattern touches, the LLM action type must touch too.

### 7.1 Java core — new files

| Path | Purpose |
|---|---|
| `mockserver/mockserver-core/src/main/java/org/mockserver/model/HttpLlmResponse.java` | Action payload — provider, model, completion, streaming flag, streamingPhysics |
| `mockserver/mockserver-core/src/main/java/org/mockserver/model/Completion.java` | Provider-neutral completion abstraction (text, toolCalls, stopReason, usage) |
| `mockserver/mockserver-core/src/main/java/org/mockserver/model/ToolUse.java` | Tool-call sub-model (name, arguments) |
| `mockserver/mockserver-core/src/main/java/org/mockserver/model/EmbeddingResponse.java` | Embedding payload (dimensions, deterministicFromInput, seed) |
| `mockserver/mockserver-core/src/main/java/org/mockserver/model/StreamingPhysics.java` | timeToFirstToken, tokensPerSecond, jitter, seed |
| `mockserver/mockserver-core/src/main/java/org/mockserver/llm/ProviderCodec.java` | Interface — `encode`, `decode`, `apiVersion`, `provider` |
| `mockserver/mockserver-core/src/main/java/org/mockserver/llm/ProviderCodecRegistry.java` | Registry + lookup by `Provider` enum |
| `mockserver/mockserver-core/src/main/java/org/mockserver/llm/ParsedConversation.java` | Decoder output — list of `ParsedMessage` (role, contentBlocks, toolCalls, toolResults) |
| `mockserver/mockserver-core/src/main/java/org/mockserver/llm/codec/AnthropicCodec.java` | Phase 1 |
| `mockserver/mockserver-core/src/main/java/org/mockserver/llm/codec/OpenAiChatCompletionsCodec.java` | Phase 1 |
| `mockserver/mockserver-core/src/main/java/org/mockserver/llm/codec/OpenAiResponsesCodec.java` | Phase 2 |
| `mockserver/mockserver-core/src/main/java/org/mockserver/llm/codec/GeminiCodec.java` | Phase 3 |
| `mockserver/mockserver-core/src/main/java/org/mockserver/llm/codec/BedrockCodec.java` | Phase 3 |
| `mockserver/mockserver-core/src/main/java/org/mockserver/llm/codec/AzureOpenAiCodec.java` | Phase 3 (mostly delegates to OpenAI codec) |
| `mockserver/mockserver-core/src/main/java/org/mockserver/llm/codec/OllamaCodec.java` | Phase 3 |
| `mockserver/mockserver-core/src/main/java/org/mockserver/mock/action/http/HttpLlmResponseActionHandler.java` | Dispatch — encode via codec, delegate streaming to `HttpSseResponseActionHandler` |
| `mockserver/mockserver-core/src/main/java/org/mockserver/serialization/model/HttpLlmResponseDTO.java` | DTO |
| `mockserver/mockserver-core/src/main/java/org/mockserver/serialization/serializers/response/HttpLlmResponseSerializer.java` | Jackson serializer |
| `mockserver/mockserver-core/src/main/java/org/mockserver/serialization/deserializers/response/HttpLlmResponseDeserializer.java` | Jackson deserializer |
| `mockserver/mockserver-core/src/main/java/org/mockserver/matchers/LlmConversationMatcher.java` | Predicate evaluation against `ParsedConversation` |

### 7.2 Java core — modified files

| Path | Change |
|---|---|
| `mockserver/mockserver-core/src/main/java/org/mockserver/model/Action.java` | Add `LLM_RESPONSE` to `Type` enum (currently 16 values) |
| `mockserver/mockserver-core/src/main/java/org/mockserver/mock/Expectation.java` | Add `httpLlmResponse` field + fluent setters/getters; include in `equals`/`hashCode` (~16 action fields today, see line 901) |
| `mockserver/mockserver-core/src/main/java/org/mockserver/serialization/model/ExpectationDTO.java` | Add corresponding DTO field |
| `mockserver/mockserver-core/src/main/java/org/mockserver/mock/ScenarioManager.java` | Composite key support `(scenarioName, isolationValue)`; backward-compatible when isolation null |
| `mockserver/mockserver-core/src/main/java/org/mockserver/configuration/ConfigurationProperties.java` | New property `mockserver.maxLlmConversationBodySize` (default 1 MiB) |
| `mockserver/mockserver-core/src/main/java/org/mockserver/configuration/Configuration.java` | Fluent setter for the above |
| `mockserver/mockserver-core/src/main/java/org/mockserver/matchers/HttpRequestPropertiesMatcher.java` | Wire `LlmConversationMatcher` into match pipeline when the expectation declares LLM matchers |
| `mockserver/mockserver-core/src/main/java/org/mockserver/mock/HttpState.java` | Register the new action with the expectation lifecycle |
| `mockserver/mockserver-core/src/main/java/org/mockserver/mock/action/http/HttpActionHandler.java` | Add the dispatch branch from `Action.Type.LLM_RESPONSE` to `HttpLlmResponseActionHandler` |
| `mockserver/mockserver-core/src/main/resources/org/mockserver/model/schema/expectations.json` | Add `httpLlmResponse` to the expectation schema |
| `mockserver/mockserver-core/src/main/resources/org/mockserver/model/schema/httpLlmResponse.json` | **New** — JSON Schema definition for the new action payload (sibling to `httpResponse.json`) |

### 7.3 Java client

| Path | Change |
|---|---|
| `mockserver/mockserver-client-java/src/main/java/org/mockserver/client/LlmMockBuilder.java` | **New** — fluent builder mirroring `McpMockBuilder`/`A2aMockBuilder` |
| `mockserver/mockserver-client-java/src/main/java/org/mockserver/client/LlmConversationBuilder.java` | **New** — `turn()` chain |
| `mockserver/mockserver-client-java/src/main/java/org/mockserver/client/MockServerClient.java` | Wire up the LLM expectation flow |

### 7.4 MCP control plane

| Path | Change |
|---|---|
| `mockserver/mockserver-netty/src/main/java/org/mockserver/netty/mcp/McpToolRegistry.java` | **+2 tools** delivered in two phases: `mock_llm_completion` lands with M1 (tool count 22 → 23, depends on the response builder); `create_llm_conversation` lands with M2 (tool count 23 → 24, depends on conversation matchers). See impl-plan T30/T31 |

### 7.5 Dashboard UI

| Path | Change |
|---|---|
| `mockserver-ui/src/components/ExpectationRow.tsx` (or equivalent) | Render "LLM Response" badge with provider + model + text preview |
| `mockserver-ui/src/components/ConversationView.tsx` | Extend to render expectation-side conversation scripts alongside captured traffic |
| `mockserver-ui/src/lib/llmTraffic.ts` | No change required at launch; convergence with Java decoder is a follow-up |

### 7.6 Non-Java client libraries

REST is the primary cross-language contract. The JSON form in §2.4 is reachable via the existing `PUT /mockserver/expectation` endpoint with no transport changes. Native helpers are scheduled per language:

| Path | Change |
|---|---|
| `mockserver-client-python/` | Add `llm_mock()` helper — phase 2 (post Java client) |
| `mockserver-client-ruby/` | Add `llm_mock` helper — phase 2 |
| `mockserver-client-typescript/` | Add `llmMock()` helper — phase 2 |

### 7.7 Consumer documentation

| Path | Change |
|---|---|
| `jekyll-www.mock-server.com/mock_server/_includes/creating_expectations.html` | New "LLM Response" subsection; Stateful Scenarios extended with isolation example |
| `jekyll-www.mock-server.com/mock_server/configuration_properties.html` | Add `maxLlmConversationBodySize` (with `MOCKSERVER_MAX_LLM_CONVERSATION_BODY_SIZE` env-var equivalent) |
| `jekyll-www.mock-server.com/mock_server/_includes/performance_configuration.html` | Note conversation matcher cost vs body-size limit |
| `jekyll-www.mock-server.com/mock_server/ai_mcp_tools.html` | Add `mock_llm_completion` and `create_llm_conversation` entries with their parameter tables |
| `jekyll-www.mock-server.com/mock_server/_includes/running_docker_container.html` | Add `MOCKSERVER_MAX_LLM_CONVERSATION_BODY_SIZE` example if env-var docs are not auto-generated |

### 7.8 Internal documentation

| Path | Change |
|---|---|
| `docs/code/request-processing.md` | New "LLM action" section in dispatch flow |
| `docs/code/domain-model.md` | Add `HttpLlmResponse`, `Completion`, codec model |
| `docs/code/event-system.md` | Note that LLM expectations participate in standard event logging |
| `docs/README.md` | Index updates |

### 7.9 Tests

| Area | Coverage |
|---|---|
| `ProviderCodec` per provider | Wire-level golden-file tests against real captured Anthropic/OpenAI responses to prevent format drift |
| `HttpLlmResponseActionHandler` | Streaming and non-streaming dispatch; failure modes from §2.7 |
| `LlmConversationMatcher` | Each predicate, AND composition, parse-failure fail-closed, body-size limit, isolation by header/cookie/query |
| `ScenarioManager` composite key | Backward compatibility (null isolation), concurrent multi-agent test |
| End-to-end | Full agent loop — tool_use → tool_result → final answer — across all matchers |

### 7.10 Convergence follow-up (not RFC-1 scope)

Track separately: port `mockserver-ui/src/lib/llmTraffic.ts` to either (a) call a new `POST /api/llm/parse` endpoint backed by the Java `ProviderCodec`, or (b) generate both TS and Java parsers from a shared JSON Schema. Choosing (a) is simpler; (b) is purer. Decide once both sides are stable.
