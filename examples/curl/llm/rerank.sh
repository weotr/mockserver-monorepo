#!/usr/bin/env bash
# Mock a rerank response using the httpLlmResponse action.
# MockServer scores the candidate documents from the request and returns the
# top N, descending by relevance score, in the provider's rerank wire format.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "method": "POST",
    "path": "/v1/rerank"
  },
  "httpLlmResponse": {
    "provider": "COHERE",
    "model": "rerank-english-v3.0",
    "rerank": {
      "topN": 3,
      "deterministicFromInput": true,
      "seed": 42
    }
  }
}'
