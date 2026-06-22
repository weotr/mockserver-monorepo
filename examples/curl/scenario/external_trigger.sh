#!/usr/bin/env bash
# Triggered scenario flow - drive a state change from a test harness.
# Register one expectation per state, then advance the scenario externally with
# PUT /mockserver/scenario/{name}/trigger. This lets a test or CI pipeline flip a
# mocked dependency between states on demand - here /health returns 200 "healthy"
# until the harness triggers the "Down" state, after which it returns 503.
# Self-asserting: exits non-zero if the trigger does not take effect.
# Honours MOCKSERVER_URL, or MOCKSERVER_HOST/MOCKSERVER_PORT (default localhost:1080).
set -euo pipefail

MS="${MOCKSERVER_URL:-http://${MOCKSERVER_HOST:-localhost}:${MOCKSERVER_PORT:-1080}}"

curl -sf -X PUT "${MS}/mockserver/reset" >/dev/null

# Healthy in the initial "Started" state...
curl -sf -X PUT "${MS}/mockserver/expectation" -d '{
  "httpRequest": { "method": "GET", "path": "/health" },
  "httpResponse": { "statusCode": 200, "body": "healthy" },
  "scenarioName": "HealthFlow",
  "scenarioState": "Started"
}' >/dev/null

# ...unhealthy once triggered into "Down".
curl -sf -X PUT "${MS}/mockserver/expectation" -d '{
  "httpRequest": { "method": "GET", "path": "/health" },
  "httpResponse": { "statusCode": 503, "body": "down" },
  "scenarioName": "HealthFlow",
  "scenarioState": "Down"
}' >/dev/null

before=$(curl -s -o /dev/null -w '%{http_code}' "${MS}/health")
echo "GET /health (before trigger) -> ${before}"

echo "trigger HealthFlow -> Down:"
curl -sf -X PUT "${MS}/mockserver/scenario/HealthFlow/trigger" \
  -H "Content-Type: application/json" \
  -d '{ "newState": "Down" }' >/dev/null

after=$(curl -s -o /dev/null -w '%{http_code}' "${MS}/health")
echo "GET /health (after trigger)  -> ${after}"

[ "${before}" = "200" ] || { echo "FAIL: expected 200 before trigger, got ${before}"; exit 1; }
[ "${after}"  = "503" ] || { echo "FAIL: expected 503 after trigger, got ${after}"; exit 1; }
echo "PASS: externally triggered scenario transition"
