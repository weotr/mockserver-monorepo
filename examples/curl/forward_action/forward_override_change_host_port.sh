#!/usr/bin/env bash
# Forward requests with overridden host, port, and scheme via socketAddress.
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
          "any.host.com"
        ]
      },
      "socketAddress": {
        "host": "target.host.com",
        "port": 1234,
        "scheme": "HTTPS"
      }
    }
  }
}'
