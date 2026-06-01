#!/usr/bin/env bash
# Demonstrate LLM chaos: mid-stream truncation of an SSE streaming response.
# The LLM response streams 70% of SSE events, then abruptly stops,
# simulating a provider outage mid-generation.
# Also injects a malformed SSE chunk to test client parsing resilience.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).
set -euo pipefail

BASE="${MOCKSERVER_URL:-http://localhost:1080}"

echo "==> Creating LLM expectation with mid-stream truncation + malformed SSE chaos..."
curl -s -X PUT "${BASE}/mockserver/expectation" \
  -d '{
  "httpRequest": {
    "method": "POST",
    "path": "/v1/chat/completions"
  },
  "httpLlmResponse": {
    "provider": "OPENAI",
    "completion": {
      "model": "gpt-4",
      "messages": [
        {"role": "system", "content": "You are a helpful assistant."},
        {"role": "user", "content": "Tell me about chaos engineering."}
      ],
      "responseBody": "Chaos engineering is the discipline of experimenting on a system in order to build confidence in the system'\''s capability to withstand turbulent conditions in production."
    },
    "chaos": {
      "truncateMode": "MID_STREAM",
      "truncateAtFraction": 0.7,
      "malformedSse": true
    }
  }
}' | python3 -m json.tool 2>/dev/null || true

echo ""
echo "==> Sending streaming request (expect truncated SSE stream with a malformed chunk)..."
curl -s -N "${BASE}/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -d '{
  "model": "gpt-4",
  "messages": [{"role": "user", "content": "Tell me about chaos engineering."}],
  "stream": true
}'

echo ""
echo ""
echo "==> Creating LLM expectation with probabilistic 429 error + quota..."
curl -s -X PUT "${BASE}/mockserver/expectation" \
  -d '{
  "httpRequest": {
    "method": "POST",
    "path": "/v1/embeddings"
  },
  "httpLlmResponse": {
    "provider": "OPENAI",
    "embedding": {
      "model": "text-embedding-3-small",
      "input": "test"
    },
    "chaos": {
      "errorStatus": 429,
      "retryAfter": "60",
      "errorProbability": 1.0,
      "quotaName": "embedding-rate-limit",
      "quotaLimit": 5,
      "quotaWindowMillis": 60000,
      "quotaErrorStatus": 429
    }
  }
}' | python3 -m json.tool 2>/dev/null || true
