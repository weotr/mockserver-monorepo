#!/usr/bin/env bash
# Load Scenario registry - a realistic multi-stage profile with multiple steps.
#
# Registers a "checkout-load" scenario whose profile is a three-stage journey:
#   1. RATE ramp  - arrival rate climbs linearly 5 -> 50 req/s over 30s, capped at
#                   50 virtual users (open-model load: rate-driven).
#   2. VU hold    - 25 concurrent virtual users for 60s (closed-model load).
#   3. PAUSE      - 10s of no load (recovery / cool-down window).
# It drives two request steps per iteration (browse then checkout), each embedding
# a full MockServer HttpRequest. Velocity templates ($!iteration.index) vary the
# request per iteration; thinkTime paces the steps.
#
# Then starts it, polls live status, and stops it. Requires the server started
# with -Dmockserver.loadGenerationEnabled=true.
#
# Self-asserting: exits non-zero on failure.
# Honours MOCKSERVER_URL, or MOCKSERVER_HOST/MOCKSERVER_PORT (default localhost:1080).
set -euo pipefail

MS="${MOCKSERVER_URL:-http://${MOCKSERVER_HOST:-localhost}:${MOCKSERVER_PORT:-1080}}"

# Catch-all target so every generated request gets a fast 200.
curl -sf -X PUT "${MS}/mockserver/expectation" -d '{
  "httpRequest": {},
  "httpResponse": { "statusCode": 200, "body": "ok" }
}' >/dev/null

echo "==> register 'checkout-load' (RATE ramp -> VU hold -> PAUSE, 2 steps)..."
curl -sf -X PUT "${MS}/mockserver/loadScenario" \
  --data @"$(dirname "$0")/../../json/load_scenario/checkout_load.json" \
  | grep -q '"state" : "LOADED"' || { echo "FAIL: register"; exit 1; }

echo "==> start 'checkout-load'..."
curl -sf -X PUT "${MS}/mockserver/loadScenario/start" -d '{"name":"checkout-load"}' \
  | grep -q '"state" : "RUNNING"' || { echo "FAIL: start (loadGenerationEnabled=true?)"; exit 1; }

echo "==> poll live status for 3s..."
for _ in 1 2 3; do
  curl -sf "${MS}/mockserver/loadScenario/checkout-load" \
    | grep -E '"stageType"|"currentTarget"|"requestsSent"' | tr '\n' ' '
  echo ""
  sleep 1
done

echo "==> stop all running scenarios (empty body)..."
curl -sf -X PUT "${MS}/mockserver/loadScenario/stop" -d '{}' \
  | grep -q '"status" : "stopped"' || { echo "FAIL: stop"; exit 1; }

echo "==> clear the registry (DELETE /mockserver/loadScenario)..."
curl -sf -X DELETE "${MS}/mockserver/loadScenario" \
  | grep -q '"status" : "cleared"' || { echo "FAIL: clear"; exit 1; }

echo "PASS: multi-stage profile register -> start -> status -> stop -> clear"
