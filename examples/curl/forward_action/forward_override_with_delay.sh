#!/usr/bin/env bash
# Forward requests with overridden path and a 20-second delay.
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
    },
    "delay": {
      "timeUnit": "SECONDS",
      "value": 20
    }
  }
}'
