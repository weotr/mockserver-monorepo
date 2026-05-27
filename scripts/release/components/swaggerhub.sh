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
log_info "Spec version: $(grep -E '^  version:' "$SPEC" | head -1 || echo unknown)"

SWAGGERHUB_OWNER="jamesdbloom"
SWAGGERHUB_API="mock-server-openapi"
SWAGGERHUB_BASE="https://api.swaggerhub.com/apis/${SWAGGERHUB_OWNER}/${SWAGGERHUB_API}"

if is_dry_run; then
  log_dry "skip: POST spec to SwaggerHub"
  log_dry "skip: PUT lifecycle (publish) for version $RELEASE_VERSION"
  log_dry "skip: PUT default version to $RELEASE_VERSION"
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
  log_info "Uploading spec to SwaggerHub"
  sh_api_call "upload" POST \
    "${SWAGGERHUB_BASE}?version=$RELEASE_VERSION&isPrivate=false&oas=3.0.0" \
    -H "Content-Type: application/yaml" \
    --data-binary "@$SPEC"
  case "$sh_http" in
    2[0-9][0-9]) log_info "  uploaded (HTTP $sh_http)" ;;
    409)         log_info "  version $RELEASE_VERSION already on SwaggerHub (HTTP 409) — continuing to publish + setDefault" ;;
    *)           log_error "SwaggerHub upload failed (HTTP $sh_http)"; exit 1 ;;
  esac

  # New versions are created as unpublished drafts. Mark this version as
  # published — idempotent: PUT lifecycle on an already-published version
  # returns 200 with no state change. Reference:
  # https://api.swaggerhub.com/apis/swagger-hub/registry-api/1.3.0
  # → PUT /apis/{owner}/{api}/{version}/settings/lifecycle
  log_info "Publishing version $RELEASE_VERSION on SwaggerHub"
  sh_api_call "publish" PUT \
    "${SWAGGERHUB_BASE}/${RELEASE_VERSION}/settings/lifecycle" \
    -H "Content-Type: application/json" \
    -d '{"published": true}'
  case "$sh_http" in
    2[0-9][0-9]) log_info "  published (HTTP $sh_http)" ;;
    *)           log_error "SwaggerHub publish (PUT lifecycle) failed (HTTP $sh_http)"; exit 1 ;;
  esac

  # Promote this version to be the default. Idempotent: PUT default to the
  # current default returns 200 with no state change.
  # PUT /apis/{owner}/{api}/settings/default — body {"version": "X.Y.Z"}
  log_info "Setting $RELEASE_VERSION as default version on SwaggerHub"
  sh_api_call "setDefault" PUT \
    "${SWAGGERHUB_BASE}/settings/default" \
    -H "Content-Type: application/json" \
    -d "{\"version\": \"$RELEASE_VERSION\"}"
  case "$sh_http" in
    2[0-9][0-9]) log_info "  default set to $RELEASE_VERSION (HTTP $sh_http)" ;;
    *)           log_error "SwaggerHub setDefault failed (HTTP $sh_http)"; exit 1 ;;
  esac
fi

log_info "SwaggerHub update complete"
