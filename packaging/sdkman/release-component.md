# Release component: SDKMAN!

Script body for `scripts/release/components/sdkman.sh`:

```bash
#!/usr/bin/env bash
# Publish MockServer CLI to SDKMAN! via the Vendor API.
#
# Dry-run: show what API calls would be made, skip actual requests.

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
skip_unless_release_type "sdkman" full,post-maven

log_step "Publish SDKMAN! candidate $RELEASE_VERSION (dry-run=$DRY_RUN)"

SDKMAN_API="https://vendors.sdkman.io"
SDKMAN_KEY=$(load_secret "mockserver-release/sdkman-vendor" "consumer-key")
SDKMAN_TOKEN=$(load_secret "mockserver-release/sdkman-vendor" "consumer-token")

# TODO(cli-release): Confirm binary names and which platforms to register.
# SDKMAN! expects zip archives for platform-specific candidates.
# If the CLI ships bare binaries, wrap each in a zip during release.
BASE_URL="https://github.com/mock-server/mockserver-monorepo/releases/download/mockserver-$RELEASE_VERSION"

# Platform → binary mapping
declare -A PLATFORMS=(
  [LINUX_64]="mockserver-linux-x64.zip"
  [LINUX_ARM64]="mockserver-linux-arm64.zip"
  [MAC_OSX]="mockserver-darwin-x64.zip"
  [MAC_ARM64]="mockserver-darwin-arm64.zip"
  [WINDOWS_64]="mockserver-windows-x64.zip"
)

log_info "Register version $RELEASE_VERSION for each platform"
for platform in "${!PLATFORMS[@]}"; do
  binary="${PLATFORMS[$platform]}"
  dry_run_or "Register $platform" \
    curl -fsSL -X POST "$SDKMAN_API/release" \
      -H "Consumer-Key: $SDKMAN_KEY" \
      -H "Consumer-Token: $SDKMAN_TOKEN" \
      -H "Content-Type: application/json" \
      -d "{
        \"candidate\": \"mockserver\",
        \"version\": \"$RELEASE_VERSION\",
        \"platform\": \"$platform\",
        \"url\": \"$BASE_URL/$binary\"
      }"
done

dry_run_or "Set $RELEASE_VERSION as default" \
  curl -fsSL -X PUT "$SDKMAN_API/default" \
    -H "Consumer-Key: $SDKMAN_KEY" \
    -H "Consumer-Token: $SDKMAN_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"candidate\": \"mockserver\", \"version\": \"$RELEASE_VERSION\"}"

dry_run_or "Announce release" \
  curl -fsSL -X POST "$SDKMAN_API/announce/struct" \
    -H "Consumer-Key: $SDKMAN_KEY" \
    -H "Consumer-Token: $SDKMAN_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{
      \"candidate\": \"mockserver\",
      \"version\": \"$RELEASE_VERSION\",
      \"hashtag\": \"mockserver\"
    }"

log_info "SDKMAN! publish complete"
```
