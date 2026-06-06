#!/usr/bin/env bash
#
# launch-with-demo-data.sh
# ------------------------
# Launch the MockServer backend + the UI dev server and populate a rich demo
# dataset so every dashboard view can be tested by hand. Complements the repo's
# scripts/local_ui_dev.sh — this one lives in the UI folder and loads the much
# larger dataset in scripts/populate-demo-data.mjs (HTTP, forward, every LLM
# provider, conversations, agent-loop sessions, token/cost, predicate pills).
#
# Usage:
#   ./scripts/launch-with-demo-data.sh [OPTIONS]
#   npm run demo
#
# Options:
#   --rebuild       Force rebuild of the MockServer JAR even if one exists
#   --no-browser    Do not auto-open the browser
#   --with-broker   Start a Mosquitto MQTT broker (Docker) so the AsyncAPI panel's
#                   Recorded Messages table populates with a live, ticking feed
#   --port PORT     MockServer port (default: 1080)
#   --ui-port PORT  UI dev server port (default: 3000)
#   --mqtt-port P   MQTT broker port (default: 1883; only with --with-broker)
#   --help          Show this help
#
# Press Ctrl+C to stop both servers.

set -euo pipefail

for cmd in java curl node npm; do
  command -v "$cmd" >/dev/null 2>&1 || { echo "ERROR: '$cmd' is required but not installed"; exit 1; }
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UI_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$UI_DIR/.." && pwd)"

MOCKSERVER_PORT=1080
UI_PORT=3000
REBUILD=false
NO_BROWSER=false
WITH_BROKER=false
MQTT_PORT=1883
MQTT_CONTAINER="mockserver-demo-mqtt"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --rebuild) REBUILD=true; shift ;;
    --no-browser) NO_BROWSER=true; shift ;;
    --with-broker) WITH_BROKER=true; shift ;;
    --port) MOCKSERVER_PORT="$2"; shift 2 ;;
    --ui-port) UI_PORT="$2"; shift 2 ;;
    --mqtt-port) MQTT_PORT="$2"; shift 2 ;;
    --help|-h) sed -n '2,22p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown option: $1 (use --help)"; exit 1 ;;
  esac
done

if [ "$WITH_BROKER" = true ]; then
  command -v docker >/dev/null 2>&1 || { echo "ERROR: '--with-broker' needs Docker (for the Mosquitto MQTT broker)"; exit 1; }
fi

echo "========================================"
echo "MockServer UI + Demo Data"
echo "========================================"

# --- locate or build the runnable MockServer JAR --------------------------
find_jar() {
  # Newest matching JAR wins, so stale builds from other branches don't shadow it.
  local jar
  jar=$(ls -t "$REPO_ROOT"/mockserver/mockserver-netty-no-dependencies/target/mockserver-netty-no-dependencies-*.jar 2>/dev/null \
    | grep -Ev '(-sources|-javadoc|/original-)' | head -1)
  [ -n "$jar" ] && { echo "$jar"; return 0; } || return 1
}

if [ "$REBUILD" = true ] || ! find_jar >/dev/null; then
  BUILD_LOG="$UI_DIR/mockserver-build.log"
  echo "→ Building MockServer JAR (this can take a few minutes)"
  echo "  cmd: (cd mockserver && ./mvnw clean install -DskipTests -pl mockserver-netty-no-dependencies -am)"
  echo "  full log: $BUILD_LOG"
  echo "  progress (Maven reactor — one line per module + result):"
  # Stream the full build to a log, but surface only the reactor "Building <module>
  # [N/M]" progress lines, the BUILD result, and any errors so it is clear the build
  # is advancing rather than hung — without flooding the terminal with full output.
  # PIPESTATUS captures the real Maven exit code (grep/tee would otherwise mask it).
  set +e
  ( cd "$REPO_ROOT/mockserver" && ./mvnw clean install -DskipTests -pl mockserver-netty-no-dependencies -am ) 2>&1 \
    | tee "$BUILD_LOG" \
    | grep --line-buffered -E '\[INFO\] Building |\[INFO\] BUILD (SUCCESS|FAILURE)|\[ERROR\]'
  build_rc=${PIPESTATUS[0]}
  set -e
  if [ "$build_rc" -ne 0 ]; then
    echo "ERROR: MockServer build failed (exit $build_rc) — last 40 log lines:"
    tail -40 "$BUILD_LOG"
    exit 1
  fi
  echo "✓ Build complete"
fi
MOCKSERVER_JAR="$(find_jar)" || { echo "ERROR: MockServer JAR not found after build"; exit 1; }
echo "✓ MockServer JAR: $(basename "$MOCKSERVER_JAR")"

# --- install UI deps if needed --------------------------------------------
if [ ! -d "$UI_DIR/node_modules" ]; then
  echo "→ Installing UI dependencies..."
  (cd "$UI_DIR" && npm install)
fi

# --- start MockServer ------------------------------------------------------
MOCKSERVER_LOG="$UI_DIR/mockserver-demo.log"
echo "→ Starting MockServer on port $MOCKSERVER_PORT (log: $MOCKSERVER_LOG)..."
java -Dmockserver.metricsEnabled=true -Dmockserver.wasmEnabled=true -jar "$MOCKSERVER_JAR" -serverPort "$MOCKSERVER_PORT" -logLevel INFO > "$MOCKSERVER_LOG" 2>&1 &
MOCKSERVER_PID=$!

