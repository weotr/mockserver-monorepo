#!/usr/bin/env bash
# Release the mockserver-maven-plugin to Maven Central.
#
# The plugin inherits its version from the mockserver parent pom and uses
# ${project.version} for its internal mockserver-* dependency references, so
# version bumps are handled by the main maven-central release component. This
# script just builds the core mockserver (so the plugin can resolve it), then
# tags and deploys the plugin.
#
# Dry-run: build + verify only; skip tag + deploy.

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

require_cmd docker
require_cmd git
require_cmd curl
require_release_inputs
skip_unless_release_type "maven-plugin" full,post-maven

log_step "Release mockserver-maven-plugin $RELEASE_VERSION (dry-run=$DRY_RUN)"
sync_to_origin_master

# Idempotent: if this version is already on Maven Central the plugin release
# is done. A re-run must not redeploy an existing version - Central rejects it.
if ! is_dry_run && curl -fsI -o /dev/null \
    "https://repo1.maven.org/maven2/org/mock-server/mockserver-maven-plugin/$RELEASE_VERSION/mockserver-maven-plugin-$RELEASE_VERSION.pom"; then
  log_info "mockserver-maven-plugin $RELEASE_VERSION already on Maven Central - skipping"
  exit 0
fi

log_info "Build core MockServer (in Docker)"
in_maven -w /build/mockserver \
  -- mvn clean install -DskipTests

if is_dry_run; then
  log_info "Verify maven-plugin (in Docker, tests skipped in dry-run)"
  in_maven -w /build/mockserver/mockserver-maven-plugin -- mvn clean install -DskipTests
else
  log_info "Verify maven-plugin (in Docker)"
  in_maven -w /build/mockserver/mockserver-maven-plugin -- mvn clean verify
fi

if is_dry_run; then
  log_dry "skip: tag, deploy"
  log_info "maven-plugin dry-run complete"
  exit 0
fi

git_tag_and_push "maven-plugin-$RELEASE_VERSION"

log_info "Deploy maven-plugin to Sonatype (GPG-sign in container)"
GPG_KEY_B64=$(load_secret "mockserver-release/gpg-key" "key")
GPG_PASSPHRASE=$(load_secret "mockserver-release/gpg-key" "passphrase")
SONATYPE_USERNAME=$(load_secret "mockserver-build/sonatype" "username")
SONATYPE_PASSWORD=$(load_secret "mockserver-build/sonatype" "password")

in_docker "$MAVEN_IMAGE" \
  -w /build/mockserver/mockserver-maven-plugin \
  -v mockserver-m2-cache:/root/.m2 \
  -e "GPG_KEY_B64=$GPG_KEY_B64" \
  -e "GPG_PASSPHRASE=$GPG_PASSPHRASE" \
  -e "SONATYPE_USERNAME=$SONATYPE_USERNAME" \
  -e "SONATYPE_PASSWORD=$SONATYPE_PASSWORD" \
  -- bash -ec '
    apt-get update -qq >/dev/null
    apt-get install -y -qq gnupg >/dev/null
    set +x
    echo "$GPG_KEY_B64" | base64 -d | gpg --batch --import
    mkdir -p ~/.gnupg
    echo "allow-loopback-pinentry" >> ~/.gnupg/gpg-agent.conf
    gpgconf --reload gpg-agent 2>/dev/null || true
    cat > /tmp/settings.xml <<SETTINGS
<?xml version="1.0" encoding="UTF-8"?>
<settings>
  <servers>
    <server>
      <id>central-portal</id>
      <username>${SONATYPE_USERNAME}</username>
      <password>${SONATYPE_PASSWORD}</password>
    </server>
    <server>
      <id>gpg.passphrase</id>
      <passphrase>${GPG_PASSPHRASE}</passphrase>
    </server>
  </servers>
</settings>
SETTINGS
    mvn deploy -P release -DskipTests \
      -Dgpg.passphraseServerId=gpg.passphrase \
      -Dgpg.useagent=false \
      --settings /tmp/settings.xml
  '

log_info "maven-plugin release complete"
