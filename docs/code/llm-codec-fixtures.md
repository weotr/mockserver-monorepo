# LLM Codec Golden-File Testing

This document describes the automated golden-file drift-detection process for
LLM provider codecs. Golden files are **codec-generated** (no API keys needed),
normalized to remove volatile fields, and committed to the repository. The test
`LlmCodecGoldenFileTest` asserts them on every test run to catch wire-format
drift in our codecs.

## How it works

1. **Fixed canonical inputs** -- the test encodes two fixed `Completion` objects
   (text-completion and tool-call) through each provider codec's `encode()` and
   `encodeStreaming()` methods.
2. **Normalization** -- volatile fields (IDs, timestamps, usage counts) are
   replaced with deterministic placeholders so goldens are stable across runs.
3. **Golden comparison** -- the normalized output is compared byte-for-byte
   against the committed golden file. Any mismatch fails the test with a
   line-by-line diff.
4. **Refresh switch** -- when system property `mockserver.updateLlmGoldens`
   (or env `MOCKSERVER_UPDATE_LLM_GOLDENS`) is `true`, the test writes the
   normalized output to the golden files instead of asserting, then passes.

## Where fixtures live

```
mockserver/mockserver-core/src/test/resources/llm/fixtures/<provider>/
```

One subdirectory per provider: `anthropic`, `openai`, `openai-responses`,
`gemini`, `bedrock`, `azure-openai`, `ollama`.

Each directory contains:

- `text-completion.json` -- normalized non-streaming encode of a text completion
- `tool-call.json` -- normalized non-streaming encode of a tool-call completion
- `streaming-text.jsonl` -- normalized streaming encode of a text completion (one event per line)
- `streaming-tool-call.jsonl` -- normalized streaming encode of a tool-call completion

## Regenerating golden files

After intentional codec changes, regenerate goldens:

```bash
mvn -pl mockserver/mockserver-core test \
  -Dtest=LlmCodecGoldenFileTest \
  -Dmockserver.updateLlmGoldens=true
```

Or set the environment variable:

```bash
MOCKSERVER_UPDATE_LLM_GOLDENS=true mvn -pl mockserver/mockserver-core test \
  -Dtest=LlmCodecGoldenFileTest
```

Then review the diff with `git diff` and commit the updated golden files
alongside the codec changes.

## Asserting golden files (normal test run)

```bash
mvn -pl mockserver/mockserver-core test \
  -Dtest=LlmCodecGoldenFileTest
```

The test also runs as part of the standard `mvn test` suite.

## Normalization rules

The following fields are replaced with deterministic placeholders before
writing/comparing:

| Field | Applies to | Placeholder |
|-------|-----------|-------------|
| `id`, `item_id`, `tool_call_id` | All providers | `"<id>"` |
| String values matching `chatcmpl-*`, `msg_*`, `resp_*`, `call_*`, `toolu_*`, `fc_*` | All providers | `"<id>"` |
| `created` (numeric) | OpenAI, Azure OpenAI, OpenAI Responses | `0` |
| `created_at` (numeric) | OpenAI Responses | `0` |
| `created_at` (ISO 8601 string) | Ollama | `"<timestamp>"` |
| `system_fingerprint` | OpenAI | `"<fp>"` |
| `usage.*`, `usageMetadata.*` (numeric values) | All providers | `0` (structure preserved) |
| `*_duration` fields | Ollama | `0` |
| `prompt_eval_count`, `eval_count`, `prompt_eval_duration`, `eval_duration` | Ollama | `0` |

## Streaming golden file format

Streaming goldens use JSONL (one entry per line):

- **SSE with event types** (Anthropic, OpenAI Responses): each line is a JSON
  object `{"event":"<type>","data":<normalized-json>}` capturing both the SSE
  event name and normalized payload.
- **SSE without event types** (OpenAI, Azure OpenAI, Gemini): each line is the
  normalized JSON payload directly. Non-JSON sentinels (e.g. `[DONE]`) are
  quoted as JSON strings.
- **NDJSON** (Ollama): each line is the normalized JSON chunk directly.
- **AWS_EVENT_STREAM** (Bedrock): uses the same SSE-with-events format as
  Anthropic (since Bedrock delegates to the Anthropic codec internally). The
  binary event-stream framing is not exercised in this test -- it is covered by
  `BedrockEventStreamEncoderTest`.

## Streaming physics

The test passes `null` for `StreamingPhysics` so no timing delays are injected.
This ensures the golden files capture only the wire-format content, not timing
behavior.

## Live provider capture (optional)

The golden-file test does NOT require API keys -- it tests our codec output.
For additional confidence, you can optionally diff against real provider
responses. The `curl` recipes below require real API keys:

### Anthropic

```bash
curl -s https://api.anthropic.com/v1/messages \
  -H "x-api-key: $ANTHROPIC_API_KEY" \
  -H "anthropic-version: 2024-10-22" \
  -H "content-type: application/json" \
  -d '{
    "model": "claude-sonnet-4-20250514",
    "max_tokens": 256,
    "messages": [{"role": "user", "content": "Say hello in one sentence."}]
  }' | jq .
```

### OpenAI Chat Completions

```bash
curl -s https://api.openai.com/v1/chat/completions \
  -H "Authorization: Bearer $OPENAI_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-4o",
    "messages": [{"role": "user", "content": "Say hello in one sentence."}]
  }' | jq .
```

Focus on structural fields when comparing live output: presence/absence of
keys, value types, nesting depth. Exact token counts, IDs, and timestamps
will differ.

## Refresh cadence

Regenerate goldens whenever codec encode logic changes. Also check when
providers announce breaking API changes. After regenerating, run the full
codec test suite to confirm no regressions:

```bash
mvn -pl mockserver/mockserver-core test \
  -Dtest="*CodecTest" \
  -Dsurefire.failIfNoSpecifiedTests=false
```
