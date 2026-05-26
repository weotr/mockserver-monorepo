# LLM Codec Golden-File Testing

This document describes the golden-file refresh process for LLM provider codecs.
Golden files capture real provider API responses and are diffed against the
MockServer codec's encode output to detect format drift.

> **Current state (M5):** the fixture directories below ship with `.gitkeep`
> placeholders only — no captured responses are committed yet. Capturing them
> requires real API keys for each of the seven providers, which is deliberately
> deferred until those credentials are available in an isolated environment.
> The codec unit tests already verify wire-shape correctness via Jackson
> structural assertions, so the absence of fixtures does not weaken the
> existing test guarantees; the fixtures are an additional drift-detection
> layer planned for the next iteration. Until they land, treat per-provider
> wire-format changes as a manual review concern when bumping codec versions.

## Where fixtures live

```
mockserver/mockserver-core/src/test/resources/llm/fixtures/<provider>/
```

One subdirectory per provider: `anthropic`, `openai`, `openai-responses`,
`gemini`, `bedrock`, `azure-openai`, `ollama`.

Each directory contains:

- `text-completion.json` -- a simple text completion response
- `tool-call.json` -- a response containing a tool call / function call
- `streaming-text.jsonl` -- the full SSE event stream for a streaming text completion (one JSON object per line, prefixed with the SSE event type)
- `streaming-tool-call.jsonl` -- the full SSE event stream for a streaming tool-call response
- `embeddings.json` -- (OpenAI only) an embeddings API response

## How to capture a fresh fixture

Use `curl` (or the provider's CLI) to make a real API call and save the
response. Examples:

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
  }' | jq . > mockserver-core/src/test/resources/llm/fixtures/anthropic/text-completion.json
```

### OpenAI Chat Completions

```bash
curl -s https://api.openai.com/v1/chat/completions \
  -H "Authorization: Bearer $OPENAI_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-4o",
    "messages": [{"role": "user", "content": "Say hello in one sentence."}]
  }' | jq . > mockserver-core/src/test/resources/llm/fixtures/openai/text-completion.json
```

### Streaming

For streaming responses, use `curl --no-buffer` and capture the raw SSE
output:

```bash
curl -sN https://api.anthropic.com/v1/messages \
  -H "x-api-key: $ANTHROPIC_API_KEY" \
  -H "anthropic-version: 2024-10-22" \
  -H "content-type: application/json" \
  -d '{
    "model": "claude-sonnet-4-20250514",
    "max_tokens": 256,
    "stream": true,
    "messages": [{"role": "user", "content": "Say hello in one sentence."}]
  }' > mockserver-core/src/test/resources/llm/fixtures/anthropic/streaming-text.jsonl
```

## How to diff against the codec output

This is a manual JSON-diff step. Automated drift detection is planned as a
follow-up.

1. Run the codec unit test that calls `encode()` or `encodeStreaming()` for
   the provider under test and capture the JSON output. The test classes live
   at:
   ```
   mockserver/mockserver-core/src/test/java/org/mockserver/llm/codec/<ProviderCodec>Test.java
   ```
2. Write the test output to a scratch file, for example:
   ```bash
   # run one test class and capture stdout
   mvn -pl mockserver/mockserver-core test \
     -Dtest="AnthropicCodecTest#testEncodeTextCompletion" \
     -Dsurefire.failIfNoSpecifiedTests=false 2>&1 \
     | grep '^{' > .tmp/anthropic-encode-output.json
   ```
3. Use a JSON-diff tool to compare the codec output against the captured
   fixture. Ignore the volatile fields listed below:
   ```bash
   jq 'del(.id, .created, .usage, .system_fingerprint)' \
     mockserver/mockserver-core/src/test/resources/llm/fixtures/anthropic/text-completion.json \
     > .tmp/anthropic-fixture-normalised.json
   # repeat for the codec output, then diff
   diff .tmp/anthropic-fixture-normalised.json .tmp/anthropic-encode-normalised.json
   ```
4. Focus on structural fields: presence/absence of keys, value types, and
   nesting depth. Exact token counts, IDs, and timestamps are expected to
   differ.

## Fields expected to differ (normalisation strategy)

The following fields vary between runs and should be normalised or
ignored when diffing:

| Field | Provider(s) | Strategy |
|-------|-------------|----------|
| `id` / `msg_*` / `chatcmpl-*` / `resp_*` | All | Ignore -- random ID per response |
| `created` / `created_at` | All | Ignore -- timestamp |
| `usage.*` | All | Compare structure only, not exact values |
| `model` | All | May differ in minor version suffix |
| `system_fingerprint` | OpenAI | Ignore -- server-side build ID |

## Refresh cadence

Refresh fixtures every 3-6 months, or immediately when a provider announces
a breaking API change. The Anthropic `anthropic-version` header and OpenAI
API date headers pin the format version; check those headers are still
current when refreshing.

After capturing new fixtures, run the full codec test suite to confirm no
structural regressions:

```bash
mvn -pl mockserver/mockserver-core test \
  -Dtest="*CodecTest" \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Commit the updated fixture files alongside any codec changes that motivated
the refresh so reviewers can diff fixture content directly.
