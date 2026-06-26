#!/usr/bin/env bash
set -euo pipefail

# ==============================================================================
# run-inject.sh — drive MockServer load injectors at an Envoy direct_response
# sink and measure (a) a single injector's injection CEILING and (b) how
# aggregate injected throughput SCALES as injectors are added.
#
#   ./run-inject.sh ceiling                 # profile n1, RATE stair, -> inject-ceiling.json
#   ./run-inject.sh scale [N ...]           # profiles nN, RATE hold,  -> inject-scale.json
#       e.g. ./run-inject.sh scale 1 2      # local default (1 and 2 injectors)
#
# The script assumes the stack is reachable on the published host ports:
#   Prometheus  http://localhost:9090   (queried via the HTTP API)
#   Envoy admin http://localhost:9901
#   injector-K  http://localhost:108K   (each injector's 1080 published as 1080+K)
# The MockServer + Envoy images are distroless (no in-container curl/sh), so the
# load control-plane is driven from the HOST via the per-injector published port,
# and readiness/CPU are probed from the host too.
#
# Measurement method: rates are computed with the Prometheus HTTP API
#   sum(rate(mock_server_load_requests_total{scenario="X"}[15s]))           (aggregate)
#   sum by (run_id)(rate(mock_server_load_requests_total{scenario="X"}[15s])) (per-instance)
# sampled mid-way through each held stage (so the 15s window sits entirely
# inside the hold). CPU% comes from `docker stats`. Envoy-received rate comes
# from rate() over the Envoy downstream completed counter.
#
# ASSERTIONS: a measured point is only trusted when throttled_rps ~ 0 and
# error_rate ~ 0. If a cap is binding (throttle > epsilon) a loud WARNING is
# emitted into the output and stderr.
# ==============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.inject.yml"
COMPOSE=(docker compose -f "$COMPOSE_FILE")

PROM_URL="${PROM_URL:-http://localhost:9090}"
ENVOY_ADMIN="${ENVOY_ADMIN:-http://localhost:9901}"
MOCKSERVER_IMAGE="${MOCKSERVER_IMAGE:-mockserver/mockserver:mockserver-snapshot}"
export MOCKSERVER_IMAGE

SCENARIO_CEILING="${SCENARIO_CEILING:-inject-ceiling}"
SCENARIO_CEILING_COMPLEX="${SCENARIO_CEILING_COMPLEX:-inject-ceiling-complex}"
SCENARIO_SCALE="${SCENARIO_SCALE:-inject-scale}"

# Scenario JSON file paths (overridable so a short smoke run can point at
# reduced-duration fixtures without editing the committed ones).
CEILING_FILE="${CEILING_FILE:-$SCRIPT_DIR/scenario-ceiling.json}"
CEILING_COMPLEX_FILE="${CEILING_COMPLEX_FILE:-$SCRIPT_DIR/scenario-ceiling-complex.json}"
SCALE_FILE="${SCALE_FILE:-$SCRIPT_DIR/scenario-scale.json}"

# Tolerances for the "is the number trustworthy" assertions.
THROTTLE_EPS="${THROTTLE_EPS:-5}"       # rps of throttle we treat as "~0"
ERROR_EPS="${ERROR_EPS:-0.005}"         # 0.5% error rate we treat as "~0"
RATE_WINDOW="${RATE_WINDOW:-15s}"       # rate() lookback; must be < hold duration
# Minimum requests-per-connection (Envoy rq-rate / cx-rate) below which the injector
# is judged to be CHURNING connections rather than reusing a keep-alive pool. The
# forward client's idle-only pool reuses a connection for tens-to-hundreds of
# requests when healthy; a value near 1 means a fresh socket per request, which
# spikes injector CPU on connection setup and makes the measured ceiling reflect
# connection-setup cost, not steady request dispatch. A point below this floor is
# flagged NOT trustworthy (same status as throttle/error). See README "Connection
# reuse". Set REUSE_MIN=0 to disable the assertion (e.g. a sink without cx stats).
REUSE_MIN="${REUSE_MIN:-4}"

# Per-stage hold geometry for the ceiling stair. Keep in sync with
# scenario-ceiling.json (offered ladder + 25s holds). We sample at SETTLE secs
# into each hold so the RATE_WINDOW sits inside the steady portion. The ladder is
# FINER (2k steps) and approaches the ceiling gently so the open arrival model
# settles into a clean plateau instead of overshooting and collapsing.
# Space-separated so a smoke run can shorten the ladder: CEILING_OFFERED="2000 4000 6000"
# (must match the stage rates in the ceiling scenario file, in order).
read -r -a CEILING_OFFERED <<<"${CEILING_OFFERED:-2000 4000 6000 8000 10000 12000 14000 16000 18000 20000}"
CEILING_HOLD_S="${CEILING_HOLD_S:-25}"
CEILING_SETTLE_S="${CEILING_SETTLE_S:-18}"   # sample 18s into each 25s hold

# Lean 2-core ceiling probe (used by the scale phase to DERIVE the per-instance
# offered rate). A short, lean stair on a single 2-core injector finds the lean
# ceiling C; the scale phase then drives each lean injector at floor(0.8*C) so
# every instance is comfortably CPU-bound but NOT collapsing (the #66 failure was
# a hardcoded 30k against a ~4k lean ceiling → 43% errors). Override the cpuset
# via PROBE_I1_CPUS; the stair/holds via PROBE_*.
read -r -a PROBE_OFFERED <<<"${PROBE_OFFERED:-2000 3000 4000 5000 6000}"
PROBE_HOLD_S="${PROBE_HOLD_S:-20}"
PROBE_SETTLE_S="${PROBE_SETTLE_S:-14}"       # sample 14s into each 20s hold
PROBE_I1_CPUS_DEFAULT="2-3"                  # lean 2-core pin for the probe
SCALE_RATE_FACTOR="${SCALE_RATE_FACTOR:-0.8}"    # per-instance rate = factor * lean ceiling
SCALE_RATE_MIN="${SCALE_RATE_MIN:-2000}"         # clamp floor so a tiny laptop probe still drives load

