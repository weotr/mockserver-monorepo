#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Testcontainers tests require Docker — mount the socket.
exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i golang:1.23 \
  -w /build/mockserver-testcontainers/go \
  --docker-socket \
  -- bash -c "go vet ./... && go test ./... -v -count=1 -timeout 300s"
