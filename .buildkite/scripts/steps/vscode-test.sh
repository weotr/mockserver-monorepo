#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i node:20 \
  -w /build/mockserver-vscode \
  --cache npm \
  -- bash -c "npm ci && npm run compile && npm run lint"
