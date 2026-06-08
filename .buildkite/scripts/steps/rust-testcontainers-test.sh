#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Testcontainers tests require Docker — mount the socket.
# clippy is also run for lint coverage.
exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i rust:1.81 \
  -w /build/mockserver-testcontainers/rust \
  --docker-socket \
  -- bash -c "cargo test --all-targets && cargo clippy --all-targets -- -D warnings"
