#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i mockserver/mockserver:maven \
  -w /build/mockserver-node \
  --cache npm \
  -- bash -c '/build/.buildkite/scripts/install-nodejs.sh && npm ci && npm test'
