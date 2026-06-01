#!/usr/bin/env bash
# Create a WebSocket expectation with a message matcher: when the
# server receives a TEXT frame matching "ping", it responds with "pong".
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "path": "/ws/echo"
  },
  "httpWebSocketResponse": {
    "matchers": [
      {
        "frameType": "TEXT",
        "textMatcher": "ping",
        "responses": [
          {
            "text": "pong"
          }
        ]
      }
    ],
    "closeConnection": false
  }
}'
