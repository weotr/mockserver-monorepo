#!/usr/bin/env bash
# Cross-protocol scenario correlation - one event advances another's scenario.
# Add a "crossProtocolScenarios" array to an expectation: when the trigger event
# fires, the named scenario advances to targetState, activating other
# expectations gated on that state. Triggers can be HTTP_REQUEST, WEBSOCKET_CONNECT,
# DNS_QUERY or GRPC_REQUEST (with an optional matchPattern substring filter on the
# event identifier). Here a request to /events advances "ConnFlow" to "Connected",
# which unlocks GET /api/conn-status. This example uses an HTTP_REQUEST trigger so
# it is runnable with curl alone; the same mechanism works across protocols.
# Self-asserting: exits non-zero if the correlation does not occur.
# Honours MOCKSERVER_URL, or MOCKSERVER_HOST/MOCKSERVER_PORT (default localhost:1080).
set -euo pipefail

MS="${MOCKSERVER_URL:-http://${MOCKSERVER_HOST:-localhost}:${MOCKSERVER_PORT:-1080}}"

curl -sf -X PUT "${MS}/mockserver/reset" >/dev/null

# A request to /events fires a cross-protocol trigger advancing ConnFlow.
curl -sf -X PUT "${MS}/mockserver/expectation" -d '{
  "httpRequest": { "method": "GET", "path": "/events" },
  "httpResponse": { "statusCode": 200, "body": "event-stream" },
  "crossProtocolScenarios": [
    {
      "trigger": "HTTP_REQUEST",
      "matchPattern": "/events",
      "scenarioName": "ConnFlow",
      "targetState": "Connected"
    }
  ]
}' >/dev/null

# Only active once ConnFlow has reached "Connected".
curl -sf -X PUT "${MS}/mockserver/expectation" -d '{
  "httpRequest": { "method": "GET", "path": "/api/conn-status" },
  "httpResponse": { "statusCode": 200, "body": "connected" },
  "scenarioName": "ConnFlow",
  "scenarioState": "Connected"
}' >/dev/null

before=$(curl -s -o /dev/null -w '%{http_code}' "${MS}/api/conn-status")
echo "GET /api/conn-status (before)      -> ${before}"
events=$(curl -s -o /dev/null -w '%{http_code}' "${MS}/events")
echo "GET /events (fires trigger)        -> ${events}"
after=$(curl -s -o /dev/null -w '%{http_code}' "${MS}/api/conn-status")
echo "GET /api/conn-status (after events) -> ${after}"

[ "${before}" = "404" ] || { echo "FAIL: expected 404 before trigger, got ${before}"; exit 1; }
[ "${events}" = "200" ] || { echo "FAIL: expected 200 from /events, got ${events}"; exit 1; }
[ "${after}"  = "200" ] || { echo "FAIL: expected 200 after trigger, got ${after}"; exit 1; }
echo "PASS: cross-protocol scenario correlation"
