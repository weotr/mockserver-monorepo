#!/usr/bin/env bash
# Update the MockServer OpenAPI spec on SwaggerHub.
#
# Dry-run: validate spec exists, skip POST to SwaggerHub.

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
skip_unless_release_type "swaggerhub" full,post-maven

log_step "Update SwaggerHub $RELEASE_VERSION (dry-run=$DRY_RUN)"
sync_to_origin_master

SPEC="$REPO_ROOT/mockserver/mockserver-core/src/main/resources/org/mockserver/openapi/mock-server-openapi-embedded-model.yaml"
[[ -f "$SPEC" ]] || { log_error "OpenAPI spec not found: $SPEC"; exit 1; }
log_info "Spec: $SPEC"
# Extract the info.version field — the indented `  version:` form so we don't
# match the top-level openapi: version line. prepare.sh bumps this to
# $RELEASE_VERSION as part of the same commit that bumps Maven poms (audit
# F-SH-01); if it doesn't match here, prepare.sh failed to bump the on-disk
# spec and we'd publish the wrong release's API surface. Fail-fast. (The
# uploaded copy's info.version is relabelled to the .x form below; this guard
# is about the on-disk spec being the correct release content.)
SPEC_VERSION=$(grep -E '^[[:space:]]+version:' "$SPEC" | head -1 | sed -E 's/^[[:space:]]+version:[[:space:]]*//; s/[[:space:]]*$//')
log_info "Spec version: $SPEC_VERSION"
# In dry-run, prepare.sh (which bumps the spec's info.version) is skipped and its
# commit never reaches this step's fresh checkout, so the on-disk spec still has
# the previous version. Bump it in-place to RELEASE_VERSION so the dry-run
# validates the real release's spec; restore on exit (dry-run never commits).
if is_dry_run && [[ "$SPEC_VERSION" != "$RELEASE_VERSION" ]]; then
  mkdir -p "$REPO_ROOT/.tmp"
  cp "$SPEC" "$REPO_ROOT/.tmp/swaggerhub-spec.bak"
  # shellcheck disable=SC2064  # expand the path now, not at trap-fire time
  trap "cp '$REPO_ROOT/.tmp/swaggerhub-spec.bak' '$SPEC' 2>/dev/null || true" EXIT
  _newspec="$REPO_ROOT/.tmp/swaggerhub-spec.new"
  awk -v v="$RELEASE_VERSION" 'seen!=1 && /^[[:space:]]+version:/ {sub(/version:[[:space:]]*.*/, "version: " v); seen=1} {print}' "$SPEC" > "$_newspec" && mv "$_newspec" "$SPEC"
  SPEC_VERSION=$(grep -E '^[[:space:]]+version:' "$SPEC" | head -1 | sed -E 's/^[[:space:]]+version:[[:space:]]*//; s/[[:space:]]*$//')
  [[ "$SPEC_VERSION" == "$RELEASE_VERSION" ]] || { log_error "dry-run: failed to bump spec info.version"; exit 1; }
  log_info "dry-run: bumped spec info.version to $RELEASE_VERSION in-place (not committed)"
fi
if [[ "$SPEC_VERSION" != "$RELEASE_VERSION" ]]; then
  log_error "OpenAPI spec info.version ($SPEC_VERSION) does not match RELEASE_VERSION ($RELEASE_VERSION) — refusing to upload a spec whose body claims the wrong version"
  exit 1
fi

SWAGGERHUB_OWNER="jamesdbloom"
SWAGGERHUB_API="mock-server-openapi"
SWAGGERHUB_BASE="https://api.swaggerhub.com/apis/${SWAGGERHUB_OWNER}/${SWAGGERHUB_API}"

# SwaggerHub registers each spec under the major.minor ".x" version label (e.g.
# 7.0.x), NOT the full patch version. This matches the website links
# (mockserver_api_version, set to ${MAJOR}.${MINOR}.x by
# update-version-references.sh), the README versions table, and every
# historical version (5.13.x, 5.14.x, 5.15.x, 6.0.x, ...). Uploading under the
# full version (as a regression once did for 6.1.0 and 7.0.0) leaves every
# ".x" link 404ing.
#
# Both the registry label AND the uploaded spec body's info.version must be the
# ".x" form: every correctly-published historical version has body == label
# (e.g. 6.0.x's body info.version is "6.0.x"), so we rewrite info.version in a
# throwaway copy of the spec before upload. The on-disk spec keeps the full
# $RELEASE_VERSION (asserted above) — only the uploaded copy is relabelled.
MAJOR="${RELEASE_VERSION%%.*}"
MINOR_REST="${RELEASE_VERSION#*.}"
MINOR="${MINOR_REST%%.*}"
API_VERSION="${MAJOR}.${MINOR}.x"
log_info "SwaggerHub registry version label + spec body version: $API_VERSION"

