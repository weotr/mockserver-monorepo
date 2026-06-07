#!/usr/bin/env bash
# Register gRPC chaos that injects UNAVAILABLE status with latency and
# custom trailers on all methods of a gRPC service.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).
set -euo pipefail

BASE="${MOCKSERVER_URL:-http://localhost:1080}"

echo "==> Registering gRPC chaos for service 'com.example.OrderService'..."
curl -s -X PUT "${BASE}/mockserver/grpcChaos" \
  -d '{
  "service": "com.example.OrderService",
  "chaos": {
    "errorStatusCode": "UNAVAILABLE",
    "errorMessage": "service is undergoing maintenance",
    "errorProbability": 0.5,
    "latencyMs": 100,
    "customTrailers": {
      "x-retry-reason": "chaos-test",
      "x-fault-id": "grpc-001"
    }
  }
}' | python3 -m json.tool 2>/dev/null || true

echo ""
echo "==> Registering a default gRPC chaos profile (applies to ALL services)..."
curl -s -X PUT "${BASE}/mockserver/grpcChaos" \
  -d '{
  "service": "",
  "chaos": {
    "errorStatusCode": "INTERNAL",
    "errorProbability": 0.1,
    "seed": 99
  }
}' | python3 -m json.tool 2>/dev/null || true

echo ""
echo "==> Checking active gRPC chaos profiles..."
curl -s "${BASE}/mockserver/grpcChaos" | python3 -m json.tool 2>/dev/null || true

echo ""
echo "==> Live-patching OrderService: increase probability and add quota..."
curl -s -X PATCH "${BASE}/mockserver/grpcChaos" \
  -d '{
  "service": "com.example.OrderService",
  "chaos": {
    "errorProbability": 0.8,
    "quotaName": "order-rpc-limit",
    "quotaLimit": 10,
    "quotaWindowMillis": 5000
  }
}' | python3 -m json.tool 2>/dev/null || true

echo ""
echo "==> Clean up: clear all gRPC chaos..."
curl -s -X PUT "${BASE}/mockserver/grpcChaos" \
  -d '{"clear": true}' | python3 -m json.tool 2>/dev/null || true
