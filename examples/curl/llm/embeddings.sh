#!/usr/bin/env bash
# Mock an embeddings response using the httpLlmResponse action.
# Vectors are generated deterministically from the request input so the same
# input always yields the same embedding (useful for stable test assertions).
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "method": "POST",
    "path": "/v1/embeddings"
  },
  "httpLlmResponse": {
    "provider": "OPENAI",
    "model": "text-embedding-3-small",
    "embedding": {
      "dimensions": 1536,
      "deterministicFromInput": true,
      "seed": 42
    }
  }
}'
