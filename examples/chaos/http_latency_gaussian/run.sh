#!/usr/bin/env bash
# Inject Gaussian-distributed latency on a mocked response.
# The latency samples from a Gaussian distribution with mean=200ms, stdDev=50ms.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).
set -euo pipefail

BASE="${MOCKSERVER_URL:-http://localhost:1080}"

echo "==> Creating expectation with Gaussian latency chaos..."
curl -s -X PUT "${BASE}/mockserver/expectation" \
  -d '{
  "httpRequest": {
    "method": "GET",
    "path": "/api/inventory"
  },
  "httpResponse": {
    "statusCode": 200,
    "body": "{\"items\": [\"widget-a\", \"widget-b\"]}"
  },
  "chaos": {
    "latency": {
      "timeUnit": "MILLISECONDS",
      "value": 0,
      "distribution": {
        "type": "GAUSSIAN",
        "mean": 200,
        "stdDev": 50
      }
    }
  }
}' | python3 -m json.tool 2>/dev/null || true

echo ""
echo "==> Sending test request (expect ~200ms latency with Gaussian jitter)..."
time curl -s -o /dev/null -w "HTTP %{http_code} in %{time_total}s\n" "${BASE}/api/inventory"

echo ""
echo "==> Sending another request (latency varies each time)..."
time curl -s -o /dev/null -w "HTTP %{http_code} in %{time_total}s\n" "${BASE}/api/inventory"
