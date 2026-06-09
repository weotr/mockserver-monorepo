# Release component: Chocolatey

Script body for `scripts/release/components/chocolatey.sh`:

```bash
#!/usr/bin/env bash
# Publish MockServer CLI to Chocolatey.
#
# Dry-run: build .nupkg, skip `choco push`.

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
require_release_inputs
skip_unless_release_type "chocolatey" full,post-maven

log_step "Publish Chocolatey package $RELEASE_VERSION (dry-run=$DRY_RUN)"

WORK_DIR="$REPO_ROOT/.tmp/chocolatey-$RELEASE_VERSION"
mkdir -p "$WORK_DIR/tools"

# TODO(cli-release): Confirm binary names once artifact naming is finalised
BASE_URL="https://github.com/mock-server/mockserver-monorepo/releases/download/mockserver-$RELEASE_VERSION"

log_info "Download release binaries and compute SHA256"
X64_HASH=$(curl -fsSL "$BASE_URL/mockserver-windows-x64.exe" | sha256sum | awk '{print $1}')
ARM64_HASH=$(curl -fsSL "$BASE_URL/mockserver-windows-arm64.exe" | sha256sum | awk '{print $1}')

log_info "  x64:   $X64_HASH"
log_info "  arm64: $ARM64_HASH"

log_info "Substitute placeholders in nuspec and install script"
sed -e "s/\${VERSION}/$RELEASE_VERSION/g" \
    "$REPO_ROOT/packaging/chocolatey/mockserver.nuspec" > "$WORK_DIR/mockserver.nuspec"

sed -e "s/\${SHA256_WINDOWS_X64}/$X64_HASH/g" \
    -e "s/\${SHA256_WINDOWS_ARM64}/$ARM64_HASH/g" \
    "$REPO_ROOT/packaging/chocolatey/tools/chocolateyinstall.ps1" > "$WORK_DIR/tools/chocolateyinstall.ps1"

cp "$REPO_ROOT/packaging/chocolatey/tools/chocolateyuninstall.ps1" "$WORK_DIR/tools/"

log_info "Build .nupkg"
# choco pack must run on Windows or in a Docker container with Chocolatey
(cd "$WORK_DIR" && choco pack mockserver.nuspec)

NUPKG="$WORK_DIR/mockserver.$RELEASE_VERSION.nupkg"
if [[ ! -f "$NUPKG" ]]; then
  log_error "Expected nupkg not found: $NUPKG"
  exit 1
fi

dry_run_or "Push to Chocolatey" \
  choco push "$NUPKG" \
    --source https://push.chocolatey.org/ \
    --api-key "$(load_secret "mockserver-release/chocolatey-api-key" "key")"

rm -rf "$WORK_DIR"
log_info "Chocolatey publish complete"
```
