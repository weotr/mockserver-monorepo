#!/usr/bin/env bash
# Finalize a release: bump the root pom to the next SNAPSHOT, deploy that
# SNAPSHOT to Sonatype, commit, push.
#
# Version references in docs/configs across the repo (changelog, jekyll
# _config.yml, package.json files, etc.) used to be updated at the tail of
# this script. They were extracted into update-version-references.sh and
# moved earlier in the release pipeline so that the parallel deploy group
# (website, helm, docker, npm) reads the new version when it runs.
#
# Dry-run: skip the SNAPSHOT deploy and git push; everything else still runs.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
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
require_release_inputs
skip_unless_release_type "finalize" full,maven-only,post-maven

log_step "Finalize release $RELEASE_VERSION (dry-run=$DRY_RUN)"
sync_to_origin_master

# ---- Deploy next SNAPSHOT to Sonatype --------------------------------------
log_info "Bump pom.xml: $RELEASE_VERSION -> $NEXT_VERSION"
if is_dry_run; then
  log_dry "would: update_pom_versions mockserver/ $RELEASE_VERSION $NEXT_VERSION"
else
  update_pom_versions "$REPO_ROOT/mockserver" "$RELEASE_VERSION" "$NEXT_VERSION"
fi

if is_dry_run; then
  log_dry "skip: mvn deploy SNAPSHOT to Sonatype"
else
  SONATYPE_USERNAME=$(load_secret "mockserver-build/sonatype" "username")
  SONATYPE_PASSWORD=$(load_secret "mockserver-build/sonatype" "password")
  in_docker "$MAVEN_IMAGE" \
    -w /build/mockserver \
    -v mockserver-m2-cache:/root/.m2 \
    -e "SONATYPE_USERNAME=$SONATYPE_USERNAME" \
    -e "SONATYPE_PASSWORD=$SONATYPE_PASSWORD" \
    -- mvn -T 1C clean deploy -DskipTests \
         -Djava.security.egd=file:/dev/./urandom \
         --settings .buildkite-settings.xml
fi

git_commit_and_push "release: set next development version $NEXT_VERSION" mockserver/

log_info "Finalize complete"
