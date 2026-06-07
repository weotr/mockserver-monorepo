# LLM Mid-Stream Truncation

## What it demonstrates

Injects **LLM-specific chaos** on MockServer's `httpLlmResponse` action, testing AI agent resilience against real-world LLM provider failure modes:

1. **Mid-stream truncation** -- the SSE event stream is cut off after 70% of events, simulating a provider outage during generation
2. **Malformed SSE** -- a broken-JSON SSE chunk is injected, testing client-side SSE parser error handling
3. **Probabilistic provider errors** -- 429/529/500 status codes with `Retry-After`, simulating rate limiting
4. **Stateful quota** -- a fixed-window rate limit for LLM API calls

Key fields in `httpLlmResponse.chaos` (`LlmChaosProfile`):
- `truncateMode` -- `"MID_STREAM"` to truncate the SSE stream, or `"NONE"` (default)
- `truncateAtFraction` -- fraction (0.0-1.0) of SSE events to emit before truncating (default: random)
- `malformedSse` -- inject a malformed (broken-JSON) SSE chunk
- `errorStatus` -- HTTP status to inject (e.g. 429, 529, 500)
- `errorProbability` -- probability (0.0-1.0) of injecting the error
- `retryAfter` -- value for the `Retry-After` header on errors
- `quotaName` / `quotaLimit` / `quotaWindowMillis` / `quotaErrorStatus` -- stateful rate limiting
- `seed` -- fixed seed for reproducible error draws

## Prerequisites

- A running MockServer instance
- `curl`

## Run

```bash
./run.sh
```

## Expected output

The streaming response starts normally but abruptly stops at ~70% of the expected content, with a malformed JSON chunk injected:

```
==> Sending streaming request (expect truncated SSE stream with a malformed chunk)...
data: {"id":"chatcmpl-...","choices":[{"delta":{"content":"Chaos "}}]}

data: {"id":"chatcmpl-...","choices":[{"delta":{"content":"engineering "}}]}

data: {MALFORMED_JSON_CHUNK

data: {"id":"chatcmpl-...","choices":[{"delta":{"content":"is "}}]}

<stream ends abruptly>
```
