#!/usr/bin/env bash
# Sequential / cycling responses - one expectation, many responses.
# Provide an array in "httpResponses" instead of a single "httpResponse". With
# the default "responseMode":"SEQUENTIAL" each matching request returns the next
# response in the list, cycling back to the first after the last. Here three
# calls return 200, 503, 200, and the fourth cycles back to 200. (Other modes:
# RANDOM, WEIGHTED with responseWeights, SWITCH with switchAfter.)
# Self-asserting: exits non-zero if the sequence is wrong.
# Honours MOCKSERVER_URL, or MOCKSERVER_HOST/MOCKSERVER_PORT (default localhost:1080).
set -euo pipefail

MS="${MOCKSERVER_URL:-http://${MOCKSERVER_HOST:-localhost}:${MOCKSERVER_PORT:-1080}}"

curl -sf -X PUT "${MS}/mockserver/reset" >/dev/null

curl -sf -X PUT "${MS}/mockserver/expectation" -d '{
  "httpRequest": { "path": "/api/status" },
  "httpResponses": [
    { "statusCode": 200, "body": "{\"status\": \"ok\"}" },
    { "statusCode": 503, "body": "{\"status\": \"degraded\"}" },
    { "statusCode": 200, "body": "{\"status\": \"ok\"}" }
  ]
}' >/dev/null

codes=""
for i in 1 2 3 4; do
  c=$(curl -s -o /dev/null -w '%{http_code}' "${MS}/api/status")
  codes="${codes}${c} "
done
echo "GET /api/status x4 -> ${codes}"

[ "${codes}" = "200 503 200 200 " ] || { echo "FAIL: expected '200 503 200 200', got '${codes}'"; exit 1; }
echo "PASS: sequential cycling responses"
