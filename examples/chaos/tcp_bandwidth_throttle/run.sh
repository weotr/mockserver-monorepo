#!/usr/bin/env bash
# Register a TCP-layer chaos profile that throttles bandwidth to 1024 bytes/sec
# for a target host, with an optional TTL so the chaos auto-expires.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).
set -euo pipefail

BASE="${MOCKSERVER_URL:-http://localhost:1080}"

echo "==> Registering TCP bandwidth throttle (1 KB/s, TTL 60s) for 'slow-api.example.com'..."
curl -s -X PUT "${BASE}/mockserver/tcpChaos" \
  -d '{
  "host": "slow-api.example.com",
  "chaos": {
    "bandwidthBytesPerSec": 1024
  },
  "ttlMillis": 60000
}' | python3 -m json.tool 2>/dev/null || true

echo ""
echo "==> Checking active TCP chaos profiles (note ttlRemainingMillis)..."
curl -s "${BASE}/mockserver/tcpChaos" | python3 -m json.tool 2>/dev/null || true

echo ""
echo "==> Live-patching: also add data slicing (256-byte chunks)..."
curl -s -X PATCH "${BASE}/mockserver/tcpChaos" \
  -d '{
  "host": "slow-api.example.com",
  "chaos": {
    "slicerChunkSize": 256
  }
}' | python3 -m json.tool 2>/dev/null || true

echo ""
echo "==> Verify the merged profile (bandwidth + slicer)..."
curl -s "${BASE}/mockserver/tcpChaos" | python3 -m json.tool 2>/dev/null || true
