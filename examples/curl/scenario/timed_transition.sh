#!/usr/bin/env bash
# Timed scenario flow - auto-transition after a delay.
# Register one expectation per state, then drive the scenario through the
# scenario REST API: PUT /mockserver/scenario/{name} sets the state immediately
# and (with transitionAfterMs + nextState) schedules an automatic transition.
# Here GET /status returns "deploying" and then "complete" 1s later, modelling a
# background process that finishes on its own. The timed transition only fires if
# the scenario is still in the set state when the timer expires.
# Self-asserting: exits non-zero if the timed transition does not occur.
# Honours MOCKSERVER_URL, or MOCKSERVER_HOST/MOCKSERVER_PORT (default localhost:1080).
set -euo pipefail

MS="${MOCKSERVER_URL:-http://${MOCKSERVER_HOST:-localhost}:${MOCKSERVER_PORT:-1080}}"

curl -sf -X PUT "${MS}/mockserver/reset" >/dev/null

curl -sf -X PUT "${MS}/mockserver/expectation" -d '{
  "httpRequest": { "method": "GET", "path": "/status" },
  "httpResponse": { "statusCode": 200, "body": "deploying" },
  "scenarioName": "DeployFlow",
  "scenarioState": "Deploying"
}' >/dev/null

curl -sf -X PUT "${MS}/mockserver/expectation" -d '{
  "httpRequest": { "method": "GET", "path": "/status" },
  "httpResponse": { "statusCode": 200, "body": "complete" },
  "scenarioName": "DeployFlow",
  "scenarioState": "Deployed"
}' >/dev/null

# Enter "Deploying" now, auto-advance to "Deployed" after 1000ms.
curl -sf -X PUT "${MS}/mockserver/scenario/DeployFlow" \
  -H "Content-Type: application/json" \
  -d '{ "state": "Deploying", "transitionAfterMs": 1000, "nextState": "Deployed" }' >/dev/null

before=$(curl -s "${MS}/status"); echo "GET /status (now)        -> ${before}"
sleep 1.4
after=$(curl -s "${MS}/status");  echo "GET /status (after 1.4s) -> ${after}"

[ "${before}" = "deploying" ] || { echo "FAIL: expected 'deploying', got '${before}'"; exit 1; }
[ "${after}"  = "complete"  ] || { echo "FAIL: expected 'complete' after timer, got '${after}'"; exit 1; }
echo "PASS: timed scenario transition"