# Scale hold geometry. Keep in sync with scenario-scale.json (90s hold). The
# per-instance offered rate is DERIVED at runtime from the lean ceiling probe
# (see above) — it is NOT a hardcoded constant. SCALE_PER_INSTANCE_RPS may be set
# to force a fixed rate and skip the probe (mainly for fast smoke runs).
SCALE_PER_INSTANCE_RPS="${SCALE_PER_INSTANCE_RPS:-}"
SCALE_HOLD_S="${SCALE_HOLD_S:-90}"
SCALE_SETTLE_S="${SCALE_SETTLE_S:-45}"       # sample 45s into the 90s hold

# Per-core sweep. For each core count C the SAME single injector (injector-1) is
# pinned to exactly C cores and runs the SIMPLE-GET ceiling stair; we record the
# highest CLEAN ceiling, the injector CPU% there, and rps_per_core = ceiling/C —
# producing the real efficiency curve (a single injector plateaus well before 8
# cores saturate, so rps/core falls as C grows: the diminishing-returns story).
# Override the C list for a short smoke: PERCORE_CORES="1 2".
read -r -a PERCORE_CORES <<<"${PERCORE_CORES:-1 2 4 8}"
# The injector starts at core PERCORE_INJ_CPU0 and takes C consecutive cores;
# Envoy gets a generous DISJOINT set (PERCORE_ENVOY_CPUS) so the sink never binds.
# Defaults suit a >=16-vCPU box: injector on cores 2..(2+Cmax-1) for the largest C
# (2..9 for C=8), Envoy on 10-15 (6 cores), cores 0-1 for Prometheus/OS. Per-C the
# injector takes only its first C of that block, leaving the rest idle. Override
# any of these (set empty to disable pinning, e.g. a small laptop).
PERCORE_INJ_CPU0="${PERCORE_INJ_CPU0:-2}"            # first injector core
PERCORE_ENVOY_CPUS="${PERCORE_ENVOY_CPUS-10-15}"    # disjoint generous Envoy set
PERCORE_ENVOY_CONCURRENCY="${PERCORE_ENVOY_CONCURRENCY:-6}"
# Per-C ceiling stair geometry (reuses run_rate_stair). The stair top must reach
# the largest-C plateau (~20k for C=8); smaller C plateau lower (that is the point).
read -r -a PERCORE_OFFERED <<<"${PERCORE_OFFERED:-2000 4000 6000 8000 10000 12000 14000 16000 18000 20000}"
PERCORE_HOLD_S="${PERCORE_HOLD_S:-20}"
PERCORE_SETTLE_S="${PERCORE_SETTLE_S:-14}"   # sample 14s into each 20s hold

log()  { printf '%s\n' "--- $*" >&2; }
warn() { printf '%s\n' "!!! WARNING: $*" >&2; }
die()  { printf '%s\n' "ERROR: $*" >&2; exit 1; }

need() { command -v "$1" >/dev/null 2>&1 || die "'$1' is required on PATH"; }
need docker
need jq
need curl

# --- Prometheus instant-query helper ------------------------------------------
# Echoes the scalar value of the first result series (or "0" when no data).
prom_query() {
  local q="$1" out
  out="$(curl -fsS --max-time 10 --data-urlencode "query=${q}" "${PROM_URL}/api/v1/query" 2>/dev/null || echo '')"
  if [ -z "$out" ]; then echo "0"; return; fi
  printf '%s' "$out" | jq -r '
    if .status=="success" and (.data.result|length)>0
    then (.data.result[0].value[1] // "0")
    else "0" end' 2>/dev/null || echo "0"
}

# Echoes one "run_id value" line per series (for per-instance breakdown).
prom_query_by_runid() {
  local q="$1" out
  out="$(curl -fsS --max-time 10 --data-urlencode "query=${q}" "${PROM_URL}/api/v1/query" 2>/dev/null || echo '')"
  if [ -z "$out" ]; then return; fi
  printf '%s' "$out" | jq -r '
    if .status=="success"
    then (.data.result[] | "\(.metric.run_id // "?") \(.value[1])")
    else empty end' 2>/dev/null || true
}

# round a float to an integer (banker-ish: nearest)
rnd() { awk -v x="${1:-0}" 'BEGIN{printf "%d", (x<0?x-0.5:x+0.5)}'; }
# round a float to N decimals
rndf() { awk -v x="${1:-0}" -v n="${2:-4}" 'BEGIN{printf "%.*f", n, x}'; }
gt()  { awk -v a="${1:-0}" -v b="${2:-0}" 'BEGIN{exit !(a>b)}'; }
# truthy test for an env flag: true/1/yes/on (case-insensitive) => success (0)
is_truthy() {
  case "$(printf '%s' "${1:-}" | tr '[:upper:]' '[:lower:]')" in
    true|1|yes|on) return 0 ;;
    *) return 1 ;;
  esac
}

