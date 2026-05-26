# LLM Codec Golden-File Testing

This document describes the golden-file refresh process for LLM provider codecs.
Golden files capture real provider API responses and are diffed against the
MockServer codec's encode output to detect format drift.

## Where fixtures live

```
mockserver-core/src/test/resources/llm/fixtures/<provider>/
```

One subdirectory per provider: `anthropic`, `openai`, `openai-responses`,
`gemini`, `bedrock`, `azure-openai`, `ollama`.

Each directory will contain:

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

Currently this is a manual JSON-diff step. Automated diff detection is
planned as a Tier 3 follow-up (drift detection).

1. Run the codec's `encode()` or `encodeStreaming()` for a matching
   `Completion` object and capture the JSON output.
2. Use a JSON-diff tool (e.g., `jq`, `diff`, or `json-diff`) to compare
   the codec output against the captured fixture.
3. Focus on structural fields (presence/absence of keys, value types,
   nesting). Ignore volatile fields (see below).

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

Fixtures should be refreshed every 3-6 months, or when a provider
announces a breaking API change. The Anthropic `anthropic-version` header
and OpenAI API date headers pin the expected format version.
