#!/usr/bin/env bash
# Match by HTTP content negotiation on the Accept header.
# Prefixing an Accept header matcher value with "accept:" switches it from a
# plain string/regex comparison to RFC 7231 content-negotiation matching: the
# expectation matches when the request's Accept header makes the given media
# type acceptable (honouring subtype wildcards like application/* and quality
# values such as q=0). Here it matches any request that accepts application/json.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "method": "GET",
    "path": "/some/path",
    "headers": {
      "Accept": [
        "accept:application/json"
      ]
    }
  },
  "httpResponse": {
    "headers": {
      "Content-Type": [
        "application/json"
      ]
    },
    "body": "{\"result\":\"ok\"}"
  }
}'
