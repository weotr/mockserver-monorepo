#!/usr/bin/env bash
# Soft / collecting verification of several requests.
# The Java client's verifyAll(...) checks multiple requests and reports every
# failure together. Over REST there is no /verifyAll endpoint - each
# verification is an independent PUT /mockserver/verify. This script issues one
# verify per expected request so you can collect all the outcomes yourself.
# Each PUT returns 202 when the verification passes, or 406 with a failure
# message when it does not.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

BASE="${MOCKSERVER_URL:-http://localhost:1080}"

# Verify the first request was received at least once
curl -X PUT "${BASE}/mockserver/verify" \
-d '{
  "httpRequest": {
    "path": "/some/path/one"
  },
  "times": {
    "atLeast": 1
  }
}'

# Verify the second request was received at least once
curl -X PUT "${BASE}/mockserver/verify" \
-d '{
  "httpRequest": {
    "path": "/some/path/two"
  },
  "times": {
    "atLeast": 1
  }
}'
