#!/usr/bin/env bash
# Cycle through a fixed sequence of responses.
# With "httpResponses" (plural) and the default "responseMode":"SEQUENTIAL",
# each matching request returns the next response in the list, cycling back to
# the first after the last. Here requests return 200, 503, 200, then repeat -
# useful for scripting a deterministic sequence of outcomes.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "path": "/some/path"
  },
  "httpResponses": [
    { "statusCode": 200, "body": "first" },
    { "statusCode": 503, "body": "second" },
    { "statusCode": 200, "body": "third" }
  ],
  "responseMode": "SEQUENTIAL"
}'
