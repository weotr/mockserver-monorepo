#!/usr/bin/env bash
# Verify a recorded response (not just the request).
# Supplying an "httpResponse" matcher to /mockserver/verify checks the
# responses MockServer recorded. The optional "disposition" narrows this to
# "FORWARDED" (proxied upstream responses) or "MOCKED" (responses MockServer
# generated). Here we assert at least one forwarded response returned 200.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/verify" \
-d '{
  "httpRequest": {
    "path": "/some/path"
  },
  "httpResponse": {
    "statusCode": 200
  },
  "disposition": "FORWARDED",
  "times": {
    "atLeast": 1
  }
}'