# --- self-diagnosing stale-image guard ----------------------------------------
# The keep-warm forward pool (env MOCKSERVER_FORWARD_CONNECTION_POOL_KEEP_ALIVE)
# is what lets a trivial high-rate request REUSE connections instead of churning.
# It is a no-op unless the mockserver-snapshot image was built AT/AFTER the
# keep-warm core commit 142cbc778. A STALE image silently ignores the flag, the
# simple GET churns, and the reuse assertion (correctly) excludes every mid-ladder
# point — leaving ZERO clean points. Without this guard a paid ~60-min CI run
# produces no usable signal and no hint at the cause, so make the likely cause
# LOUD when the reuse assertion is active and nothing passed it. Args: <label>
# <clean_count>.
stale_image_guard() {
  local label="$1" clean_count="$2"
  if is_truthy "${MOCKSERVER_FORWARD_CONNECTION_POOL_KEEP_ALIVE:-}" || gt "$REUSE_MIN" 0; then
    if [ "${clean_count:-0}" -eq 0 ]; then
      warn "$label: ZERO clean points — EVERY stage was excluded by the reuse/throttle/error gates."
      warn "  Likely cause: the '$MOCKSERVER_IMAGE' image PREDATES the keep-warm forward-pool feature (core commit 142cbc778),"
      warn "  so MOCKSERVER_FORWARD_CONNECTION_POOL_KEEP_ALIVE=true is silently ignored and the injector churns connections."
      warn "  Pull/rebuild a fresh mockserver-snapshot built at/after 142cbc778, then re-run. (Or set REUSE_MIN=0 to disable the"
      warn "  reuse gate if you intend to measure a churning injector deliberately.)"
    fi
  fi
}

# --- container CPU% (docker stats, single snapshot) ---------------------------
container_cpu_pct() {
  local name="$1"
  docker stats --no-stream --format '{{.CPUPerc}}' "$name" 2>/dev/null \
    | tr -d '% ' | head -1 || echo ""
}

# --- wait for the chosen profile's injectors to report metrics to Prometheus --
wait_injectors_scraped() {
  local expected="$1"   # how many injector targets we expect UP
  log "waiting for $expected injector(s) + envoy to be scraped by Prometheus"
  for _ in $(seq 1 60); do
    local up
    up="$(curl -fsS --max-time 5 "${PROM_URL}/api/v1/query" \
            --data-urlencode 'query=up{job="injectors"}==1' 2>/dev/null \
          | jq -r '.data.result|length' 2>/dev/null || echo 0)"
    if [ "${up:-0}" -ge "$expected" ]; then
      log "Prometheus sees $up injector target(s) up"
      return 0
    fi
    sleep 2
  done
  die "timed out waiting for $expected injectors to be scraped (saw fewer)"
}

# --- map an injector service name to its published host control port ----------
# The MockServer image is distroless (no in-container curl/sh), so the control
# plane is driven from the host via the per-injector published port (1080+K).
injector_host_url() {
  local svc="$1"
  local n="${svc#injector-}"
  echo "http://localhost:$((1080 + n))"
}

# --- register a scenario file on an injector and trigger it -------------------
register_and_trigger() {
  local svc="$1" scenario_file="$2" scenario_name="$3"
  local base; base="$(injector_host_url "$svc")"
  local body; body="$(cat "$scenario_file")"
  log "[$svc] registering scenario '$scenario_name' at $base"
  curl -fsS -X PUT "$base/mockserver/loadScenario" \
      -H 'Content-Type: application/json' -d "$body" >/dev/null \
    || die "[$svc] failed to register scenario (is the cap rejecting it? check logs)"
  log "[$svc] triggering '$scenario_name'"
  curl -fsS -X PUT "$base/mockserver/loadScenario/start" \
      -H 'Content-Type: application/json' -d "{\"name\":\"$scenario_name\"}" >/dev/null \
    || die "[$svc] failed to trigger scenario (loadGenerationEnabled? 403?)"
}

stop_all_injectors() {
  local svc base
  for svc in "$@"; do
    base="$(injector_host_url "$svc")"
    curl -fsS -X PUT "$base/mockserver/loadScenario/stop" \
        -d '{"all":true}' >/dev/null 2>&1 || true
  done
}

