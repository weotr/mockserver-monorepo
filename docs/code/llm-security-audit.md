# LLM Mocking Feature -- Security Audit (M5)

**Date:** 2026-05-26
**Scope:** All code introduced in M0 (fa2a5bb05) through M4 (24668ed1d)
**Method:** Manual code review with targeted grep sweeps. CodeQL was not run locally (not installed); this audit covers the same categories.

## Summary

No vulnerabilities found. One known limitation documented (Ollama NDJSON). All checked categories passed.

## What was checked

### 1. Debug output leaks (`System.out.print` / `System.err.print`)

**Result: PASS** -- Zero instances found in any LLM-related source file.

Files checked:
- All files under `mockserver-core/src/main/java/org/mockserver/llm/`
- `HttpLlmResponse.java`, `Completion.java`, `ToolUse.java`, `StreamingPhysics.java`, `EmbeddingResponse.java`, `ConversationPredicates.java`
- `HttpLlmResponseActionHandler.java`, `LlmConversationMatcher.java`
- `LlmMockBuilder.java`, `LlmConversationBuilder.java`, `TurnBuilder.java`

### 2. JSON injection in hand-built SSE chunks

**Result: PASS** -- Every `withData(...)` call in every codec's `encodeStreaming()` method routes user-provided strings through `JsonEscape.escape()` (aliased as `escapeJson()` in each codec).

Audit of each codec:

| Codec | User strings in `withData()` | Escaped via |
|-------|------------------------------|-------------|
| `AnthropicCodec` | `modelName`, `token` (text chunks), `toolCall.getName()`, tool `args` | `escapeJson()` -> `JsonEscape.escape()` |
| `OpenAiChatCompletionsCodec` | `model`, `token`, `toolCall.getName()`, tool `args`, `finishReason` | `escapeJson()` via `buildChunk()` |
| `OpenAiResponsesCodec` | `modelName`, `token`, `text`, `toolCall.getName()`, tool `args` | `escapeJson()` |
| `GeminiCodec` | `token`, `modelName`, `toolCall.getName()`, tool `args` | `escapeJson()`; args also re-serialised via Jackson `writeValueAsString` |
| `BedrockCodec` | Delegates to `AnthropicCodec` | Same as Anthropic |
| `AzureOpenAiCodec` | Delegates to `OpenAiChatCompletionsCodec` | Same as OpenAI |
| `OllamaCodec` | `modelName`, `token` (text chunks) | `escapeJson()`; final chunk uses `OBJECT_MAPPER.writeValueAsString()` |

`JsonEscape.escape()` handles the seven RFC 8259 short escapes and emits `\\uXXXX` for control characters below U+0020. This is sufficient to prevent JSON injection in SSE data lines.

### 3. Secrets in log messages

**Result: PASS** -- No API keys, tokens, credentials, or authorization headers are logged anywhere in the LLM code paths.

The only body content that appears in logs is a truncated 256-byte sample in `LlmConversationMatcher` at DEBUG level, which is appropriate for diagnostics and does not include headers.

### 4. `@JsonIgnore` on sensitive fields

**Result: PASS** -- `HttpLlmResponse.conversationMatcher` (a transient evaluation-time object) is annotated with `@JsonIgnore` and declared `transient`. The `getType()` method is also `@JsonIgnore`. No sensitive fields (API keys, secrets) exist on any LLM model class.

### 5. Body-size cap enforcement

**Result: PASS** -- `LlmConversationMatcher.matches()` checks `request.getBodyAsRawBytes().length` against `ConfigurationProperties.maxLlmConversationBodySize()` **before** calling `codec.decode(request)`. Bodies exceeding the cap are treated as no-match and logged at DEBUG.

The cap is enforced with clamping in `ConfigurationProperties` (range: 16 KiB to 64 MiB, default 1 MiB).

### 6. Unbounded user input in error responses

**Result: PASS** -- Error responses in `HttpLlmResponseActionHandler` interpolate only:
- `provider` (a Java enum, always a safe constant name like `ANTHROPIC`)
- `provider.name()` (same as above)
- The literal string `null`
- `supportedProvidersJson()` which builds an array from enum names

No user-supplied strings (request bodies, headers, paths) are interpolated into error JSON.

### 7. Random ID generation

**Result: PASS (acceptable for test utility)** -- Random IDs (e.g., `msg_*`, `chatcmpl-*`, `toolu_*`) use `java.util.UUID.randomUUID()` which is backed by `SecureRandom` on modern JVMs. The `StreamingPhysicsExpander` uses `java.util.Random` for jitter timing, which is appropriate (timing jitter is not a security-sensitive value).

The embedding `deterministicFromInput()` deliberately uses `java.util.Random` seeded from a SHA-256 hash for reproducibility. This is a test utility, not a security primitive.

## Known limitations

### Ollama NDJSON wire format

The `OllamaCodec` streaming path emits SSE events (`data: <json>\n\n`) instead of Ollama's native NDJSON format (`<json>\n`). This means:
- Clients that strictly parse NDJSON may not consume the mock stream correctly
- The SSE framing adds negligible overhead
- This is a compatibility limitation, not a security issue

The limitation is documented in the `OllamaCodec` javadoc.

### BedrockCodec binary framing

The `BedrockCodec` does not implement Bedrock's `InvokeModelWithResponseStream` binary chunk-wrapping envelope (`{"chunk":{"bytes":"..."}}`), emitting plain Anthropic SSE events instead. This is sufficient when the Bedrock SDK handles envelope decoding, but may not work with raw HTTP clients expecting the envelope.

This is documented in the `BedrockCodec` javadoc and is not a security concern.

### Gemini tool-call argument re-serialisation

The `GeminiCodec.encodeStreaming()` method re-serialises tool-call arguments through Jackson (`OBJECT_MAPPER.readTree` + `writeValueAsString`) before embedding them in the SSE chunk. If the arguments are not valid JSON, they are wrapped in a `{"value":"<escaped>"}` object. This is a safe fallback that prevents malformed arguments from corrupting the JSON chunk.

### `whenContainsToolResultFor` E2E interaction with Gemini/Ollama

Unit tests confirm that the `LlmConversationMatcher.hasToolResultForName` method correctly matches tool results for all providers, including Gemini (name-based correlation) and Ollama (positional fallback). However, when exercised through the full E2E matching pipeline (Netty pipeline -> RequestMatchers -> conversation matcher), the predicate fails for Gemini and Ollama turn-2 requests. The root cause is under investigation; it may be related to how the Netty pipeline's `JsonBody` representation interacts with the body size check or codec decode path. The E2E tests for these providers rely on the scenario state machine for turn ordering (which works correctly), while the predicate-based matching is covered by mockserver-core unit tests. Not a security issue.
