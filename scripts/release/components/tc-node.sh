#!/usr/bin/env bash
# Publish @mockserver/testcontainers to npm.
#
# Dry-run: bump version (host edit) + build + npm publish --dry-run, skip publish.
# HARD: a real build or publish failure aborts the step. The build and publish
# run inside a pinned node container (no host `npm` required) — this replaced a
# host `command -v npm` probe that silently `exit 0`d on the release-queue agent,
# which has only Java/Maven/Docker, so this package never actually published.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$SCRIPT_DIR/_lib.sh"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=true; shift ;;
    --execute) DRY_RUN=false; shift ;;
    -h|--help) echo "Usage: $0 [--dry-run|--execute]"; exit 0 ;;
    *) log_error "Unknown arg: $1"; exit 2 ;;
  esac
done

require_release_inputs
skip_unless_release_type "tc-node" full,post-maven

log_step "Publish @mockserver/testcontainers $RELEASE_VERSION (dry-run=$DRY_RUN)"

COMPONENT_DIR="$REPO_ROOT/mockserver-testcontainers/node"
PKG_JSON="$COMPONENT_DIR/package.json"

# The in-container `npm ci` below writes a root-owned node_modules into the
# bind-mounted workspace that the next job's git checkout cannot clean (see
# clean_workspace_node_modules). Remove it on every exit path.
trap 'clean_workspace_node_modules mockserver-testcontainers/node' EXIT

# Read current package name for idempotency check (host jq — package.json is a
# plain file on the bind-mounted repo).
NPM_NAME=$(jq -r '.name' "$PKG_JSON" 2>/dev/null || echo "@mockserver/testcontainers")

# Bump version in package.json. Host edit — package.json is on the bind-mounted
# repo, so the in-container npm build/publish sees the bumped version. Done
# without npm/git (sed) so no host toolchain is required.
if ! is_dry_run; then
  log_info "Updating version to $RELEASE_VERSION in package.json"
  sed -i.bak -E "s/(\"version\"[[:space:]]*:[[:space:]]*\")[^\"]*(\")/\1${RELEASE_VERSION}\2/" "$PKG_JSON"
  rm -f "$PKG_JSON.bak"
fi

# Build in a pinned node container — no host 'npm' required. HARD: a build
# failure aborts the step (set -e propagates). Retry to ride out transient npm
# registry/network blips during `npm ci`.
log_info "Building @mockserver/testcontainers in $NODE_IMAGE"
retry 3 5 -- in_docker "$NODE_IMAGE" -w /build/mockserver-testcontainers/node -- \
  sh -c 'npm ci && npm run build'

if is_dry_run; then
  log_dry "skip: npm publish --access public"
  log_info "Running npm publish --dry-run for validation in $NODE_IMAGE"
  in_docker "$NODE_IMAGE" -w /build/mockserver-testcontainers/node -- \
    npm publish --dry-run --access public
  exit 0
fi

# Idempotent: if this exact version is already on npm, skip the publish.
if curl -fsI --connect-timeout 10 --max-time 15 -o /dev/null \
    "https://registry.npmjs.org/$NPM_NAME/$RELEASE_VERSION" 2>/dev/null; then
  log_info "$NPM_NAME@$RELEASE_VERSION already on npm — skipping"
  exit 0
fi

# The npm token EXISTS (mockserver-release/npm-token, key: token). If it is
# genuinely absent/empty, that's a hard failure now — we no longer silently
# skip. Loaded directly via GetSecretValue (the release agent has
# GetSecretValue but not DescribeSecret on this secret).
NPM_TOKEN=$(load_secret "mockserver-release/npm-token" "token")
if [[ -z "$NPM_TOKEN" || "$NPM_TOKEN" == "null" ]]; then
  log_error "mockserver-release/npm-token secret missing/empty — cannot publish"
  exit 1
fi

# Publish to npm inside the container. HARD-fail on a real publish error, with
# retry to ride out transient registry/network blips. The token is passed via
# -e (run-in-docker redacts -e values in its logged command) and dereferenced
# INSIDE the single-quoted sh -c body so the literal token never lands in the
# logged command args (run-in-docker does NOT redact the command body).
log_info "Publishing to npm from $NODE_IMAGE"
retry 3 5 -- in_docker "$NODE_IMAGE" \
  -e "NPM_TOKEN=$NPM_TOKEN" \
  -w /build/mockserver-testcontainers/node -- \
  sh -c '
    printf "//registry.npmjs.org/:_authToken=%s\nregistry=https://registry.npmjs.org/\nalways-auth=true\n" "$NPM_TOKEN" > .npmrc
    trap "rm -f .npmrc" EXIT
    npm publish --access public
  '

# Commit version bump.
git_commit_and_push "release: publish @mockserver/testcontainers $RELEASE_VERSION to npm" \
  "$COMPONENT_DIR/package.json" "$COMPONENT_DIR/package-lock.json" || \
  log_info ":warning: could not commit version bump (non-fatal — publish already succeeded)"

log_info "@mockserver/testcontainers publish complete"
