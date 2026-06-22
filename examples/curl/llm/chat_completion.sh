#!/usr/bin/env bash
# Mock an OpenAI-style chat completion using the httpLlmResponse action.
# MockServer encodes the completion in the provider's wire format (here OpenAI).
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "method": "POST",
    "path": "/v1/chat/completions"
  },
  "httpLlmResponse": {
    "provider": "OPENAI",
    "model": "gpt-4o",
    "completion": {
      "text": "The capital of France is Paris.",
      "stopReason": "end_turn",
      "usage": {
        "inputTokens": 15,
        "outputTokens": 12
      }
    }
  }
}'
