#!/usr/bin/env bash
# Publish the MockServer MCP server manifest (server.json) to the official MCP
# registry (registry.modelcontextprotocol.io).
#
# Namespace: com.mock-server/mockserver — a DNS-verified namespace derived from
# the mock-server.com domain we own. Authentication is fully non-interactive
# (an ed25519 private key in Secrets Manager + a one-time DNS TXT record), so
# unlike the old io.github.mock-server/* namespace (interactive GitHub OAuth)
# this can run hands-off in Buildkite.
#
# STRICTLY non-fatal / soft: the MCP registry is a discovery surface, never a
# release gate. Every failure mode (missing secret, missing CLI, auth failure,
# label mismatch, publish error) logs a warning and returns 0 — the Buildkite
# step is also soft_fail.
#
# Dry-run: validate the committed server.json only; never mutate it; skip
# auth + publish.

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

require_cmd jq
require_release_inputs
skip_unless_release_type "mcp" full,post-maven

log_step "Publish MCP server manifest $RELEASE_VERSION (dry-run=$DRY_RUN)"
sync_to_origin_master

SERVER_JSON="$REPO_ROOT/server.json"
SERVER_NAME="com.mock-server/mockserver"
MCP_DOMAIN="mock-server.com"
IMAGE_REF="docker.io/mockserver/mockserver:${RELEASE_VERSION}"
# Pin the CLI version; optionally pin its sha256 via MCP_PUBLISHER_SHA256 to
# verify the download (left unset by default — the step is soft, so an unset
# checksum only means "skip MCP" on a bad download, never a release abort).
MCP_PUBLISHER_VERSION="${MCP_PUBLISHER_VERSION:-1.2.3}"
MCP_PUBLISHER_SHA256="${MCP_PUBLISHER_SHA256:-}"

[[ -f "$SERVER_JSON" ]] || { log_info "WARNING: $SERVER_JSON missing — skipping MCP publish"; exit 0; }

# ---- Locate the mcp-publisher CLI ------------------------------------------
# Prefer one already on the agent; otherwise download the pinned release into
# .tmp/. The step is soft, so a download/verify failure simply skips publishing.
MCP_BIN=""
if command -v mcp-publisher >/dev/null 2>&1; then
  MCP_BIN="mcp-publisher"
else
  os="linux"; arch="amd64"
  case "$(uname -m)" in aarch64|arm64) arch="arm64" ;; esac
  case "$(uname -s)" in Darwin) os="darwin" ;; esac
  url="https://github.com/modelcontextprotocol/registry/releases/download/v${MCP_PUBLISHER_VERSION}/mcp-publisher_${MCP_PUBLISHER_VERSION}_${os}_${arch}.tar.gz"
  log_info "mcp-publisher not on PATH — downloading v${MCP_PUBLISHER_VERSION} ($os/$arch)"
  mkdir -p "$REPO_ROOT/.tmp"
  if ! curl -fsSL --max-time 120 -o "$REPO_ROOT/.tmp/mcp-publisher.tgz" "$url" 2>/dev/null; then
    log_info "WARNING: could not download mcp-publisher v${MCP_PUBLISHER_VERSION} — skipping MCP publish (non-fatal)"
    exit 0
  fi
  if [[ -n "$MCP_PUBLISHER_SHA256" ]]; then
    if ! echo "${MCP_PUBLISHER_SHA256}  $REPO_ROOT/.tmp/mcp-publisher.tgz" | sha256sum -c - >/dev/null 2>&1; then
      log_info "WARNING: mcp-publisher checksum mismatch — refusing the download, skipping MCP publish (non-fatal)"
      rm -f "$REPO_ROOT/.tmp/mcp-publisher.tgz"
      exit 0
    fi
  else
    log_info "  (MCP_PUBLISHER_SHA256 unset — download not checksum-verified; set it on the agent to harden)"
  fi
  if ! tar -xzf "$REPO_ROOT/.tmp/mcp-publisher.tgz" -C "$REPO_ROOT/.tmp" mcp-publisher 2>/dev/null; then
    log_info "WARNING: could not unpack mcp-publisher — skipping MCP publish (non-fatal)"
    exit 0
  fi
  chmod +x "$REPO_ROOT/.tmp/mcp-publisher"
  MCP_BIN="$REPO_ROOT/.tmp/mcp-publisher"
fi

