#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i mcr.microsoft.com/dotnet/sdk:10.0 \
  -w /build/mockserver-client-dotnet \
  -- bash -c "dotnet test --logger 'trx;LogFileName=unit.trx' --results-directory test-reports"
