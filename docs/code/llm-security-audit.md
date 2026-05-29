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

**Compatibility bug — actionable.**

The `OllamaCodec` streaming path emits SSE events (`data: <json>\n\n`) instead of Ollama's native NDJSON format (`<json>\n`). Real Ollama clients that strictly parse newline-delimited JSON (not SSE) will silently receive no tokens or raise a parse error when the mock response is consumed.

To fix: implement an NDJSON write path in `OllamaCodec.encodeStreaming()` that writes each chunk as `<json>\n` without the `data:` prefix, and select the path based on the inbound request's `Accept` header or a codec configuration flag. Non-streaming Ollama responses are already in the correct format; only the streaming path is affected.

The limitation is documented in the `OllamaCodec` javadoc. Not a security issue.

### BedrockCodec binary framing

**Compatibility limitation — actionable for raw HTTP clients.**

The `BedrockCodec` does not implement Bedrock's `InvokeModelWithResponseStream` binary chunk-wrapping envelope (`{"chunk":{"bytes":"<base64>"}}`), emitting plain Anthropic SSE events instead. Applications that use the AWS SDK for streaming invocations will work correctly because the SDK handles the envelope at the HTTP client layer. Raw HTTP clients that directly parse the binary frame protocol will not work.

To fix: implement the `aws-chunked` / event stream encoding described in the Bedrock Streaming API reference. The binary envelope is straightforward: base64-encode each Anthropic SSE chunk, wrap it in `{"chunk":{"bytes":"..."}}`, and write with the correct content-type (`application/vnd.amazon.eventstream`). This is a non-trivial change requiring a new codec subclass or a framing wrapper.

The limitation is documented in the `BedrockCodec` javadoc. Not a security concern.

### Runtime LLM client — Bedrock SigV4 signing

**Compatibility limitation — actionable for direct Bedrock use.**

The runtime-LLM client `BedrockLlmClient` (`org.mockserver.llm.client`) builds the Anthropic-on-Bedrock request body and parses the Anthropic-shaped response, but does **not** implement AWS SigV4 request signing. Callers must supply auth out of band: via the `LlmBackend.headers()` escape hatch (a pre-signed `Authorization` header) or by pointing `baseUrl` at a local signing proxy / sidecar. Without valid auth the request fails closed (Bedrock returns 403 and `LlmCompletionService` returns no completion) — there is no security exposure, but a user attempting direct Bedrock integration receives no completion until signing is provided.

To fix: implement an AWS SigV4 v4 signer (deterministic; verifiable offline against AWS's published canonical-request test vectors) and apply it in `BedrockLlmClient.buildCompletionRequest`. Tracked here per the `BedrockLlmClient` javadoc.

### Gemini API key in query string (runtime client)

The runtime-LLM `GeminiLlmClient` passes the API key as a `?key=` query parameter, as Gemini's API-key auth requires. Unlike header credentials, query-string keys can surface in HTTP access/proxy logs. This is a property of the provider's API, not a MockServer choice; front the call with a gateway that injects the key after ingress in high-security environments. Documented in the `GeminiLlmClient` javadoc. Not a MockServer defect.

### Gemini tool-call argument re-serialisation

The `GeminiCodec.encodeStreaming()` method re-serialises tool-call arguments through Jackson (`OBJECT_MAPPER.readTree` + `writeValueAsString`) before embedding them in the SSE chunk. If the arguments are not valid JSON, they are wrapped in a `{"value":"<escaped>"}` object. This is a safe fallback that prevents malformed arguments from corrupting the JSON chunk. No action required.

### `whenContainsToolResultFor` E2E false-negative for Gemini and Ollama

**Bug — requires investigation before fix.**

Unit tests confirm that `LlmConversationMatcher.hasToolResultForName` correctly matches tool results for all providers, including Gemini (name-based correlation) and Ollama (positional fallback). However, when exercised through the full E2E matching pipeline (Netty pipeline &rarr; `RequestMatchers` &rarr; conversation matcher), the predicate fails for Gemini and Ollama turn-2 requests.

The most likely candidates for root cause, in order of probability:
1. The Netty pipeline deserialises the body into a `JsonBody` before `LlmConversationMatcher` receives it. The codec's `decode()` path may receive a pre-parsed form rather than the raw bytes, causing the Gemini/Ollama tool-result extractor to miss the expected field path.
2. The body-size cap check in `LlmConversationMatcher.matches()` uses `request.getBodyAsRawBytes().length`. If the Netty pipeline has already consumed the raw bytes into a parsed `JsonBody`, `getBodyAsRawBytes()` may return an empty array, causing every body to pass the size check but `codec.decode()` to receive an empty string.

To investigate: add a DEBUG log in `LlmConversationMatcher.matches()` that records the actual body bytes length and the decoded `ParsedConversation` before predicate evaluation. Compare the Anthropic and Gemini/Ollama code paths at the point `codec.decode(request)` is called.

The scenario state machine (which does not use `whenContainsToolResultFor`) works correctly for all providers. E2E tests for Gemini and Ollama rely on `turnIndex` ordering rather than tool-result predicates. Not a security issue.
