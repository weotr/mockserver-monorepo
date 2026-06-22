#!/usr/bin/env bash
#
# Try out the MockServer editor extensions locally with one command.
#
#   scripts/try-editor-extensions.sh            # VS Code (Extension Development Host)
#   scripts/try-editor-extensions.sh vscode     # same as above
#   scripts/try-editor-extensions.sh jetbrains  # JetBrains plugin in a sandbox IDE
#   scripts/try-editor-extensions.sh both        # launch both
#
# Options:
#   --install      VS Code only: package a .vsix and install it into your real
#                  VS Code (persistent) instead of the sandboxed dev host.
#   --no-server    Do not start a local MockServer container.
#   --port N       Host port for the MockServer container (default 1080).
#
# What it does (so every new feature is immediately testable):
#   * builds the extension(s),
#   * starts a MockServer Docker container (so Load/Diff/Record/OpenAPI work),
#   * drops a sample demo.mockserver.json and petstore.openapi.json into a scratch
#     workspace and opens it, so schema validation + the CodeLens/commands light up.
#
# VS Code's Extension Development Host loads the extension from source with no
# install and nothing to uninstall afterwards — the easiest way to try it.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET="vscode"
INSTALL=false
START_SERVER=true
PORT=1080

while [[ $# -gt 0 ]]; do
  case "$1" in
    vscode|jetbrains|both) TARGET="$1"; shift ;;
    --install) INSTALL=true; shift ;;
    --no-server) START_SERVER=false; shift ;;
    --port) [[ -n "${2:-}" ]] || { echo "error: --port requires an argument" >&2; exit 2; }; PORT="$2"; shift 2 ;;
    -h|--help) sed -n '2,40p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; exit 2 ;;
  esac
done

# Node is provided via nvm as a shell function in interactive shells; resolve a
# real binary for non-interactive use.
NODE_BIN="$(command -v node || true)"
if [[ -z "$NODE_BIN" || "$(file -b "$NODE_BIN" 2>/dev/null)" == *"script"* ]]; then
  if [[ -x /opt/homebrew/bin/node ]]; then NODE_BIN=/opt/homebrew/bin/node; fi
fi
if [[ -z "$NODE_BIN" ]]; then
  echo "error: node not found; install Node via nvm or Homebrew" >&2; exit 1
fi
NPM_BIN="$(dirname "$NODE_BIN")/npm"

log() { printf '\033[36m[try]\033[0m %s\n' "$*"; }

make_demo_workspace() {
  local ws="$REPO_ROOT/.tmp/editor-demo"
  mkdir -p "$ws"
  cat > "$ws/demo.mockserver.json" <<'JSON'
{
  "httpRequest": { "method": "GET", "path": "/api/hello" },
  "httpResponse": { "statusCode": 200, "body": "hello from MockServer" }
}
JSON
  cat > "$ws/petstore.openapi.json" <<'JSON'
{
  "openapi": "3.0.0",
  "info": { "title": "Petstore", "version": "1.0.0" },
  "paths": {
    "/pets": {
      "get": {
        "operationId": "listPets",
        "responses": { "200": { "description": "ok", "content": { "application/json": { "example": [{ "id": 1, "name": "Rex" }] } } } }
      }
    }
  }
}
JSON
  echo "$ws"
}

start_server() {
  $START_SERVER || { log "skipping MockServer (--no-server)"; return; }
  if ! docker info >/dev/null 2>&1; then
    log "Docker not running — skipping server. Load/Diff/Record/OpenAPI need a server on port $PORT."
    return
  fi
  if [[ -n "$(docker ps -q --filter "name=mockserver-try" 2>/dev/null)" ]]; then
    log "MockServer already running (container mockserver-try)"
    return
  fi
  log "starting MockServer on http://localhost:$PORT (container mockserver-try)"
  docker run -d --rm --name mockserver-try -p "$PORT:1080" mockserver/mockserver:latest >/dev/null
}

launch_vscode() {
  log "building VS Code extension"
  ( cd "$REPO_ROOT/mockserver-vscode" && "$NPM_BIN" ci --no-audit --no-fund --loglevel=error && "$NPM_BIN" run compile )
  local ws; ws="$(make_demo_workspace)"
  if $INSTALL; then
    log "packaging + installing into your VS Code"
    rm -f "$REPO_ROOT/mockserver-vscode"/*.vsix   # avoid installing a stale package
    ( cd "$REPO_ROOT/mockserver-vscode" && "$NPM_BIN" run generate-schema && "$(dirname "$NODE_BIN")/npx" --yes @vscode/vsce package --no-git-tag-version )
    code --install-extension "$REPO_ROOT/mockserver-vscode"/*.vsix --force
    log "installed — open $ws/demo.mockserver.json in VS Code to try it"
    code "$ws"
  else
    log "launching VS Code Extension Development Host (sandboxed, nothing to uninstall)"
    code --extensionDevelopmentPath="$REPO_ROOT/mockserver-vscode" "$ws"
  fi
  log "Try: open demo.mockserver.json (validation + CodeLens), run 'MockServer: Generate Expectations From OpenAPI Spec' on petstore.openapi.json"
}

launch_jetbrains() {
  log "launching JetBrains sandbox IDE with the plugin (./gradlew runIde — first run downloads the IDE)"
  ( cd "$REPO_ROOT/mockserver-jetbrains" && ./gradlew runIde )
}

start_server
case "$TARGET" in
  vscode) launch_vscode ;;
  jetbrains) launch_jetbrains ;;
  both) launch_vscode; log "now starting JetBrains (blocking)…"; launch_jetbrains ;;
esac
