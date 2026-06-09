#!/usr/bin/env bash
# Publish MockServer JetBrains plugin to JetBrains Marketplace.
#
# Dry-run: build the plugin, skip publishPlugin.
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
skip_unless_release_type "jetbrains" full,post-maven

log_step "Publish JetBrains plugin $RELEASE_VERSION (dry-run=$DRY_RUN)"

COMPONENT_DIR="$REPO_ROOT/mockserver-jetbrains"
GRADLE_PROPS="$COMPONENT_DIR/gradle.properties"

# Check for Gradle wrapper
if [[ ! -x "$COMPONENT_DIR/gradlew" ]]; then
  log_info "WARNING: gradlew not found in $COMPONENT_DIR — skipping jetbrains publish (non-fatal)"
  exit 0
fi

# Bump version in gradle.properties
if ! is_dry_run; then
  log_info "Updating pluginVersion to $RELEASE_VERSION in gradle.properties"
  sed -i.bak "s/^pluginVersion=.*/pluginVersion=${RELEASE_VERSION}/" "$GRADLE_PROPS"
  rm -f "$GRADLE_PROPS.bak"
fi

# Build the plugin
log_info "Building plugin"
(cd "$COMPONENT_DIR" && ./gradlew clean buildPlugin) || {
  log_info "WARNING: gradle buildPlugin failed — skipping jetbrains publish (non-fatal)"
  exit 0
}

if is_dry_run; then
  log_dry "skip: gradle publishPlugin"
  log_info "Built plugin:"
  ls -la "$COMPONENT_DIR/build/distributions/"*.zip 2>/dev/null || true
  exit 0
fi

# Check if secret exists
if ! aws secretsmanager describe-secret --region "$REGION" \
     --secret-id mockserver-release/jetbrains >/dev/null 2>&1; then
  log_info "WARNING: mockserver-release/jetbrains secret not configured — skipping jetbrains publish (non-fatal)"
  exit 0
fi

JETBRAINS_TOKEN=$(load_secret "mockserver-release/jetbrains" "token")

log_info "Publishing to JetBrains Marketplace"
(cd "$COMPONENT_DIR" && JETBRAINS_TOKEN="$JETBRAINS_TOKEN" ./gradlew publishPlugin) || {
  log_info "WARNING: gradle publishPlugin failed — skipping (non-fatal)"
  exit 0
}

# Commit version bump
git_commit_and_push "release: publish mockserver-jetbrains $RELEASE_VERSION" \
  "$GRADLE_PROPS" || \
  log_info "WARNING: could not commit version bump (non-fatal)"

log_info "JetBrains plugin publish complete"
