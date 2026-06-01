#!/usr/bin/env bash
# Demonstrate a named, stateful rate-limit quota.
# The quota allows 3 requests per 10-second window. The 4th request within
# the window gets a 429 Too Many Requests with a Retry-After header.
# Multiple expectations sharing the same quotaName share one counter.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).
set -euo pipefail

BASE="${MOCKSERVER_URL:-http://localhost:1080}"

echo "==> Creating expectation with named quota (3 req / 10s window)..."
curl -s -X PUT "${BASE}/mockserver/expectation" \
  -d '{
  "httpRequest": {
    "method": "GET",
    "path": "/api/search"
  },
  "httpResponse": {
    "statusCode": 200,
    "body": "{\"results\": [\"result-1\", \"result-2\"]}"
  },
  "chaos": {
    "quotaName": "search-api-limit",
    "quotaLimit": 3,
    "quotaWindowMillis": 10000,
    "quotaErrorStatus": 429,
    "retryAfter": "10"
  }
}' | python3 -m json.tool 2>/dev/null || true

echo ""
echo "==> Sending 5 requests (first 3 should succeed, last 2 should be rate-limited)..."
for i in 1 2 3 4 5; do
  echo -n "  Request $i: "
  curl -s -w "HTTP %{http_code}\n" -o /dev/null "${BASE}/api/search"
done

echo ""
echo "==> Wait 11 seconds for the window to reset, then try again..."
sleep 11
echo -n "  Request after reset: "
curl -s -w "HTTP %{http_code}\n" -o /dev/null "${BASE}/api/search"
