#!/usr/bin/env bash
# Create an SSE (Server-Sent Events) expectation that streams two events
# to the client and then closes the connection.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "path": "/events/stream"
  },
  "httpSseResponse": {
    "statusCode": 200,
    "headers": {
      "Cache-Control": ["no-cache"]
    },
    "events": [
      {
        "event": "message",
        "data": "{\"id\": 1, \"text\": \"first event\"}",
        "id": "1"
      },
      {
        "event": "message",
        "data": "{\"id\": 2, \"text\": \"second event\"}",
        "id": "2",
        "retry": 5000
      }
    ],
    "closeConnection": true
  }
}'
