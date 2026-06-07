#!/usr/bin/env bash
# Return a DNS response with an A record for example.com.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "path": "/dns/lookup"
  },
  "dnsResponse": {
    "responseCode": "NOERROR",
    "answerRecords": [
      {
        "name": "example.com",
        "type": "A",
        "value": "93.184.216.34",
        "ttl": 300
      }
    ]
  }
}'
