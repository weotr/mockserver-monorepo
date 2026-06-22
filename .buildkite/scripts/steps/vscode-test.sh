#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Clean node_modules inside the container (as container-root) on exit. Under the
# elastic-ci-stack's userns-remap, files the container writes into the bind-mounted
# workspace are owned by a remapped UID the buildkite-agent user CANNOT delete — so
# a leftover native module (mockserver-vscode/node_modules/keytar/build/Release/
# keytar.node, built by @vscode/vsce's deps) made the NEXT build's git checkout fail
# to clean the dir ("unlinkat ... permission denied" -> "cloning git repository:
# exit status 128"), reddening mockserver-editors. Removing node_modules here (the
# container owns those files) leaves the workspace cleanly removable. The npm cache
# (--cache npm, mounted separately) is untouched, so re-install stays fast. The trap
# runs whether the tests pass or fail.
exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i node:20 \
  -w /build/mockserver-vscode \
  --cache npm \
  -- bash -c 'trap "rm -rf node_modules" EXIT; npm ci && npm run compile && npm test'
