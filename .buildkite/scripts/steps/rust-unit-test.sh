#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i rust:1.81 \
  -w /build/mockserver-client-rust \
  -- bash -c "cargo test --all-targets && cargo clippy --all-targets -- -D warnings"
