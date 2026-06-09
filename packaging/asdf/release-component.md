# Release component: asdf / mise

Script body for `scripts/release/components/asdf.sh`:

```bash
#!/usr/bin/env bash
# Verify MockServer CLI is available via asdf/mise after GitHub Release.
#
# asdf/mise plugins query GitHub Releases dynamically — no publish step is
# needed. This script verifies the plugin repo is in sync and the new version
# is discoverable.
#
# Dry-run: same as execute (verification only, no side effects).

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

require_cmd curl
require_cmd diff
require_release_inputs
skip_unless_release_type "asdf" full,post-maven

log_step "Verify asdf/mise plugin for $RELEASE_VERSION (dry-run=$DRY_RUN)"

WORK_DIR="$REPO_ROOT/.tmp/asdf-$RELEASE_VERSION"
mkdir -p "$WORK_DIR"

# 1. Verify plugin repo scripts match the source of truth in packaging/asdf/bin/
log_info "Check plugin repo sync"
GITHUB_TOKEN=$(load_secret "mockserver-release/github-token" "token")
git clone --depth 1 \
  "https://x-access-token:${GITHUB_TOKEN}@github.com/mock-server/asdf-mockserver.git" \
  "$WORK_DIR/asdf-mockserver" 2>/dev/null

DRIFT=false
for script in list-all download install latest-stable; do
  if ! diff -q "$REPO_ROOT/packaging/asdf/bin/$script" \
               "$WORK_DIR/asdf-mockserver/bin/$script" >/dev/null 2>&1; then
    log_info "  DRIFT: bin/$script differs from plugin repo"
    DRIFT=true
  fi
done

if [[ "$DRIFT" == "true" ]]; then
  log_info "Syncing plugin scripts to mock-server/asdf-mockserver"
  dry_run_or "Push synced scripts" bash -c '
    cp "$REPO_ROOT/packaging/asdf/bin/"* "$WORK_DIR/asdf-mockserver/bin/"
    cd "$WORK_DIR/asdf-mockserver"
    git add bin/
    git commit -m "sync plugin scripts from mockserver-monorepo"
    git push
  '
else
  log_info "  Plugin scripts are in sync"
fi

# 2. Verify the new version is discoverable via the GitHub Releases API
log_info "Verify version $RELEASE_VERSION is discoverable"
FOUND=$(curl -fsSL "https://api.github.com/repos/mock-server/mockserver-monorepo/releases/tags/mockserver-$RELEASE_VERSION" \
  | grep -c '"tag_name"' || true)
if [[ "$FOUND" -gt 0 ]]; then
  log_info "  Release mockserver-$RELEASE_VERSION found on GitHub"
else
  log_error "  Release mockserver-$RELEASE_VERSION NOT found on GitHub — asdf users won't see it"
  exit 1
fi

rm -rf "$WORK_DIR"
log_info "asdf/mise verification complete"
```
