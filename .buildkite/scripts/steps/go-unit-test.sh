#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i golang:1.23 \
  -w /build/mockserver-client-go \
  -- bash -c "go vet ./... && go test ./... -v -count=1"
