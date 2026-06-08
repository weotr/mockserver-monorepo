#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i python:3.12 \
  -w /build/mockserver-testcontainers/python \
  --cache pip \
  -- bash -c 'pip install -e ".[test]" && pytest -m "not docker" -v'
