#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i gradle:8.10-jdk17 \
  -w /build/mockserver-jetbrains \
  -- bash -c "./gradlew test --no-daemon"
