#!/usr/bin/env bash
# Build per-platform, JVM-less MockServer binary bundles (jlink runtime + shaded
# jar + launcher) for every supported OS/arch and upload them as assets on the
# GitHub Release "mockserver-<version>".
#
# Ordering: runs AFTER the `github` component, which creates the release (with the
# changelog notes). This component only UPLOADS assets to that existing release; it
# never creates it (so it can't accidentally produce a notes-less release).
#
# Dry-run: builds the bundles (to validate the build), skips the GitHub upload.
# Set BINARY_TARGETS to a subset (e.g. "linux/x86_64 windows/x86_64") to limit the
# platforms built — useful for local dry-run testing. Default: all platforms.
#
# Agent requirements: a JDK matching --jdk-version (default 21) providing jlink,
# plus curl, tar and unzip, and ~2 GB scratch for the cached target JDKs.

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
skip_unless_release_type "binary" full,post-maven
require_cmd docker
require_cmd curl
require_cmd tar
require_cmd unzip
# Bundles are built with the host jlink against same-major target jmods, so this
# build needs a JDK 21 jlink. The release-queue agent's AMI may only ship the
# Maven build JDK (17), so we bootstrap Temurin 21 on demand (jlink only — the
# Maven build is untouched) rather than hard-failing when 21 is absent. This
# mirrors the snapshot bundle CI step and is why 7.0.0 shipped with no bundle
# assets: the old hard `exit 1` here, under a soft_fail step, silently dropped
# every asset and the launchers 404'd at runtime.
source "$REPO_ROOT/scripts/lib/ensure-host-jdk21.sh"
JDK21_HOME="$(ensure_host_jdk21 "$REPO_ROOT/.tmp/jdks")" || { log_error "could not provision a JDK 21 jlink for binary bundles"; exit 1; }
log_info "Using JDK 21 for jlink (Maven build unaffected): $JDK21_HOME"

log_step "Build & publish binary bundles $RELEASE_VERSION (dry-run=$DRY_RUN)"
sync_to_origin_master
cd "$REPO_ROOT"

# ---- locate or fetch the shaded JAR (mirrors docker.sh) -------------------
find_local_shaded() {
  find mockserver/mockserver-netty-no-dependencies/target \
    -name 'mockserver-netty-no-dependencies-*.jar' \
    ! -name '*-sources.jar' ! -name '*-javadoc.jar' ! -name 'original-*' \
    -print -quit 2>/dev/null || true
}
SHADED_JAR=$(find_local_shaded)
if [[ -z "$SHADED_JAR" ]]; then
  mkdir -p mockserver/mockserver-netty-no-dependencies/target
  CENTRAL_URL="https://repo1.maven.org/maven2/org/mock-server/mockserver-netty-no-dependencies/${RELEASE_VERSION}/mockserver-netty-no-dependencies-${RELEASE_VERSION}.jar"
  SHADED_JAR="mockserver/mockserver-netty-no-dependencies/target/mockserver-netty-no-dependencies-${RELEASE_VERSION}.jar"
  if is_dry_run && ! curl -sf -I "$CENTRAL_URL" >/dev/null 2>&1; then
    log_dry "skip: download $RELEASE_VERSION JAR (not yet on Maven Central)"
    SHADED_JAR=$(find_local_shaded)
    if [[ -z "$SHADED_JAR" ]]; then
      log_dry "no local JAR — building one with mvn package"
      in_maven -w /build/mockserver -- mvn -DskipTests -pl mockserver-netty-no-dependencies -am package
      SHADED_JAR=$(find_local_shaded)
    fi
  else
    log_info "Downloading shaded JAR from Maven Central"
    curl -fsSL --max-time 300 --connect-timeout 30 --retry 3 --retry-delay 5 -o "$SHADED_JAR" "$CENTRAL_URL"
  fi
fi
[[ -n "$SHADED_JAR" && -f "$SHADED_JAR" ]] || { log_error "No shaded JAR available"; exit 1; }
log_info "Using JAR: $SHADED_JAR"

# ---- build all platform bundles ------------------------------------------
BUNDLE_OUT="$REPO_ROOT/.tmp/bundles"
rm -rf "$BUNDLE_OUT"
TARGETS_ARG=()
[[ -n "${BINARY_TARGETS:-}" ]] && TARGETS_ARG=(--targets "$BINARY_TARGETS")
# JAVA_HOME points build-all-bundles.sh at the JDK 21 jlink resolved above for
# THIS invocation only (a subshell env), so the Maven 17 build is never affected.
JAVA_HOME="$JDK21_HOME" "$REPO_ROOT/scripts/build-all-bundles.sh" \
  --jar "$SHADED_JAR" --version "$RELEASE_VERSION" --jdk-version 21 \
  --cache "$REPO_ROOT/.tmp/jdks" --output "$BUNDLE_OUT" \
  "${TARGETS_ARG[@]+"${TARGETS_ARG[@]}"}"

# Collect assets as repo-root-relative paths (for the /build mount in in_docker).
# Portable array fill (no `mapfile` — absent in the bash 3.2 shipped on macOS,
# where these release scripts are also dry-run tested).
ASSETS=()
while IFS= read -r _asset; do
  [[ -n "$_asset" ]] && ASSETS+=("$_asset")
done < <(cd "$REPO_ROOT" && \
  ls .tmp/bundles/mockserver-"$RELEASE_VERSION"-*.tar.gz \
     .tmp/bundles/mockserver-"$RELEASE_VERSION"-*.zip \
     .tmp/bundles/mockserver-"$RELEASE_VERSION"-*.sha256 2>/dev/null || true)
[[ ${#ASSETS[@]} -gt 0 ]] || { log_error "No bundle assets produced"; exit 1; }
log_info "Built ${#ASSETS[@]} assets:"
printf '    %s\n' "${ASSETS[@]##*/}"

# ---- upload to the GitHub Release -----------------------------------------
if is_dry_run; then
  log_dry "skip: gh release upload mockserver-$RELEASE_VERSION --clobber (${#ASSETS[@]} assets)"
else
  GITHUB_TOKEN=$(load_secret "mockserver-release/github-token" "token")
  # Uploads to the release created by the `github` component. --clobber makes
  # re-runs idempotent. Fails loudly if the release does not exist (which would
  # mean this ran before `github` — an ordering bug to surface, not paper over).
  in_docker "$GH_IMAGE" \
    -w /build \
    -e "GITHUB_TOKEN=$GITHUB_TOKEN" \
    -- release upload "mockserver-$RELEASE_VERSION" --clobber "${ASSETS[@]}"
fi

log_info "Binary bundle publishing complete"
