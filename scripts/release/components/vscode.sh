#!/usr/bin/env bash
# Publish MockServer VS Code extension to:
#   1. VS Code Marketplace (via vsce)
#   2. Open VSX Registry (via ovsx)
#
# Dry-run: build + package (.vsix), skip publish.
# SOFT: if secrets are absent or publish fails, log a warning and exit 0.

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
skip_unless_release_type "vscode" full,post-maven

log_step "Publish VS Code extension $RELEASE_VERSION (dry-run=$DRY_RUN)"

COMPONENT_DIR="$REPO_ROOT/mockserver-vscode"

if ! command -v npm >/dev/null 2>&1; then
  log_info "WARNING: 'npm' not found on PATH — skipping vscode publish (non-fatal)"
  exit 0
fi

# Bump version in package.json
if ! is_dry_run; then
  log_info "Updating version to $RELEASE_VERSION in package.json"
  (cd "$COMPONENT_DIR" && npm version "$RELEASE_VERSION" --no-git-tag-version) || {
    log_info "WARNING: npm version failed — skipping vscode publish (non-fatal)"
    exit 0
  }
fi

# Install dependencies and compile
log_info "Building extension"
(cd "$COMPONENT_DIR" && npm ci && npm run compile 2>/dev/null) || {
  log_info "WARNING: npm build failed — skipping vscode publish (non-fatal)"
  exit 0
}

# Package .vsix for validation in both modes
log_info "Packaging .vsix"
(cd "$COMPONENT_DIR" && npx vsce package --no-git-tag-version) || {
  log_info "WARNING: vsce package failed — skipping vscode publish (non-fatal)"
  exit 0
}

if is_dry_run; then
  log_dry "skip: vsce publish + ovsx publish"
  log_info "Built .vsix:"
  ls -la "$COMPONENT_DIR"/*.vsix 2>/dev/null || true
  exit 0
fi

# --- VS Code Marketplace ---
if ! aws secretsmanager describe-secret --region "$REGION" \
     --secret-id mockserver-release/vsce >/dev/null 2>&1; then
  log_info "WARNING: mockserver-release/vsce secret not configured — skipping VS Code Marketplace publish (non-fatal)"
else
  VSCE_PAT=$(load_secret "mockserver-release/vsce" "token")
  log_info "Publishing to VS Code Marketplace"
  (cd "$COMPONENT_DIR" && npx vsce publish -p "$VSCE_PAT") || {
    log_info "WARNING: vsce publish failed — continuing (non-fatal)"
  }
fi

# --- Open VSX ---
if ! aws secretsmanager describe-secret --region "$REGION" \
     --secret-id mockserver-release/ovsx >/dev/null 2>&1; then
  log_info "WARNING: mockserver-release/ovsx secret not configured — skipping Open VSX publish (non-fatal)"
else
  OVSX_PAT=$(load_secret "mockserver-release/ovsx" "token")
  log_info "Publishing to Open VSX"
  (cd "$COMPONENT_DIR" && npx ovsx publish -p "$OVSX_PAT") || {
    log_info "WARNING: ovsx publish failed — continuing (non-fatal)"
  }
fi

# Commit version bump
git_commit_and_push "release: publish mockserver-vscode $RELEASE_VERSION" \
  "$COMPONENT_DIR/package.json" "$COMPONENT_DIR/package-lock.json" || \
  log_info "WARNING: could not commit version bump (non-fatal)"

log_info "VS Code extension publish complete"
