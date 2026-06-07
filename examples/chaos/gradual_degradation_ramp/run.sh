#!/usr/bin/env bash
# Gradually ramp error probability from 0% to 100% over 60 seconds.
# For the first 60s after the first match, errorProbability linearly
# increases from 0.0 to the configured value (1.0). After the ramp
# completes, every request returns a 500 error.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).
set -euo pipefail

BASE="${MOCKSERVER_URL:-http://localhost:1080}"

echo "==> Creating expectation with gradual degradation ramp (60s)..."
curl -s -X PUT "${BASE}/mockserver/expectation" \
  -d '{
  "httpRequest": {
    "method": "GET",
    "path": "/api/orders"
  },
  "httpResponse": {
    "statusCode": 200,
    "body": "{\"orders\": []}"
  },
  "chaos": {
    "errorStatus": 500,
    "errorProbability": 1.0,
    "degradationRampMillis": 60000
  }
}' | python3 -m json.tool 2>/dev/null || true

echo ""
echo "==> Request at t=0 (error probability ~0%, expect 200)..."
curl -s -w " => HTTP %{http_code}\n" "${BASE}/api/orders"

echo ""
echo "==> Waiting 10 seconds..."
sleep 10

echo "==> Request at t~10s (error probability ~16%, may still succeed)..."
curl -s -w " => HTTP %{http_code}\n" "${BASE}/api/orders"

echo ""
echo "==> To see full degradation, keep sending requests over 60 seconds."
echo "    The error rate climbs linearly from 0% to 100%."
