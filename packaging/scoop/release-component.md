# Release component: Scoop

Script body for `scripts/release/components/scoop.sh`:

```bash
#!/usr/bin/env bash
# Publish MockServer CLI to Scoop (mock-server/scoop-mockserver bucket).
#
# Dry-run: download binaries + compute hashes + generate manifest, skip git push.
# With autoupdate configured, ongoing maintenance is near-zero — Scoop's
# checkver bot can also bump the manifest automatically.

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
require_cmd jq
require_release_inputs
skip_unless_release_type "scoop" full,post-maven

log_step "Publish Scoop manifest $RELEASE_VERSION (dry-run=$DRY_RUN)"

WORK_DIR="$REPO_ROOT/.tmp/scoop-$RELEASE_VERSION"
mkdir -p "$WORK_DIR"

# TODO(cli-release): Confirm binary names once artifact naming is finalised
BASE_URL="https://github.com/mock-server/mockserver-monorepo/releases/download/mockserver-$RELEASE_VERSION"

log_info "Download release binaries and compute SHA256"
X64_HASH=$(curl -fsSL "$BASE_URL/mockserver-windows-x64.exe" | tee "$WORK_DIR/mockserver-windows-x64.exe" | sha256sum | awk '{print $1}')
ARM64_HASH=$(curl -fsSL "$BASE_URL/mockserver-windows-arm64.exe" | tee "$WORK_DIR/mockserver-windows-arm64.exe" | sha256sum | awk '{print $1}')

log_info "  x64:   $X64_HASH"
log_info "  arm64: $ARM64_HASH"

log_info "Generate manifest"
# Use the template from packaging/scoop/ and substitute placeholders
sed -e "s/\${VERSION}/$RELEASE_VERSION/g" \
    -e "s/\${SHA256_WINDOWS_X64}/$X64_HASH/g" \
    -e "s/\${SHA256_WINDOWS_ARM64}/$ARM64_HASH/g" \
    "$REPO_ROOT/packaging/scoop/mockserver.json" > "$WORK_DIR/mockserver.json"

# Remove the _comment field (it's only for the template)
jq 'del(._comment)' "$WORK_DIR/mockserver.json" > "$WORK_DIR/mockserver-clean.json"
mv "$WORK_DIR/mockserver-clean.json" "$WORK_DIR/mockserver.json"

log_info "Manifest content:"
jq . "$WORK_DIR/mockserver.json" | sed 's/^/    /'

dry_run_or "Push manifest to mock-server/scoop-mockserver" bash -c '
  GITHUB_TOKEN=$(load_secret "mockserver-release/github-token" "token")
  BUCKET_DIR="'"$WORK_DIR"'/scoop-mockserver"
  git clone "https://x-access-token:${GITHUB_TOKEN}@github.com/mock-server/scoop-mockserver.git" "$BUCKET_DIR"
  cp "'"$WORK_DIR"'/mockserver.json" "$BUCKET_DIR/mockserver.json"
  cd "$BUCKET_DIR"
  git add mockserver.json
  git commit -m "mockserver '"$RELEASE_VERSION"'"
  git push
'

rm -rf "$WORK_DIR"
log_info "Scoop publish complete"
```