# ---- Dry-run: validate the committed manifest, mutate nothing --------------
if is_dry_run; then
  log_info "Validate committed server.json against the live registry schema"
  if "$MCP_BIN" validate "$SERVER_JSON"; then
    log_info "server.json valid (name=$(jq -r .name "$SERVER_JSON"), version=$(jq -r .version "$SERVER_JSON"))"
  else
    log_info "WARNING: server.json failed schema validation (non-fatal in dry-run)"
  fi
  log_dry "skip: server.json mutation + mcp-publisher login dns + publish"
  exit 0
fi

# ---- Execute: sync server.json to this release (guarded; jq failure = skip) -
log_info "Sync server.json -> version=$RELEASE_VERSION identifier=$IMAGE_REF name=$SERVER_NAME"
TMP_JSON="$REPO_ROOT/.tmp/server.json.$$"
mkdir -p "$REPO_ROOT/.tmp"
if ! jq --arg v "$RELEASE_VERSION" --arg ref "$IMAGE_REF" --arg name "$SERVER_NAME" '
      .name = $name
      | .version = $v
      | .packages[0].identifier = $ref
    ' "$SERVER_JSON" > "$TMP_JSON" 2>/dev/null; then
  log_info "WARNING: could not rewrite server.json with jq — skipping MCP publish (non-fatal)"
  rm -f "$TMP_JSON"
  exit 0
fi
mv "$TMP_JSON" "$SERVER_JSON"

log_info "Validate server.json against the live registry schema"
if ! "$MCP_BIN" validate "$SERVER_JSON"; then
  log_info "WARNING: server.json failed schema validation — skipping MCP publish (non-fatal)"
  exit 0
fi

# ---- Authenticate via DNS (non-interactive) --------------------------------
# Requires a one-time DNS TXT record on $MCP_DOMAIN carrying the ed25519 public
# key, and the matching private key (hex seed) in Secrets Manager. See the docs.
if ! aws secretsmanager describe-secret --region "$REGION" \
     --secret-id mockserver-release/mcp-dns-key >/dev/null 2>&1; then
  log_info "MCP DNS key not configured (mockserver-release/mcp-dns-key) — skipping MCP publish; see docs/operations/mcp-registry-publishing.md"
  exit 0
fi

log_info "Authenticate to the MCP registry via DNS ($MCP_DOMAIN)"
# mcp-publisher accepts the ed25519 key ONLY via the -private-key flag (hex);
# it has no file/env input (verified: `mcp-publisher login --help`). The key is
# therefore briefly on the argv of the login subprocess. We bound the exposure:
# the script runs with no xtrace (the value never reaches the Buildkite log),
# output is redirected, the value is unset immediately after, and the release
# agent is single-tenant + ephemeral (only same-uid root could read
# /proc/PID/cmdline during the sub-second login). `login` then writes a token to
# its own config; `publish` below uses that token, not the key.
mcp_key=""
if ! mcp_key=$(load_secret "mockserver-release/mcp-dns-key" "private_key"); then
  log_info "WARNING: could not read MCP DNS private key — skipping MCP publish (non-fatal)"
  exit 0
fi
if ! "$MCP_BIN" login dns --domain "$MCP_DOMAIN" --private-key "$mcp_key" >/dev/null 2>&1; then
  log_info "WARNING: mcp-publisher DNS login failed (check the TXT record on $MCP_DOMAIN and the key) — skipping (non-fatal)"
  mcp_key=""; unset mcp_key
  exit 0
fi
mcp_key=""; unset mcp_key

# ---- Publish ----------------------------------------------------------------
# Registry preconditions (enforced server-side): the referenced image must
# carry LABEL io.modelcontextprotocol.server.name="com.mock-server/mockserver"
# (set in docker/Dockerfile). The docker step publishes that image before this
# step runs, so on a full release the label is live.
log_info "Publish to registry.modelcontextprotocol.io"
if "$MCP_BIN" publish; then
  log_info "MCP server published: $SERVER_NAME @ $RELEASE_VERSION"
else
  log_info "WARNING: mcp-publisher publish failed (often: the $RELEASE_VERSION image with the ownership label is not yet visible on Docker Hub) — skipping (non-fatal)"
  exit 0
fi

# Commit the synced server.json so the repo reflects the published manifest.
git_commit_and_push "release: MCP manifest $RELEASE_VERSION" server.json || \
  log_info "WARNING: could not commit server.json (non-fatal)"

log_info "MCP publish complete"
