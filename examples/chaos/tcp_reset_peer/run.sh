#!/usr/bin/env bash
# Register a TCP-layer chaos profile that sends TCP RST on connections
# to a target host. This operates at the raw byte level before HTTP
# decoding, simulating a hard network failure.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).
set -euo pipefail

BASE="${MOCKSERVER_URL:-http://localhost:1080}"

echo "==> Registering TCP RST chaos for host 'upstream-service.example.com'..."
curl -s -X PUT "${BASE}/mockserver/tcpChaos" \
  -d '{
  "host": "upstream-service.example.com",
  "chaos": {
    "resetPeer": true
  }
}' | python3 -m json.tool 2>/dev/null || true

echo ""
echo "==> Checking active TCP chaos profiles..."
curl -s "${BASE}/mockserver/tcpChaos" | python3 -m json.tool 2>/dev/null || true

echo ""
echo "==> To clean up, remove the profile:"
echo "    curl -X PUT '${BASE}/mockserver/tcpChaos' -d '{\"host\": \"upstream-service.example.com\", \"remove\": true}'"

echo ""
echo "==> Or clear all TCP chaos:"
echo "    curl -X PUT '${BASE}/mockserver/tcpChaos' -d '{\"clear\": true}'"
