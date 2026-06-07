#!/usr/bin/env bash
# Demonstrate the full service-scoped chaos lifecycle:
# 1. Register a chaos profile for a host via PUT /mockserver/serviceChaos
# 2. Read the active profiles via GET /mockserver/serviceChaos
# 3. Live-patch the profile via PATCH /mockserver/serviceChaos (merge semantics)
# 4. Verify the merged profile
# 5. Clean up
#
# Service-scoped chaos applies to ALL forwarded/proxied requests to the given
# host without needing a chaos block on every expectation.
#
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).
set -euo pipefail

BASE="${MOCKSERVER_URL:-http://localhost:1080}"

echo "==> Step 1: Register service chaos for 'payments.internal:8443'..."
echo "    (latency + 30% error rate + slow dribble, TTL 120s)"
curl -s -X PUT "${BASE}/mockserver/serviceChaos" \
  -d '{
  "host": "payments.internal:8443",
  "chaos": {
    "errorStatus": 500,
    "errorProbability": 0.3,
    "latency": {
      "timeUnit": "MILLISECONDS",
      "value": 0,
      "distribution": {
        "type": "LOG_NORMAL",
        "median": 100,
        "p99": 2000
      }
    },
    "slowResponseChunkSize": 512,
    "slowResponseChunkDelay": {
      "timeUnit": "MILLISECONDS",
      "value": 200
    }
  },
  "ttlMillis": 120000
}' | python3 -m json.tool 2>/dev/null || true

echo ""
echo "==> Step 2: Read active service chaos profiles..."
curl -s "${BASE}/mockserver/serviceChaos" | python3 -m json.tool 2>/dev/null || true

echo ""
echo "==> Step 3: Live-patch -- increase error rate to 80% and add body truncation..."
echo "    (only the specified fields are updated; unset fields are preserved)"
curl -s -X PATCH "${BASE}/mockserver/serviceChaos" \
  -d '{
  "host": "payments.internal:8443",
  "chaos": {
    "errorProbability": 0.8,
    "truncateBodyAtFraction": 0.5
  }
}' | python3 -m json.tool 2>/dev/null || true

echo ""
echo "==> Step 4: Verify the merged profile..."
echo "    (errorProbability should be 0.8, truncateBodyAtFraction should be 0.5,"
echo "     but latency, slow dribble, and errorStatus are preserved from step 1)"
curl -s "${BASE}/mockserver/serviceChaos" | python3 -m json.tool 2>/dev/null || true

echo ""
echo "==> Step 5: Remove the profile for this specific host..."
curl -s -X PUT "${BASE}/mockserver/serviceChaos" \
  -d '{
  "host": "payments.internal:8443",
  "remove": true
}' | python3 -m json.tool 2>/dev/null || true

echo ""
echo "==> Step 6: Verify it was removed..."
curl -s "${BASE}/mockserver/serviceChaos" | python3 -m json.tool 2>/dev/null || true
