#!/usr/bin/env bash
# Return a DNS NXDOMAIN response (domain not found).
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "path": "/dns/notfound"
  },
  "dnsResponse": {
    "responseCode": "NXDOMAIN"
  }
}'
