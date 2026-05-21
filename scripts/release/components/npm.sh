#!/usr/bin/env bash
# Publish mockserver-node and mockserver-client-node to npm.
#
# Dry-run mode: install + lint + grunt build + `npm publish --dry-run`.
# npm has a native --dry-run that goes through everything except the upload.

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
require_cmd curl
require_cmd jq
require_release_inputs
skip_unless_release_type "npm" full,post-maven

log_step "Publish npm packages $RELEASE_VERSION (dry-run=$DRY_RUN)"
sync_to_origin_master

publish_one() {
  local pkg="$1"
  # Idempotent: if this package version is already on npm, an earlier run
  # published it. The npm package name can differ from the directory name
  # (mockserver-client-node publishes as "mockserver-client"), so read it.
  local npm_name
  npm_name=$(jq -r '.name' "$REPO_ROOT/$pkg/package.json")
  if ! is_dry_run && curl -fsI --connect-timeout 10 --max-time 15 -o /dev/null \
      "https://registry.npmjs.org/$npm_name/$RELEASE_VERSION" 2>/dev/null; then
    log_info "[$pkg] $npm_name@$RELEASE_VERSION already on npm - skipping"
    return 0
  fi
  # In dry-run we still exercise the full "rm lockfile + npm install" path
  # so the test stays faithful to the real release, but we restore the
  # committed package-lock.json afterwards so the working tree is clean.
  if is_dry_run; then
    mkdir -p "$REPO_ROOT/.tmp"
    cp "$REPO_ROOT/$pkg/package-lock.json" "$REPO_ROOT/.tmp/$pkg.package-lock.json.bak"
    # shellcheck disable=SC2064  # expand $pkg now, not at trap-fire time
    trap "cp '$REPO_ROOT/.tmp/$pkg.package-lock.json.bak' '$REPO_ROOT/$pkg/package-lock.json' 2>/dev/null || true" RETURN
  fi
  log_info "[$pkg] build"
  in_docker "$NODE_IMAGE" \
    -w "/build/$pkg" \
    -e "PKG_DIR=$pkg" \
    -- bash -ec '
      rm -rf package-lock.json node_modules
      attempts=0
      until npm i; do
        attempts=$((attempts + 1))
        if [ "$attempts" -ge 5 ]; then
          echo "npm install failed after ${attempts} attempts"; exit 1
        fi
        echo "npm install failed, retrying in 15s"; sleep 15
      done
      # Release grunt invocation: package + lint only. The default grunt
      # task includes integration tests that start a real MockServer JVM,
      # which would require Java in this node container (we do not, and
      # do not need to, install Java here).
      if [ "$PKG_DIR" = "mockserver-node" ]; then
        npm audit fix 2>/dev/null || true
        npx grunt deleted_jars download_jar jshint
      else
        npx grunt jshint
      fi
    '

  if is_dry_run; then
    log_dry "skip: commit + push + tag + npm publish"
    log_info "[$pkg] npm publish --dry-run"
    in_docker "$NODE_IMAGE" \
      -w "/build/$pkg" \
      -- npm publish --dry-run --access=public
    return
  fi

  log_info "[$pkg] commit build artifacts"
  git_commit_and_push "release: publish $pkg $RELEASE_VERSION" "$pkg"
  git_tag_and_push "$pkg-$RELEASE_VERSION"

  log_info "[$pkg] npm publish"
  local npm_token
  npm_token=$(load_secret "mockserver-release/npm-token" "token")
  in_docker "$NODE_IMAGE" \
    -w "/build/$pkg" \
    -e "NPM_TOKEN=$npm_token" \
    -- bash -ec '
      set +x
      cat > /tmp/.npmrc <<NPMRC
//registry.npmjs.org/:_authToken=${NPM_TOKEN}
registry=https://registry.npmjs.org/
always-auth=true
NPMRC
      export NPM_CONFIG_USERCONFIG=/tmp/.npmrc
      npm whoami >/dev/null || { echo "npm authentication failed"; exit 1; }
      npm publish --access=public
    '

  # npm is eventually consistent — block until the just-published version is
  # actually resolvable from the registry before returning. mockserver-client-node
  # is published second and depends on mockserver-node, so its `npm install`
  # must not run until mockserver-node's new version is downloadable.
  log_info "[$pkg] waiting for $npm_name@$RELEASE_VERSION to be installable from npm"
  local waited=0
  # 300s ceiling — npm global propagation is typically well under a minute.
  until curl -fsI --connect-timeout 10 --max-time 15 -o /dev/null \
      "https://registry.npmjs.org/$npm_name/$RELEASE_VERSION" 2>/dev/null; do
    if [[ "$waited" -ge 300 ]]; then
      log_error "[$pkg] $npm_name@$RELEASE_VERSION still not visible on npm after ${waited}s"
      return 1
    fi
    log_info "[$pkg] not yet visible on npm (${waited}s elapsed), waiting..."
    sleep 10
    waited=$((waited + 10))
  done
  log_info "[$pkg] $npm_name@$RELEASE_VERSION is live on npm"
}

publish_one mockserver-node
publish_one mockserver-client-node

log_info "npm publish complete"
