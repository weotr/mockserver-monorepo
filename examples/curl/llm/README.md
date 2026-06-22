# LLM Response Mocking (curl)

## What it demonstrates

Mocking LLM endpoints with the `httpLlmResponse` action via raw REST calls.
MockServer renders the canned payload into the provider's native wire format,
so an SDK pointed at MockServer gets a realistic response.

| Script | Description |
|--------|-------------|
| `chat_completion.sh` | OpenAI-style chat completion (provider, model, completion text, stop reason, token usage) |
| `embeddings.sh` | Embeddings response with vectors generated deterministically from the input |
| `rerank.sh` | Rerank response returning the top N candidates scored by relevance |

## Prerequisites

- A running MockServer instance (e.g. `docker run -d -p 1080:1080 mockserver/mockserver`)
- `curl` installed
- Optionally `export MOCKSERVER_URL=http://localhost:1080` (scripts default to this)

## Run

```bash
./chat_completion.sh
./embeddings.sh
./rerank.sh
```

## Expected output

Each script prints the created expectation as JSON, for example:

```json
{
  "id" : "...",
  "priority" : 0,
  "httpRequest" : { "method" : "POST", "path" : "/v1/chat/completions" },
  "httpLlmResponse" : { "provider" : "OPENAI", "model" : "gpt-4o", "completion" : { ... } },
  "times" : { "unlimited" : true }
}
```
