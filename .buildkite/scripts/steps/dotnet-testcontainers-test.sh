#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Testcontainers tests require Docker — mount the socket.
exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i mcr.microsoft.com/dotnet/sdk:10.0 \
  -w /build/mockserver-testcontainers/dotnet \
  --docker-socket \
  -- bash -c "dotnet test --logger 'trx;LogFileName=unit.trx' --results-directory test-reports"
