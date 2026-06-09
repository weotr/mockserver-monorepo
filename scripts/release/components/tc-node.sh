#!/usr/bin/env bash
# Publish @mockserver/testcontainers to npm.
#
# Dry-run: build + npm publish --dry-run, skip actual publish.
# SOFT: if the secret is absent or the publish fails, log a warning and exit 0.

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

if ! command -v npm >/dev/null 2>&1; then
  log_info "WARNING: 'npm' not found on PATH — skipping tc-node publish (non-fatal)"
  exit 0
fi

# Read current package name for idempotency check
NPM_NAME=$(jq -r '.name' "$PKG_JSON" 2>/dev/null || echo "@mockserver/testcontainers")

# Bump version in package.json
if ! is_dry_run; then
  log_info "Updating version to $RELEASE_VERSION in package.json"
  (cd "$COMPONENT_DIR" && npm version "$RELEASE_VERSION" --no-git-tag-version) || {
    log_info "WARNING: npm version failed — skipping tc-node publish (non-fatal)"
    exit 0
  }
fi

# Build
log_info "Building"
(cd "$COMPONENT_DIR" && npm ci && npm run build) || {
  log_info "WARNING: npm build failed — skipping tc-node publish (non-fatal)"
  exit 0
}

if is_dry_run; then
  log_dry "skip: npm publish --access public"
  log_info "Running npm publish --dry-run for validation"
  (cd "$COMPONENT_DIR" && npm publish --dry-run --access public) || true
  exit 0
fi

# Idempotent: check if already published
if curl -fsI --connect-timeout 10 --max-time 15 -o /dev/null \
    "https://registry.npmjs.org/$NPM_NAME/$RELEASE_VERSION" 2>/dev/null; then
  log_info "$NPM_NAME@$RELEASE_VERSION already on npm — skipping"
  exit 0
fi

# Reuses the same npm token as npm.sh. Load directly (GetSecretValue) rather
# than gating on describe-secret: the release agent has GetSecretValue but not
# DescribeSecret on this secret, so a describe gate would misfire (AccessDenied
# read as "absent") and skip publishing. A load failure soft-skips.
NPM_TOKEN=$(load_secret "mockserver-release/npm-token" "token") || {
  log_info "WARNING: could not read mockserver-release/npm-token — skipping tc-node publish (non-fatal)"
  exit 0
}

log_info "Publishing to npm"
(
  cd "$COMPONENT_DIR"
  cat > .npmrc <<NPMRC
//registry.npmjs.org/:_authToken=${NPM_TOKEN}
registry=https://registry.npmjs.org/
always-auth=true
NPMRC
  npm publish --access public
  rm -f .npmrc
) || {
  log_info "WARNING: npm publish failed — skipping (non-fatal)"
  rm -f "$COMPONENT_DIR/.npmrc"
  exit 0
}

# Commit version bump
git_commit_and_push "release: publish @mockserver/testcontainers $RELEASE_VERSION to npm" \
  "$COMPONENT_DIR/package.json" "$COMPONENT_DIR/package-lock.json" || \
  log_info "WARNING: could not commit version bump (non-fatal)"

log_info "@mockserver/testcontainers publish complete"
