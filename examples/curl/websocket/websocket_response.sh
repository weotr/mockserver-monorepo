#!/usr/bin/env bash
# Create a WebSocket expectation that upgrades the connection with a
# subprotocol and sends two text messages to the client.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "path": "/ws/chat"
  },
  "httpWebSocketResponse": {
    "subprotocol": "chat",
    "messages": [
      {
        "text": "welcome to the chat"
      },
      {
        "text": "you are now connected"
      }
    ],
    "closeConnection": false
  }
}'
