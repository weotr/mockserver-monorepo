#!/usr/bin/env bash
# Inject a deterministic 503 Service Unavailable with a Retry-After header.
# errorProbability=1.0 means every request gets the error (deterministic).
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).
set -euo pipefail

BASE="${MOCKSERVER_URL:-http://localhost:1080}"

echo "==> Creating expectation with 503 error + Retry-After chaos..."
curl -s -X PUT "${BASE}/mockserver/expectation" \
  -d '{
  "httpRequest": {
    "method": "GET",
    "path": "/api/payments"
  },
  "httpResponse": {
    "statusCode": 200,
    "body": "{\"status\": \"ok\"}"
  },
  "chaos": {
    "errorStatus": 503,
    "retryAfter": "30",
    "errorProbability": 1.0
  }
}' | python3 -m json.tool 2>/dev/null || true

echo ""
echo "==> Sending test request (expect 503 with Retry-After: 30)..."
curl -s -i "${BASE}/api/payments" 2>/dev/null | head -20