if is_dry_run; then
  log_dry "skip: POST spec to SwaggerHub"
  log_dry "skip: PUT lifecycle (publish) for version $API_VERSION"
  log_dry "skip: PUT default version to $API_VERSION"
else
  API_KEY=$(load_secret "mockserver-release/swaggerhub" "api_key")

  # Small helper: do a SwaggerHub API call, capture HTTP status, and dispatch
  # by status. Encapsulates the curl-level vs HTTP-level failure split so each
  # caller below stays readable. Usage:
  #   sh_api_call <label> <method> <url> [curl args...]
  # Caller sets `sh_http` to the captured HTTP code on return, or the helper
  # aborts the script (with `log_error`) on a curl-level failure.
  sh_api_call() {
    local label="$1" method="$2" url="$3"; shift 3
    sh_http=$(curl -sS -X "$method" -o /dev/null -w '%{http_code}' \
      -H "Authorization: $API_KEY" "$@" "$url") \
      || { log_error "SwaggerHub $label: curl network error (exit $?) for $method $url"; exit 1; }
  }

  # SwaggerHub Registry API: the save-definition operation is POST on the
  # un-versioned /apis/{owner}/{api} path with the version passed as a query
  # parameter. POST to /apis/{owner}/{api}/{version} returns 405 — that path
  # only supports GET and DELETE.
  #
  # Idempotency: if this exact version already exists, SwaggerHub returns
  # HTTP 409 Conflict. We treat that as success and continue on to the
  # publish + setDefault steps, which are inherently idempotent (PUT
  # to current desired state returns 200 with no change).
  # Relabel info.version to the .x form in a throwaway copy (see the note above).
  # Replace only the FIRST indented `version:` line — that is info.version; any
  # later indented `version:` lines would be inside examples/schemas.
  UPLOAD_SPEC="$REPO_ROOT/.tmp/swaggerhub-upload-${API_VERSION}.yaml"
  mkdir -p "$REPO_ROOT/.tmp"
  awk -v ver="$API_VERSION" \
    '!done && /^[[:space:]]+version:/ { sub(/version:.*/, "version: " ver); done=1 } { print }' \
    "$SPEC" > "$UPLOAD_SPEC"

  log_info "Uploading spec to SwaggerHub"
  sh_api_call "upload" POST \
    "${SWAGGERHUB_BASE}?version=$API_VERSION&isPrivate=false&oas=3.0.0" \
    -H "Content-Type: application/yaml" \
    --data-binary "@$UPLOAD_SPEC"
  case "$sh_http" in
    2[0-9][0-9]) log_info "  uploaded (HTTP $sh_http)" ;;
    409)         log_info "  version $API_VERSION already on SwaggerHub (HTTP 409) — continuing to publish + setDefault" ;;
    *)           log_error "SwaggerHub upload failed (HTTP $sh_http)"; exit 1 ;;
  esac

  # New versions are created as unpublished drafts. Mark this version as
  # published — idempotent: PUT lifecycle on an already-published version
  # returns 200 with no state change. Reference:
  # https://api.swaggerhub.com/apis/swagger-hub/registry-api/1.3.0
  # → PUT /apis/{owner}/{api}/{version}/settings/lifecycle
  log_info "Publishing version $API_VERSION on SwaggerHub"
  sh_api_call "publish" PUT \
    "${SWAGGERHUB_BASE}/${API_VERSION}/settings/lifecycle" \
    -H "Content-Type: application/json" \
    -d '{"published": true}'
  case "$sh_http" in
    2[0-9][0-9]) log_info "  published (HTTP $sh_http)" ;;
    *)           log_error "SwaggerHub publish (PUT lifecycle) failed (HTTP $sh_http)"; exit 1 ;;
  esac

  # Promote this version to be the default. Idempotent: PUT default to the
  # current default returns 200 with no state change.
  # PUT /apis/{owner}/{api}/settings/default — body {"version": "X.Y.Z"}
  log_info "Setting $API_VERSION as default version on SwaggerHub"
  sh_api_call "setDefault" PUT \
    "${SWAGGERHUB_BASE}/settings/default" \
    -H "Content-Type: application/json" \
    -d "{\"version\": \"$API_VERSION\"}"
  case "$sh_http" in
    2[0-9][0-9]) log_info "  default set to $API_VERSION (HTTP $sh_http)" ;;
    *)           log_error "SwaggerHub setDefault failed (HTTP $sh_http)"; exit 1 ;;
  esac
fi

log_info "SwaggerHub update complete"
