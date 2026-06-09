#!/usr/bin/env bash
# Publish mockserver-client-go via git tag.
#
# Go modules are indexed automatically by the Go module proxy when a
# properly-formatted tag is pushed to a public repository. The subdir-module
# tag convention (mockserver-client-go/vX.Y.Z) is required for pkg.go.dev to
# resolve the module inside a monorepo.
#
# NOTE: full pkg.go.dev resolution may require splitting to a dedicated repo
# later — keep this best-effort/soft.
#
# Dry-run: validate the module builds, skip tag + push.

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
skip_unless_release_type "client-go" full,post-maven

log_step "Publish Go client $RELEASE_VERSION (dry-run=$DRY_RUN)"

MODULE_DIR="$REPO_ROOT/mockserver-client-go"
TAG="mockserver-client-go/v${RELEASE_VERSION}"

if ! command -v go >/dev/null 2>&1; then
  log_info "WARNING: 'go' not found on PATH — skipping client-go publish (non-fatal)"
  exit 0
fi

# Validate module builds
log_info "Validating Go module"
(cd "$MODULE_DIR" && go vet ./...) || {
  log_info "WARNING: go vet failed — skipping client-go publish (non-fatal)"
  exit 0
}

if is_dry_run; then
  log_dry "skip: git tag $TAG + push"
  log_dry "skip: trigger proxy indexing"
  exit 0
fi

# Tag and push
log_info "Tagging $TAG"
git_tag_and_push "$TAG" || {
  log_info "WARNING: could not push tag $TAG — skipping (non-fatal)"
  exit 0
}

# Trigger proxy indexing (best-effort)
log_info "Triggering Go module proxy indexing"
GOPROXY=https://proxy.golang.org GO111MODULE=on \
  go list -m "github.com/mock-server/mockserver-monorepo/mockserver-client-go@v${RELEASE_VERSION}" 2>/dev/null || \
  log_info "  proxy indexing trigger returned non-zero (may take time to propagate)"

log_info "Go client publish complete"
