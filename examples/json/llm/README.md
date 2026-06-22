# LLM Response Mocking Examples

JSON expectation payloads that mock LLM provider APIs (chat completions, embeddings,
rerank) using the `httpLlmResponse` action. MockServer synthesises a provider-shaped
response (Anthropic, OpenAI, Cohere, etc.) so client SDKs see a realistic reply without
calling a real model.

| File | Description |
|------|-------------|
| `chat_completion.json` | Mock a chat/text completion (`completion`) |
| `embeddings.json` | Mock an embeddings response (`embedding`) |
| `rerank.json` | Mock a rerank response (`rerank`) |

## What it demonstrates

- The `httpLlmResponse` expectation action selects a `provider` and `model`, then carries
  exactly one of `completion`, `embedding`, or `rerank`.
- A `completion` returns `text`, a `stopReason`, and a `usage` token count.
- An `embedding` returns a deterministic vector of `dimensions` length (`deterministicFromInput`
  + `seed` make the vector reproducible from the request text).
- A `rerank` returns the `topN` reordered documents (also deterministic from input).

## Prerequisites

- A running MockServer instance (default `http://localhost:1080`).
- A client that calls the matched provider path (e.g. `POST /v1/messages` for Anthropic,
  `POST /v1/embeddings` for OpenAI, `POST /v1/rerank` for Cohere). Point the SDK's base URL
  at MockServer.

## Run

```bash
curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
  -d @examples/json/llm/chat_completion.json
```

Then call the mocked endpoint:

```bash
curl -X POST "${MOCKSERVER_URL:-http://localhost:1080}/v1/messages" \
  -H 'Content-Type: application/json' \
  -d '{"model":"claude-sonnet-4-20250514","messages":[{"role":"user","content":"hi"}]}'
```

## Expected output

- `chat_completion.json` — a provider-shaped chat completion whose assistant text is
  `Hello, world!` with `inputTokens=10` / `outputTokens=25` in the usage block.
- `embeddings.json` — an embeddings payload containing a reproducible 1536-dimension vector.
- `rerank.json` — a rerank payload returning the three highest-scoring documents.
