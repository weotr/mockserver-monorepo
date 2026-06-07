#!/usr/bin/env bash
# Inject probabilistic connection drops with a fixed seed for reproducibility.
# dropConnectionProbability=0.5 means ~50% of requests will have the TCP
# connection dropped before any response is sent.
# The fixed seed (42) ensures the same draw on every request -- making the
# drop decision deterministic and reproducible across test runs.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).
set -euo pipefail

BASE="${MOCKSERVER_URL:-http://localhost:1080}"

echo "==> Creating expectation with seeded connection-drop chaos..."
curl -s -X PUT "${BASE}/mockserver/expectation" \
  -d '{
  "httpRequest": {
    "method": "GET",
    "path": "/api/health"
  },
  "httpResponse": {
    "statusCode": 200,
    "body": "{\"healthy\": true}"
  },
  "chaos": {
    "dropConnectionProbability": 0.5,
    "seed": 42
  }
}' | python3 -m json.tool 2>/dev/null || true

echo ""
echo "==> Sending test request (with seed=42, the drop decision is the same every time)..."
curl -s -w "\nHTTP %{http_code}\n" "${BASE}/api/health" 2>&1 || echo "(connection was dropped)"

echo ""
echo "==> Sending another request (same seed = same result)..."
curl -s -w "\nHTTP %{http_code}\n" "${BASE}/api/health" 2>&1 || echo "(connection was dropped)"
