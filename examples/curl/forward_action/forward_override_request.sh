#!/usr/bin/env bash
# Forward requests with overridden path and Host header.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "path": "/some/path"
  },
  "httpOverrideForwardedRequest": {
    "httpRequest": {
      "path": "/some/other/path",
      "headers": {
        "Host": [
          "target.host.com"
        ]
      }
    }
  }
}'
