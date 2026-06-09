#!/usr/bin/env bash
# Publish mockserver-client-php via git tag.
#
# PHP packages on Packagist are indexed automatically via a GitHub webhook when
# a tag is pushed. The subdir-module tag convention
# (mockserver-client-php/vX.Y.Z) is used.
#
# NOTE: Packagist webhook must be configured once in GitHub repo settings.
# If Packagist requires a separate split repo in the future, the tag approach
# still works — keep this best-effort/soft.
#
# Dry-run: validate composer.json, skip tag + push.

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
skip_unless_release_type "client-php" full,post-maven

log_step "Publish PHP client $RELEASE_VERSION (dry-run=$DRY_RUN)"

MODULE_DIR="$REPO_ROOT/mockserver-client-php"
TAG="mockserver-client-php/v${RELEASE_VERSION}"

# Validate composer.json exists
if [[ ! -f "$MODULE_DIR/composer.json" ]]; then
  log_info "WARNING: composer.json not found in $MODULE_DIR — skipping client-php publish (non-fatal)"
  exit 0
fi

# Validate composer.json is valid JSON
if command -v jq >/dev/null 2>&1; then
  if ! jq empty "$MODULE_DIR/composer.json" 2>/dev/null; then
    log_info "WARNING: composer.json is not valid JSON — skipping client-php publish (non-fatal)"
    exit 0
  fi
  log_info "composer.json: valid (name=$(jq -r '.name' "$MODULE_DIR/composer.json"))"
fi

if is_dry_run; then
  log_dry "skip: git tag $TAG + push"
  exit 0
fi

# Tag and push (Packagist webhook picks it up)
log_info "Tagging $TAG"
git_tag_and_push "$TAG" || {
  log_info "WARNING: could not push tag $TAG — skipping (non-fatal)"
  exit 0
}

log_info "PHP client publish complete (Packagist indexes via webhook within 1-2 minutes)"
