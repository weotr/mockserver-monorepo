#!/usr/bin/env bash
# Demonstrate a time-based outage window controlled by the MockServer clock.
#
# The outage window opens 5 seconds after the first match and lasts 10 seconds,
# after which the service self-heals. We use the controllable clock to drive the
# window deterministically without waiting in real time.
#
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).
set -euo pipefail

BASE="${MOCKSERVER_URL:-http://localhost:1080}"

echo "==> Step 1: Freeze the clock at a known instant..."
curl -s -X PUT "${BASE}/mockserver/clock" \
  -d '{"action": "freeze", "instant": "2025-01-01T00:00:00Z"}' \
  | python3 -m json.tool 2>/dev/null || true

echo ""
echo "==> Step 2: Create expectation with outage window (starts at +5s, lasts 10s)..."
curl -s -X PUT "${BASE}/mockserver/expectation" \
  -d '{
  "httpRequest": {
    "method": "GET",
    "path": "/api/catalog"
  },
  "httpResponse": {
    "statusCode": 200,
    "body": "{\"products\": [\"item-1\"]}"
  },
  "chaos": {
    "errorStatus": 503,
    "errorProbability": 1.0,
    "outageAfterMillis": 5000,
    "outageDurationMillis": 10000
  }
}' | python3 -m json.tool 2>/dev/null || true

echo ""
echo "==> Step 3: First request at t=0 (before outage window, expect 200)..."
curl -s -w " => HTTP %{http_code}\n" "${BASE}/api/catalog"

echo ""
echo "==> Step 4: Advance clock by 6 seconds (now inside the outage window)..."
curl -s -X PUT "${BASE}/mockserver/clock" \
  -d '{"action": "advance", "durationMillis": 6000}' \
  | python3 -m json.tool 2>/dev/null || true

echo ""
echo "==> Step 5: Request during outage (expect 503)..."
curl -s -w " => HTTP %{http_code}\n" "${BASE}/api/catalog"

echo ""
echo "==> Step 6: Advance clock by 10 more seconds (past the outage window)..."
curl -s -X PUT "${BASE}/mockserver/clock" \
  -d '{"action": "advance", "durationMillis": 10000}' \
  | python3 -m json.tool 2>/dev/null || true

echo ""
echo "==> Step 7: Request after self-heal (expect 200 again)..."
curl -s -w " => HTTP %{http_code}\n" "${BASE}/api/catalog"

echo ""
echo "==> Step 8: Reset the clock to real time..."
curl -s -X PUT "${BASE}/mockserver/clock" \
  -d '{"action": "reset"}' \
  | python3 -m json.tool 2>/dev/null || true