# --- sample the metrics for a scenario at the current instant ------------------
# Emits a JSON object for one measured point. Args: scenario, offered_rps,
# space-separated injector container names (for CPU).
sample_point() {
  local scenario="$1" offered="$2"; shift 2
  local injectors=("$@")

  local achieved throttled errors inflight recv_rps err_rate
  achieved="$(prom_query "sum(rate(mock_server_load_requests_total{scenario=\"$scenario\"}[$RATE_WINDOW]))")"
  throttled="$(prom_query "sum(rate(mock_server_load_throttled_total{scenario=\"$scenario\"}[$RATE_WINDOW]))")"
  errors="$(prom_query "sum(rate(mock_server_load_errors_total{scenario=\"$scenario\"}[$RATE_WINDOW]))")"
  inflight="$(prom_query "sum(mock_server_load_inflight_requests{scenario=\"$scenario\"})")"

  # Envoy received: rate over the downstream completed counter for the ingress
  # listener. envoy_http_downstream_rq_completed is the per-HCM completed count.
  recv_rps="$(prom_query "sum(rate(envoy_http_downstream_rq_completed{envoy_http_conn_manager_prefix=\"ingress_http\"}[$RATE_WINDOW]))")"
  if ! gt "$recv_rps" 0; then
    # Fallback to the 2xx response-class counter if the completed series is absent.
    recv_rps="$(prom_query "sum(rate(envoy_http_downstream_rq_xx{envoy_response_code_class=\"2\",envoy_http_conn_manager_prefix=\"ingress_http\"}[$RATE_WINDOW]))")"
  fi

  # Connection REUSE: requests-per-connection at Envoy = rq-rate / cx-rate over the
  # window. A healthy keep-alive pool reuses each connection for many requests (high
  # ratio); a value near 1 means the injector is opening a fresh socket per request
  # (connection CHURN), which spikes injector CPU on connection setup and, under the
  # open arrival model, tips into an overshoot/error collapse. This is the dominant
  # artifact for trivial high-rate requests (the forward client's idle-only pool
  # can't reuse a connection that is never idle — see README "Connection reuse").
  local cx_rps reqs_per_conn
  cx_rps="$(prom_query "sum(rate(envoy_http_downstream_cx_total{envoy_http_conn_manager_prefix=\"ingress_http\"}[$RATE_WINDOW]))")"
  reqs_per_conn="$(awk -v r="$recv_rps" -v c="$cx_rps" 'BEGIN{ printf "%.1f", (c>0.0001? r/c : r) }')"

  # error_rate = errors / (achieved + errors)
  err_rate="$(awk -v e="$errors" -v a="$achieved" 'BEGIN{ d=a+e; printf "%.5f", (d>0? e/d : 0) }')"

  # CPU: max across the chosen injectors (the ceiling injector is index 0).
  local cpu_inj=0 c
  for name in "${injectors[@]}"; do
    c="$(container_cpu_pct "$name")"; c="${c:-0}"
    if gt "$c" "$cpu_inj"; then cpu_inj="$c"; fi
  done
  local cpu_envoy; cpu_envoy="$(container_cpu_pct "$(envoy_container)")"; cpu_envoy="${cpu_envoy:-0}"

  if gt "$throttled" "$THROTTLE_EPS"; then
    warn "scenario '$scenario' offered=$offered: throttled=$(rnd "$throttled") rps (> $THROTTLE_EPS) — a CAP IS BINDING, this point is NOT trustworthy"
  fi
  if gt "$err_rate" "$ERROR_EPS"; then
    warn "scenario '$scenario' offered=$offered: error_rate=$err_rate (> $ERROR_EPS) — errors present, point suspect"
  fi
  # Connection-churn assertion: only when REUSE_MIN>0 and we actually saw connections.
  if gt "$REUSE_MIN" 0 && gt "$cx_rps" 0 && ! gt "$reqs_per_conn" "$REUSE_MIN"; then
    warn "scenario '$scenario' offered=$offered: reqs_per_connection=$reqs_per_conn (< $REUSE_MIN) — injector is CHURNING connections (not reusing the keep-alive pool); CPU reflects connection setup, point NOT trustworthy"
  fi

  jq -n \
    --argjson offered "$offered" \
    --argjson achieved "$(rnd "$achieved")" \
    --argjson throttled "$(rnd "$throttled")" \
    --argjson error_rate "$(rndf "$err_rate" 5)" \
    --argjson injector_cpu_pct "$(rnd "$cpu_inj")" \
    --argjson envoy_cpu_pct "$(rnd "$cpu_envoy")" \
    --argjson max_inflight "$(rnd "$inflight")" \
    --argjson target_received_rps "$(rnd "$recv_rps")" \
    --argjson reqs_per_connection "$(rndf "$reqs_per_conn" 1)" \
    --argjson target_cx_rps "$(rndf "$cx_rps" 2)" \
    '{offered_rps:$offered, achieved_rps:$achieved, throttled_rps:$throttled,
      error_rate:$error_rate, injector_cpu_pct:$injector_cpu_pct,
      envoy_cpu_pct:$envoy_cpu_pct, max_inflight:$max_inflight,
      target_received_rps:$target_received_rps,
      reqs_per_connection:$reqs_per_connection,
      target_cx_rps:$target_cx_rps}'
}

# container-name helpers (compose default project naming may add -1 suffix; ask compose)
svc_container() { "${COMPOSE[@]}" ps -q "$1" 2>/dev/null | head -1; }
envoy_container() { svc_container envoy; }

# Wait for the Envoy admin /ready and the first N injector control ports to
# answer on the host before doing anything (the distroless images have no
# in-container healthcheck).
wait_stack_ready() {
  local n="$1"
  log "waiting for Envoy admin /ready"
  for _ in $(seq 1 60); do
    [ "$(curl -fsS --max-time 2 "${ENVOY_ADMIN}/ready" 2>/dev/null || echo X)" = "LIVE" ] && break
    sleep 2
  done
  [ "$(curl -fsS --max-time 2 "${ENVOY_ADMIN}/ready" 2>/dev/null || echo X)" = "LIVE" ] \
    || die "Envoy did not become ready"
  local k base
  for k in $(seq 1 "$n"); do
    base="http://localhost:$((1080 + k))"
    log "waiting for injector-$k control plane at $base"
    for _ in $(seq 1 60); do
      curl -fsS --max-time 2 "$base/mockserver/metrics" >/dev/null 2>&1 && break
      sleep 2
    done
    curl -fsS --max-time 2 "$base/mockserver/metrics" >/dev/null 2>&1 \
      || die "injector-$k control plane did not answer at $base"
  done
}

compose_up() {
  local profile="$1"
  log "bringing up profile $profile (image $MOCKSERVER_IMAGE)"
  "${COMPOSE[@]}" --profile "$profile" up -d
}

compose_down() {
  log "tearing down stack"
  "${COMPOSE[@]}" --profile n1 --profile n2 --profile n4 --profile n6 down -v >/dev/null 2>&1 || true
}

# ==============================================================================
# run_rate_stair: walk an offered-rate ladder on ONE already-triggered injector,
# sample each held stage, and report the points + the highest CLEAN point as the
# ceiling. Shared by cmd_ceiling (fat injector, fine stair) and the lean 2-core
# probe inside cmd_scale (short lean stair to derive the per-instance rate).
#
# Args: <scenario_name> <injector_container> <hold_s> <settle_s> <offered...>
# Results are returned via globals (bash can't return multiple values):
#   STAIR_POINTS       — JSON array of measured points
#   STAIR_CEILING_RPS  — highest cleanly-achieved rps (rounded int)
#   STAIR_EVIDENCE     — evidence object for the last clean point
# ==============================================================================
run_rate_stair() {
  local scenario="$1" inj="$2" hold_s="$3" settle_s="$4"; shift 4
  local offered_ladder=("$@")

  local points="[]"
  local ceiling_rps=0
  local evidence='{}'
  local clean_count=0
  local i offered
  for i in "${!offered_ladder[@]}"; do
    offered="${offered_ladder[$i]}"
    log "  stage $((i+1))/${#offered_ladder[@]}: offered=$offered rps — settling ${settle_s}s into the ${hold_s}s hold"
    sleep "$settle_s"
    local pt; pt="$(sample_point "$scenario" "$offered" "$inj")"
    points="$(jq -c --argjson p "$pt" '. + [$p]' <<<"$points")"

    # Track the highest offered rate the injector actually achieved cleanly
    # (achieved ~ offered, no throttle, no errors, reusing connections) as the
    # ceiling estimate.
    local ach thr er inf rpc cxr
    ach="$(jq -r '.achieved_rps' <<<"$pt")"
    thr="$(jq -r '.throttled_rps' <<<"$pt")"
    er="$(jq -r '.error_rate' <<<"$pt")"
    inf="$(jq -r '.max_inflight' <<<"$pt")"
    rpc="$(jq -r '.reqs_per_connection' <<<"$pt")"
    cxr="$(jq -r '.target_cx_rps' <<<"$pt")"
    # "clean" = achieved within 10% of offered, throttle ~0, errors ~0, AND the
    # injector is reusing connections (reqs_per_connection >= REUSE_MIN) so the
    # measured CPU reflects request dispatch, not connection churn/setup. The reuse
    # clause is applied ONLY when it can be assessed — i.e. REUSE_MIN>0 AND Envoy
    # actually observed connections (cx_rps>0). When REUSE_MIN<=0 (assertion off) or
    # cx_rps==0 (no connection counter — e.g. a sink without cx stats, or a stage so
    # quiet none rotated) the reuse clause is skipped and the point is judged on
    # achieved/throttle/errors alone. This mirrors the warn-gate's cx_rps>0 guard.
    if awk -v a="$ach" -v o="$offered" -v t="$thr" -v te="$THROTTLE_EPS" -v e="$er" -v ee="$ERROR_EPS" \
         -v rp="$rpc" -v rm="$REUSE_MIN" -v cx="$cxr" \
         'BEGIN{exit !(a >= 0.90*o && t <= te && e <= ee && (rm <= 0 || cx <= 0 || rp >= rm))}'; then
      ceiling_rps="$ach"
      evidence="$(jq -n \
        --argjson injector_cpu_pct "$(jq '.injector_cpu_pct' <<<"$pt")" \
        --argjson envoy_cpu_pct "$(jq '.envoy_cpu_pct' <<<"$pt")" \
        --argjson throttled_rps "$(jq '.throttled_rps' <<<"$pt")" \
        --argjson error_rate "$(jq '.error_rate' <<<"$pt")" \
        --argjson reqs_per_connection "$(jq '.reqs_per_connection' <<<"$pt")" \
        --argjson inflight_below_cap "$( [ "$inf" -lt 20000 ] && echo true || echo false )" \
        '{injector_cpu_pct:$injector_cpu_pct, envoy_cpu_pct:$envoy_cpu_pct,
          throttled_rps:$throttled_rps, error_rate:$error_rate,
          reqs_per_connection:$reqs_per_connection,
          inflight_below_cap:$inflight_below_cap}')"
      clean_count=$((clean_count + 1))
      log "    -> clean: achieved=$ach (offered=$offered, reqs/conn=$rpc) — ceiling candidate"
    else
      log "    -> NOT clean (achieved=$ach offered=$offered throttle=$thr err=$er reqs/conn=$rpc) — ladder top reached"
    fi
    # remaining tail of this hold before the next stage's offered changes
    local tail=$((hold_s - settle_s))
    [ "$tail" -gt 0 ] && sleep "$tail"
  done

  STAIR_POINTS="$points"
  STAIR_CEILING_RPS="$(rnd "$ceiling_rps")"
  STAIR_EVIDENCE="$evidence"
  STAIR_CLEAN_COUNT="$clean_count"
}

# ==============================================================================
# ceiling: bring up n1 (FAT injector — ~8 cores, set by the CI wrapper via
# I1_CPUS), run the FINE RATE stair on injector-1, sample each held stage.
# ==============================================================================
# Args (all optional; defaults run the SIMPLE ceiling unchanged):
#   $1 scenario file   (default $CEILING_FILE)
#   $2 scenario name   (default $SCENARIO_CEILING)
#   $3 output file     (default $SCRIPT_DIR/inject-ceiling.json)
# The COMPLEX ceiling reuses this verbatim — same fat injector, same fine stair,
# same clean/collapse assertions and Envoy cross-check — only the registered
# scenario (heavier templated request) and output path differ.
cmd_ceiling() {
  local scenario_file="${1:-$CEILING_FILE}"
  local scenario_name="${2:-$SCENARIO_CEILING}"
  local out="${3:-$SCRIPT_DIR/inject-ceiling.json}"

  compose_up n1
  wait_stack_ready 1
  local inj1; inj1="$(svc_container injector-1)"
  [ -n "$inj1" ] || die "injector-1 container not found"
  wait_injectors_scraped 1

  register_and_trigger injector-1 "$scenario_file" "$scenario_name"

  log "ceiling '$scenario_name': fine RATE stair on the fat injector (${#CEILING_OFFERED[@]} stages)"
  run_rate_stair "$scenario_name" "$inj1" "$CEILING_HOLD_S" "$CEILING_SETTLE_S" "${CEILING_OFFERED[@]}"
  local points="$STAIR_POINTS"
  local ceiling_rps="$STAIR_CEILING_RPS"
  local last_clean_evidence="$STAIR_EVIDENCE"
  local clean_count="$STAIR_CLEAN_COUNT"

  stop_all_injectors injector-1

  stale_image_guard "ceiling '$scenario_name'" "$clean_count"

  jq -n \
    --arg scenario "$scenario_name" \
    --argjson points "$points" \
    --argjson ceiling_rps "$(rnd "$ceiling_rps")" \
    --argjson evidence "$last_clean_evidence" \
    '{proto:"http", target:"envoy-direct-response", scenario:$scenario,
      caps:{max_requests_per_second:200000, max_in_flight_requests:20000, client_nio_threads:8},
      points:$points,
      ceiling_rps:$ceiling_rps,
      ceiling_evidence:$evidence}' > "$out"

  log "wrote $out"
  cat "$out"
}

# ==============================================================================
# percore: pin ONE injector to exactly C cores for each C in PERCORE_CORES, run
# the SIMPLE-GET ceiling stair, and record the highest CLEAN ceiling, the injector
# CPU% there, the connection reuse, and rps_per_core = ceiling/C. This MEASURES the
# per-core efficiency curve: a single injector's dispatch path plateaus well before
# 8 cores saturate, so rps/core FALLS as C grows (diminishing returns). Emits
# inject-percore.json. Reuses run_rate_stair (same clean-gate + reuse assertion)
# and the stale-image guard.
# ==============================================================================

# Build a contiguous cpuset string of N cores starting at the given first core,
# e.g. cpuset_range 2 1 -> "2"; cpuset_range 2 8 -> "2-9". Empty first core -> "".
cpuset_range() {
  local first="$1" n="$2"
  [ -z "$first" ] && { printf ''; return; }
  if [ "$n" -le 1 ]; then printf '%s' "$first";
  else printf '%s-%s' "$first" "$((first + n - 1))"; fi
}

cmd_percore() {
  local out="$SCRIPT_DIR/inject-percore.json"
  local points="[]"
  local c
  for c in "${PERCORE_CORES[@]}"; do
    case "$c" in ''|*[!0-9]*) die "PERCORE_CORES entry '$c' is not a positive integer";; esac
    [ "$c" -ge 1 ] || die "PERCORE_CORES entry '$c' must be >= 1"

    local inj_cpus envoy_cpus
    inj_cpus="$(cpuset_range "$PERCORE_INJ_CPU0" "$c")"
    envoy_cpus="$PERCORE_ENVOY_CPUS"
    log "percore C=$c: injector pinned to $c core(s) (I1_CPUS='${inj_cpus}'), Envoy '${envoy_cpus}' (conc $PERCORE_ENVOY_CONCURRENCY)"

    # fresh stack per C so run_ids/counters/cpusets don't carry over. The cpuset
    # exports are intentionally scoped to this subshell so they apply ONLY to this
    # C's compose_up and never leak to the next iteration (SC2030/SC2031 are the
    # desired semantics here, not a bug).
    compose_down
    # shellcheck disable=SC2030
    ( export I1_CPUS="$inj_cpus" ENVOY_CPUS="$envoy_cpus" ENVOY_CONCURRENCY="$PERCORE_ENVOY_CONCURRENCY"; compose_up n1 )
    wait_stack_ready 1
    local inj1; inj1="$(svc_container injector-1)"
    [ -n "$inj1" ] || die "percore C=$c: injector-1 container not found"
    wait_injectors_scraped 1

    register_and_trigger injector-1 "$CEILING_FILE" "$SCENARIO_CEILING"
    run_rate_stair "$SCENARIO_CEILING" "$inj1" "$PERCORE_HOLD_S" "$PERCORE_SETTLE_S" "${PERCORE_OFFERED[@]}"
    stop_all_injectors injector-1

    stale_image_guard "percore C=$c" "$STAIR_CLEAN_COUNT"

    # Pull the ceiling + its evidence (CPU%, reqs/conn) for this C and compute
    # rps_per_core. When no clean point was found, ceiling is 0 (guard already warned).
    local ceiling_rps cpu rpc rps_per_core envoy_cpu
    ceiling_rps="$STAIR_CEILING_RPS"
    cpu="$(jq -r '.injector_cpu_pct // 0' <<<"$STAIR_EVIDENCE")"
    envoy_cpu="$(jq -r '.envoy_cpu_pct // 0' <<<"$STAIR_EVIDENCE")"
    rpc="$(jq -r '.reqs_per_connection // 0' <<<"$STAIR_EVIDENCE")"
    rps_per_core="$(awk -v r="$ceiling_rps" -v c="$c" 'BEGIN{ printf "%d", (c>0? (r/c)+0.5 : 0) }')"

    # Envoy headroom cross-check: warn if the sink was anywhere near its budget.
    if gt "$envoy_cpu" "$((PERCORE_ENVOY_CONCURRENCY * 80))"; then
      warn "percore C=$c: envoy_cpu_pct=$envoy_cpu is high vs its ${PERCORE_ENVOY_CONCURRENCY}-core budget — the sink may be limiting; give Envoy more cores"
    fi

    local pt; pt="$(jq -n \
      --argjson cores "$c" \
      --argjson ceiling_rps "$(rnd "$ceiling_rps")" \
      --argjson injector_cpu_pct "$(rnd "$cpu")" \
      --argjson envoy_cpu_pct "$(rnd "$envoy_cpu")" \
      --argjson reqs_per_connection "$(rnd "$rpc")" \
      --argjson rps_per_core "$rps_per_core" \
      '{cores:$cores, ceiling_rps:$ceiling_rps, injector_cpu_pct:$injector_cpu_pct,
        envoy_cpu_pct:$envoy_cpu_pct, reqs_per_connection:$reqs_per_connection,
        rps_per_core:$rps_per_core}')"
    points="$(jq -c --argjson p "$pt" '. + [$p]' <<<"$points")"
    log "  -> C=$c: ceiling=$(rnd "$ceiling_rps") rps  cpu=$(rnd "$cpu")%  reqs/conn=$(rnd "$rpc")  rps/core=$rps_per_core"
  done

  compose_down

  jq -n \
    --argjson points "$points" \
    '{proto:"http", target:"envoy-direct-response", request:"simple-GET",
      points:$points}' > "$out"

  log "wrote $out"
  cat "$out"
}

# ==============================================================================
# scale: for each N, bring up nN, trigger scenario-scale on each injector
# together, sample the aggregate + per-instance during the hold.
# ==============================================================================

# Lean 2-core ceiling probe: bring up a SINGLE 2-core injector, run a short lean
# stair, and echo the lean ceiling C (highest cleanly-achieved rps). The scale
# phase derives the per-instance offered rate from this so each lean injector is
# comfortably CPU-bound but NOT collapsing. Tears its own stack down on exit.
probe_lean_ceiling() {
  # Decide the lean cpuset: an explicit PROBE_I1_CPUS wins; otherwise, if the
  # caller is pinning at all (I1_CPUS non-empty), pin the probe to the lean
  # 2-core default; if pinning is disabled (I1_CPUS empty/unset), don't pin.
  local lean_cpus
  # shellcheck disable=SC2031  # intentional: read the CALLER's I1_CPUS (subshell writes elsewhere don't affect this read)
  if [ -n "${PROBE_I1_CPUS+x}" ]; then
    lean_cpus="$PROBE_I1_CPUS"
  elif [ -n "${I1_CPUS:-}" ]; then
    lean_cpus="$PROBE_I1_CPUS_DEFAULT"
  else
    lean_cpus=""
  fi
  log "lean ceiling probe: single 2-core injector (I1_CPUS='${lean_cpus}'), short stair ${PROBE_OFFERED[*]}"

  compose_down
  # shellcheck disable=SC2030,SC2031  # intentional: cpuset export scoped to this subshell only
  ( export I1_CPUS="$lean_cpus"; compose_up n1 )
  wait_stack_ready 1
  local inj1; inj1="$(svc_container injector-1)"
  [ -n "$inj1" ] || die "lean probe: injector-1 container not found"
  wait_injectors_scraped 1

  register_and_trigger injector-1 "$CEILING_FILE" "$SCENARIO_CEILING"
  run_rate_stair "$SCENARIO_CEILING" "$inj1" "$PROBE_HOLD_S" "$PROBE_SETTLE_S" "${PROBE_OFFERED[@]}"
  stop_all_injectors injector-1
  compose_down

  PROBE_CEILING_RPS="$STAIR_CEILING_RPS"
  log "lean ceiling probe: C=${PROBE_CEILING_RPS} rps"
}

# Write a copy of the scale scenario with the RATE-stage rate overridden to the
# derived per-instance offered rate. Echoes the path of the generated file.
gen_scale_scenario() {
  local rate="$1" out="$SCRIPT_DIR/.scenario-scale.generated.json"
  jq --argjson r "$rate" '.profile.stages |= map(.rate = $r)' "$SCALE_FILE" > "$out" \
    || die "failed to generate scale scenario with rate=$rate"
  printf '%s' "$out"
}

cmd_scale() {
  local sizes=("$@")
  [ "${#sizes[@]}" -gt 0 ] || sizes=(1 2)

  # Derive the per-instance offered rate from the LEAN 2-core ceiling (unless a
  # fixed SCALE_PER_INSTANCE_RPS was forced, e.g. for a fast smoke run).
  if [ -z "${SCALE_PER_INSTANCE_RPS:-}" ]; then
    probe_lean_ceiling
    # per-instance rate = floor(factor * lean ceiling), clamped to a sane min.
    SCALE_PER_INSTANCE_RPS="$(awk -v c="$PROBE_CEILING_RPS" -v f="$SCALE_RATE_FACTOR" -v m="$SCALE_RATE_MIN" \
      'BEGIN{ r=int(c*f); if (r<m) r=m; printf "%d", r }')"
    log "derived per-instance offered rate = floor(${SCALE_RATE_FACTOR} * ${PROBE_CEILING_RPS}) = ${SCALE_PER_INSTANCE_RPS} rps (min ${SCALE_RATE_MIN})"
  else
    log "using forced per-instance offered rate = ${SCALE_PER_INSTANCE_RPS} rps (lean probe skipped)"
  fi

  # Generate the scale scenario with the derived rate (overrides the placeholder).
  local scale_scenario_file; scale_scenario_file="$(gen_scale_scenario "$SCALE_PER_INSTANCE_RPS")"

  local series="[]"
  local max_instances=0 actual_at_max=0
  local n
  for n in "${sizes[@]}"; do
    local profile="n$n"
    case "$n" in 1|2|4|6) ;; *) die "unsupported instance count '$n' (use 1,2,4,6)";; esac

    # fresh stack per N so run_ids and counters don't carry over
    compose_down
    compose_up "$profile"
    wait_stack_ready "$n"
    wait_injectors_scraped "$n"

    # collect the injector services for this N
    local svcs=() conts=()
    local k
    for k in $(seq 1 "$n"); do svcs+=("injector-$k"); done
    for s in "${svcs[@]}"; do conts+=("$(svc_container "$s")"); done

    # register on each, then trigger each (they start ~together; small skew ok)
    for s in "${svcs[@]}"; do
      register_and_trigger "$s" "$scale_scenario_file" "$SCENARIO_SCALE"
    done

    log "scale N=$n: settling ${SCALE_SETTLE_S}s into the ${SCALE_HOLD_S}s hold"
    sleep "$SCALE_SETTLE_S"

    # aggregate + per-instance + envoy
    local agg recv err err_rate
    agg="$(prom_query "sum(rate(mock_server_load_requests_total{scenario=\"$SCENARIO_SCALE\"}[$RATE_WINDOW]))")"
    err="$(prom_query "sum(rate(mock_server_load_errors_total{scenario=\"$SCENARIO_SCALE\"}[$RATE_WINDOW]))")"
    local thr; thr="$(prom_query "sum(rate(mock_server_load_throttled_total{scenario=\"$SCENARIO_SCALE\"}[$RATE_WINDOW]))")"
    recv="$(prom_query "sum(rate(envoy_http_downstream_rq_completed{envoy_http_conn_manager_prefix=\"ingress_http\"}[$RATE_WINDOW]))")"
    if ! gt "$recv" 0; then
      recv="$(prom_query "sum(rate(envoy_http_downstream_rq_xx{envoy_response_code_class=\"2\",envoy_http_conn_manager_prefix=\"ingress_http\"}[$RATE_WINDOW]))")"
    fi
    err_rate="$(awk -v e="$err" -v a="$agg" 'BEGIN{ d=a+e; printf "%.5f", (d>0? e/d : 0) }')"

    # per-instance array (by run_id)
    local per_instance_json="[]"
    while read -r _rid val; do
      [ -z "${val:-}" ] && continue
      per_instance_json="$(jq -c --argjson v "$(rnd "$val")" '. + [$v]' <<<"$per_instance_json")"
    done < <(prom_query_by_runid "sum by (run_id)(rate(mock_server_load_requests_total{scenario=\"$SCENARIO_SCALE\"}[$RATE_WINDOW]))")

    if gt "$thr" "$THROTTLE_EPS"; then
      warn "scale N=$n: throttled=$(rnd "$thr") rps (> $THROTTLE_EPS) — a CAP IS BINDING, aggregate is throttled"
    fi
    if gt "$err_rate" "$ERROR_EPS"; then
      warn "scale N=$n: error_rate=$err_rate (> $ERROR_EPS)"
    fi
    # cross-check envoy received ~ aggregate injected (within 10%)
    if ! awk -v r="$recv" -v a="$agg" 'BEGIN{ exit !(a>0 && r >= 0.9*a && r <= 1.1*a) }'; then
      warn "scale N=$n: envoy received ($(rnd "$recv")) != aggregate injected ($(rnd "$agg")) within 10% — investigate"
    fi

    local entry; entry="$(jq -n \
      --argjson instances "$n" \
      --argjson aggregate_rps "$(rnd "$agg")" \
      --argjson per_instance_rps "$per_instance_json" \
      --argjson error_rate "$(rndf "$err_rate" 5)" \
      --argjson target_received_rps "$(rnd "$recv")" \
      '{instances:$instances, aggregate_rps:$aggregate_rps,
        per_instance_rps:$per_instance_rps, error_rate:$error_rate,
        target_received_rps:$target_received_rps}')"
    series="$(jq -c --argjson e "$entry" '. + [$e]' <<<"$series")"
    log "  -> N=$n aggregate=$(rnd "$agg") rps  per-instance=$per_instance_json  envoy=$(rnd "$recv")"

    if [ "$n" -gt "$max_instances" ]; then max_instances="$n"; actual_at_max="$(rnd "$agg")"; fi

    stop_all_injectors "${svcs[@]}"
    # remaining hold tail
    local tail=$((SCALE_HOLD_S - SCALE_SETTLE_S))
    [ "$tail" -gt 0 ] && sleep "$tail"
  done

  # scaling efficiency = actual_at_max / (per_instance_offered * max_instances)
  local ideal_at_max efficiency
  ideal_at_max=$(( SCALE_PER_INSTANCE_RPS * max_instances ))
  efficiency="$(awk -v a="$actual_at_max" -v i="$ideal_at_max" 'BEGIN{ printf "%d", (i>0? (a/i*100)+0.5 : 0) }')"

  local out="$SCRIPT_DIR/inject-scale.json"
  jq -n \
    --argjson per_instance_offered_rps "$SCALE_PER_INSTANCE_RPS" \
    --argjson series "$series" \
    --argjson ideal_at_max "$ideal_at_max" \
    --argjson actual_at_max "$actual_at_max" \
    --argjson efficiency_pct "$efficiency" \
    '{proto:"http", target:"envoy-direct-response",
      per_instance_offered_rps:$per_instance_offered_rps,
      series:$series,
      scaling_efficiency:{ideal_at_max:$ideal_at_max, actual_at_max:$actual_at_max, efficiency_pct:$efficiency_pct}}' > "$out"

  rm -f "$scale_scenario_file"
  log "wrote $out"
  cat "$out"
}

usage() {
  cat >&2 <<EOF
Usage: $0 <ceiling | ceiling-complex | percore | scale [N ...]>
  ceiling          1 fat injector, fine RATE stair, SIMPLE GET -> inject-ceiling.json
  ceiling-complex  1 fat injector, same stair, COMPLEX templated POST (feeder + ~1KB
                   Velocity body) -> inject-ceiling-complex.json. Comparable rps/core
                   data point: complex < simple because each request costs more CPU.
  percore          for each C in PERCORE_CORES (default "1 2 4 8"), pin ONE injector to
                   C cores, run the SIMPLE-GET stair, record ceiling + CPU% + rps_per_core
                   -> inject-percore.json. Shows the per-core efficiency curve (rps/core
                   falls as C grows — a single injector's dispatch path plateaus early).
  scale [N ...]    for each N in {1,2,4,6} (default "1 2"), measure aggregate rps
                   and emit inject-scale.json

  The stack is brought up/torn down by this script. Requires docker, jq, curl.
  Override the image with MOCKSERVER_IMAGE=...; Prometheus at PROM_URL.
EOF
  exit 2
}

main() {
  local cmd="${1:-}"; shift || true
  case "$cmd" in
    ceiling)         cmd_ceiling ;;
    ceiling-complex) cmd_ceiling "$CEILING_COMPLEX_FILE" "$SCENARIO_CEILING_COMPLEX" "$SCRIPT_DIR/inject-ceiling-complex.json" ;;
    percore)         cmd_percore ;;
    scale)           cmd_scale "$@" ;;
    *)               usage ;;
  esac
}

main "$@"