UI_PID=""
cleanup() {
  echo ""
  echo "→ Stopping servers..."
  [ -n "${UI_PID:-}" ] && kill "$UI_PID" 2>/dev/null || true
  [ -n "${MOCKSERVER_PID:-}" ] && kill "$MOCKSERVER_PID" 2>/dev/null || true
  [ "$WITH_BROKER" = true ] && docker rm -f "$MQTT_CONTAINER" >/dev/null 2>&1 || true
  wait 2>/dev/null || true
  echo "✓ Stopped"
}
trap cleanup INT TERM EXIT

wait_for() {
  # MockServer's control plane answers /mockserver/status only to PUT, so the
  # HTTP method is a parameter (default GET for plain pages like the dashboard).
  local url="$1" name="$2" method="${3:-GET}" timeout=60 elapsed=0
  echo "  Waiting for $name..."
  until curl -sf -X "$method" "$url" >/dev/null 2>&1; do
    [ "$elapsed" -ge "$timeout" ] && { echo "ERROR: $name did not start within ${timeout}s"; return 1; }
    sleep 1; elapsed=$((elapsed + 1))
  done
}

wait_for "http://localhost:$MOCKSERVER_PORT/mockserver/status" "MockServer" PUT
echo "✓ MockServer ready (PID $MOCKSERVER_PID)"

# --- optional MQTT broker (for AsyncAPI Recorded Messages) -----------------
# Starts a throwaway Mosquitto broker with anonymous access so the populate
# script can load the AsyncAPI spec in live-broker mode (publish + consume),
# making the AsyncAPI panel's Recorded Messages table fill with a live feed.
DEMO_MQTT_BROKER_URL=""
if [ "$WITH_BROKER" = true ]; then
  echo "→ Starting Mosquitto MQTT broker on port $MQTT_PORT (Docker container: $MQTT_CONTAINER)..."
  docker rm -f "$MQTT_CONTAINER" >/dev/null 2>&1 || true
  docker run -d --name "$MQTT_CONTAINER" -p "$MQTT_PORT:1883" eclipse-mosquitto:2 \
    sh -c "printf 'listener 1883\nallow_anonymous true\n' > /mosquitto/config/mosquitto.conf && exec /usr/sbin/mosquitto -c /mosquitto/config/mosquitto.conf" >/dev/null
  # Wait for the broker TCP port to accept connections (bash /dev/tcp — no nc dependency).
  broker_elapsed=0
  until (exec 3<>"/dev/tcp/localhost/$MQTT_PORT") 2>/dev/null; do
    [ "$broker_elapsed" -ge 30 ] && { echo "ERROR: MQTT broker did not open port $MQTT_PORT within 30s"; docker logs "$MQTT_CONTAINER" 2>&1 | tail -10; exit 1; }
    sleep 1; broker_elapsed=$((broker_elapsed + 1))
  done
  exec 3>&- 2>/dev/null || true
  DEMO_MQTT_BROKER_URL="tcp://localhost:$MQTT_PORT"
  echo "✓ MQTT broker ready ($DEMO_MQTT_BROKER_URL)"
fi

# --- populate demo data ----------------------------------------------------
echo "→ Populating demo data..."
DEMO_MQTT_BROKER_URL="$DEMO_MQTT_BROKER_URL" node "$SCRIPT_DIR/populate-demo-data.mjs" --url "http://localhost:$MOCKSERVER_PORT"

# --- start UI dev server ---------------------------------------------------
echo "→ Starting UI dev server on port $UI_PORT..."
(cd "$UI_DIR" && MOCKSERVER_URL="http://localhost:$MOCKSERVER_PORT" npm run dev -- --port "$UI_PORT" >/dev/null 2>&1) &
UI_PID=$!

# Open the dashboard on the dev-server origin but pointed at MockServer via ?port: the UI
# then calls http://localhost:$MOCKSERVER_PORT directly (cross-origin from the :$UI_PORT dev
# server). MockServer's control plane returns CORS headers on every /mockserver/* response and
# answers the OPTIONS preflight, so this cross-origin path works without the dev proxy — the
# same way the bundled dashboard works when pointed at a different MockServer via host/port.
UI_URL="http://localhost:$UI_PORT/mockserver/dashboard/?port=$MOCKSERVER_PORT"
wait_for "http://localhost:$UI_PORT/mockserver/dashboard/" "UI dev server"
echo "✓ UI dev server ready (PID $UI_PID)"

if [ "$NO_BROWSER" = false ]; then
  if command -v open >/dev/null 2>&1; then open "$UI_URL"
  elif command -v xdg-open >/dev/null 2>&1; then xdg-open "$UI_URL"
  fi
fi

echo ""
echo "========================================"
echo "✓ Ready — populated demo environment"
echo "========================================"
echo "  UI (dev) : $UI_URL"
echo "  Dashboard: http://localhost:$MOCKSERVER_PORT/mockserver/dashboard"
echo "  MockServer log: $MOCKSERVER_LOG"
echo ""
echo "  Re-populate at any time:  npm run demo:data"
echo "  Press Ctrl+C to stop both servers."
echo "========================================"

wait
