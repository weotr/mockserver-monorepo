# Release component: winget

Script body for `scripts/release/components/winget.sh`:

```bash
#!/usr/bin/env bash
# Publish MockServer CLI to winget (microsoft/winget-pkgs).
#
# Dry-run: download binaries + compute hashes + generate manifest, skip PR submission.

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
require_cmd sha256sum
require_release_inputs
skip_unless_release_type "winget" full,post-maven

log_step "Publish winget manifest $RELEASE_VERSION (dry-run=$DRY_RUN)"

WORK_DIR="$REPO_ROOT/.tmp/winget-$RELEASE_VERSION"
mkdir -p "$WORK_DIR"

# TODO(cli-release): Confirm binary names once artifact naming is finalised
BINARIES=(
  "mockserver-windows-x64.exe"
  "mockserver-windows-arm64.exe"
)
BASE_URL="https://github.com/mock-server/mockserver-monorepo/releases/download/mockserver-$RELEASE_VERSION"

log_info "Download release binaries"
for bin in "${BINARIES[@]}"; do
  curl -fsSL -o "$WORK_DIR/$bin" "$BASE_URL/$bin"
done

log_info "Compute SHA256 hashes"
declare -A HASHES
for bin in "${BINARIES[@]}"; do
  HASHES["$bin"]=$(sha256sum "$WORK_DIR/$bin" | awk '{print $1}')
  log_info "  $bin: ${HASHES[$bin]}"
done

# wingetcreate update generates the manifest and optionally submits the PR.
# The --token flag uses a GitHub PAT that can create PRs on microsoft/winget-pkgs.
dry_run_or "Submit winget manifest PR" \
  wingetcreate update MockServer.MockServer \
    --version "$RELEASE_VERSION" \
    --urls "$BASE_URL/mockserver-windows-x64.exe" \
           "$BASE_URL/mockserver-windows-arm64.exe" \
    --submit \
    --token "$(load_secret "mockserver-release/winget-github-token" "token")"

rm -rf "$WORK_DIR"
log_info "winget publish complete"
```
