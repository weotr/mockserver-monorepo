#!/usr/bin/env bash
# Regenerate the Postman & Bruno collections from the OpenAPI spec and publish the
# Postman collection to the public workspace (official-mockserver) via the Postman API.
#
# Source of truth: jekyll-www.mock-server.com/mockserver-openapi.yaml. Both collections
# are generated from it (scripts/collections/generate_collections.py) so they never
# diverge. Bruno is git-native (committed = published); only Postman needs an API push.
#
# Dry-run: regenerate, validate count/parity, optionally test against Docker, skip the
# Postman API upload.

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
require_cmd python3
require_release_inputs
skip_unless_release_type "postman-collection" full,post-maven

log_step "Publish Postman collection $RELEASE_VERSION (dry-run=$DRY_RUN)"
sync_to_origin_master

SPEC="$REPO_ROOT/jekyll-www.mock-server.com/mockserver-openapi.yaml"
[[ -f "$SPEC" ]] || { log_error "OpenAPI spec not found: $SPEC"; exit 1; }

# Guard: the spec we generate from must be this release's spec (prepare.sh bumps
# info.version). Mirrors swaggerhub.sh's version guard.
SPEC_VERSION=$(grep -E '^[[:space:]]+version:' "$SPEC" | head -1 | sed -E 's/^[[:space:]]+version:[[:space:]]*//; s/[[:space:]]*$//')
log_info "Spec version: $SPEC_VERSION"
# In dry-run, prepare.sh (which bumps the spec's info.version) is skipped and its
# commit never reaches this step's fresh checkout, so the on-disk spec still has
# the previous version. Bump it in-place to RELEASE_VERSION so the dry-run
# validates against the real release's spec; restore on exit (dry-run never
# commits). The generated collections don't embed info.version, so this does not
# perturb the drift guard below.
if is_dry_run && [[ "$SPEC_VERSION" != "$RELEASE_VERSION" ]]; then
  mkdir -p "$REPO_ROOT/.tmp"
  cp "$SPEC" "$REPO_ROOT/.tmp/postman-spec.bak"
  # shellcheck disable=SC2064  # expand the path now, not at trap-fire time
  trap "cp '$REPO_ROOT/.tmp/postman-spec.bak' '$SPEC' 2>/dev/null || true" EXIT
  _newspec="$REPO_ROOT/.tmp/postman-spec.new"
  awk -v v="$RELEASE_VERSION" 'seen!=1 && /^[[:space:]]+version:/ {sub(/version:[[:space:]]*.*/, "version: " v); seen=1} {print}' "$SPEC" > "$_newspec" && mv "$_newspec" "$SPEC"
  SPEC_VERSION=$(grep -E '^[[:space:]]+version:' "$SPEC" | head -1 | sed -E 's/^[[:space:]]+version:[[:space:]]*//; s/[[:space:]]*$//')
  [[ "$SPEC_VERSION" == "$RELEASE_VERSION" ]] || { log_error "dry-run: failed to bump spec info.version"; exit 1; }
  log_info "dry-run: bumped spec info.version to $RELEASE_VERSION in-place (not committed)"
fi
if [[ "$SPEC_VERSION" != "$RELEASE_VERSION" ]]; then
  log_error "OpenAPI spec info.version ($SPEC_VERSION) != RELEASE_VERSION ($RELEASE_VERSION)"
  exit 1
fi

# Regenerate both collections from the spec.
log_info "Regenerating collections from the OpenAPI spec"
python3 "$REPO_ROOT/scripts/collections/generate_collections.py"

# Drift guard: the committed collections must already match the spec. If regeneration
# changed them, someone edited the spec without regenerating + committing.
if ! git -C "$REPO_ROOT" diff --quiet -- examples/postman examples/bruno; then
  log_error "Generated collections differ from the committed ones — run scripts/collections/generate_collections.py and commit the result"
  git -C "$REPO_ROOT" --no-pager diff --stat -- examples/postman examples/bruno
  exit 1
fi

PM_FILE="$REPO_ROOT/examples/postman/MockServer.postman_collection.json"
REQ_COUNT=$(python3 - "$PM_FILE" <<'PY'
import json, sys
c = json.load(open(sys.argv[1]))
def n(items): return sum(n(i["item"]) if "item" in i else 1 for i in items)
print(n(c["item"]))
PY
)
log_info "Collection has $REQ_COUNT requests"

# Optional live validation against the release Docker image (best-effort; the step is soft_fail).
if command -v docker >/dev/null 2>&1 && [[ -z "${SKIP_COLLECTION_TEST:-}" ]]; then
  IMG="${MOCKSERVER_IMAGE:-mockserver/mockserver:${RELEASE_VERSION}}"
  log_info "Validating examples against $IMG"
  if MOCKSERVER_IMAGE="$IMG" python3 "$REPO_ROOT/scripts/collections/test_collections.py"; then
    log_info "  all examples accepted"
  else
    log_error "  example validation reported failures (soft — see log above)"
  fi
else
  log_info "Docker not available — skipping live example validation"
fi

# Public workspace + collection (created out-of-band; see docs/distribution/postman-public-workspace.md)
POSTMAN_WORKSPACE="1739eeee-5da1-4112-86a7-b6c094f2b527"
POSTMAN_COLLECTION_UID="3256712-63a2d67a-46d6-41fd-a544-0535e7393e7d"
POSTMAN_API="https://api.getpostman.com/collections/${POSTMAN_COLLECTION_UID}"

if is_dry_run; then
  log_dry "skip: PUT $REQ_COUNT-request collection to Postman ($POSTMAN_API)"
  log_dry "would: update public workspace $POSTMAN_WORKSPACE"
else
  API_KEY=$(load_secret "mockserver-build/postman-api-key" "api_key")
  PAYLOAD="$REPO_ROOT/.tmp/postman-update.json"
  mkdir -p "$REPO_ROOT/.tmp"
  python3 - "$PM_FILE" "$PAYLOAD" <<'PY'
import json, sys
c = json.load(open(sys.argv[1]))
json.dump({"collection": c}, open(sys.argv[2], "w"))
PY
  # Guard the API key out of any xtrace output (mirrors load_secret in _lib.sh).
  xtrace_state=$(shopt -po xtrace 2>/dev/null || true)
  set +x
  http=$(curl -sS -X PUT -o /dev/null -w '%{http_code}' \
    -H "X-Api-Key: $API_KEY" -H "Content-Type: application/json" \
    --data-binary "@$PAYLOAD" "$POSTMAN_API") \
    || { rc=$?; eval "$xtrace_state"; log_error "Postman API: curl network error (exit $rc)"; exit 1; }
  eval "$xtrace_state"
  case "$http" in
    2[0-9][0-9]) log_info "  published $REQ_COUNT requests to Postman (HTTP $http)" ;;
    *)           log_error "Postman publish failed (HTTP $http)"; exit 1 ;;
  esac
fi

log_info "Postman collection publish complete"
