#!/usr/bin/env bash
# Publish MockServer JetBrains plugin to JetBrains Marketplace.
#
# Dry-run: bump version (host edit) + build the plugin (buildPlugin), skip publish.
# HARD: a real build/publish failure aborts the step. The build and publish run
# inside the pinned Maven image ($MAVEN_IMAGE — it ships JDK 17; the module's
# ./gradlew wrapper downloads its own Gradle), so no host gradle/JDK is required.
# This replaced a host `command -v gradlew`/gradle probe that silently `exit 0`d
# on the release-queue agent (Java/Maven/Docker only), so the plugin never
# actually published.

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

# Bump pluginVersion in gradle.properties. Host edit — gradle.properties is on
# the bind-mounted repo, so the in-container build/publish sees the bumped
# version. Done with sed so no host gradle/JDK is required.
if ! is_dry_run; then
  log_info "Updating pluginVersion to $RELEASE_VERSION in gradle.properties"
  sed -i.bak "s/^pluginVersion=.*/pluginVersion=${RELEASE_VERSION}/" "$GRADLE_PROPS"
  rm -f "$GRADLE_PROPS.bak"
fi

# Build the plugin in the pinned Maven image (JDK 17; the ./gradlew wrapper
# downloads its own Gradle, and intellij-platform/Kotlin plugins resolve from
# Maven Central + the Gradle plugin portal). HARD: a build failure aborts the
# step (set -e propagates the in_docker non-zero). Retry to ride out transient
# Gradle / Maven Central / plugin-portal download blips on a cold build.
#
# The ${ca_install_prelude} (from _lib.sh) imports the host corp CA into the
# container's OS trust store AND the JDK cacerts truststore when one is mounted
# (behind a TLS-inspecting proxy). The JVM ignores SSL_CERT_FILE/NODE_EXTRA_CA_CERTS,
# so Gradle's HTTPS downloads need the cacerts import that plain in_docker does
# not do. Silent no-op in CI (no proxy → no CA mounted), so the same body works
# both places. Runs under bash -ec because the prelude is multi-line shell.
log_info "Building plugin (buildPlugin) in $MAVEN_IMAGE"
retry 3 5 -- in_docker "$MAVEN_IMAGE" -w /build/mockserver-jetbrains -- \
  bash -ec "${ca_install_prelude}./gradlew clean buildPlugin"

if is_dry_run; then
  log_dry "skip: gradle publishPlugin"
  log_info "Built plugin:"
  ls -la "$COMPONENT_DIR/build/distributions/"*.zip 2>/dev/null || true
  exit 0
fi

# --- JetBrains Marketplace (publishPlugin) ---
# The upload token EXISTS (mockserver-release/jetbrains, key: token). A
# genuinely-missing secret is a hard failure now. The token is passed via -e
# (redacted in the logged command) and read by the build INSIDE the
# single-quoted bash -ec body straight from the JETBRAINS_TOKEN environment
# variable — the literal token never lands in the logged command args
# (run-in-docker does NOT redact the command body). build.gradle.kts reads it
# via providers.environmentVariable("JETBRAINS_TOKEN"). HARD-fail on a real
# publish error, with retry for transient blips. ${ca_install_prelude} handles
# the corp-proxy CA exactly as the build step above (no-op in CI).
JETBRAINS_TOKEN=$(load_secret "mockserver-release/jetbrains" "token")
if [[ -z "$JETBRAINS_TOKEN" || "$JETBRAINS_TOKEN" == "null" ]]; then
  log_error "mockserver-release/jetbrains secret missing/empty — cannot publish to JetBrains Marketplace"
  exit 1
fi
# Idempotent: re-running a release (or a prior build that already uploaded the
# plugin) makes publishPlugin fail with "… already contains version X.Y.Z in
# channel …" — that is the desired end state, so run_idempotent treats it as
# success while still HARD-failing on any other publish error.
log_info "Publishing to JetBrains Marketplace from $MAVEN_IMAGE"
retry 3 5 -- run_idempotent 'already contains version|already exists' -- in_docker "$MAVEN_IMAGE" \
  -e "JETBRAINS_TOKEN=$JETBRAINS_TOKEN" \
  -w /build/mockserver-jetbrains -- \
  bash -ec "${ca_install_prelude}"'./gradlew publishPlugin'

# Commit version bump (best-effort — publish already succeeded).
git_commit_and_push "release: publish mockserver-jetbrains $RELEASE_VERSION" \
  "$GRADLE_PROPS" || \
  log_info ":warning: could not commit version bump (non-fatal — publish already succeeded)"

log_info "JetBrains plugin publish complete"
