#!/usr/bin/env bash
set -euo pipefail

# Periodic performance-regression RUN step (perf queue). Produces ONE result JSON
# (uploaded as a Buildkite artifact) that perf-test-compare.sh baseline-checks.
#
# Phases:
#   1. start a DEDICATED upstream MockServer + the MockServer under test
#      (metrics enabled, DEFAULT maxLogEntries — never shrink it, see growth)
#   2. regression.js over HTTP, then over HTTPS+H2  -> per-behaviour latency
#   3. growth.js (sustained load) with a background CPU/heap sampler
#      -> resource-growth slope ratios (issue #2329 class)
#   4. assemble result.json {metadata, behaviours, growth, resources}
#
# Co-located load-gen + server: on a >=16 vCPU box the server, upstream and k6
# are core-pinned to disjoint cpusets so they don't steal cycles (the single
# biggest factor in number quality). On a smaller box pinning is skipped with a
# warning — numbers are then noisier but the run still works for local checks.
#
# Durations pass through to k6 via K6_* env (defaults in k6/lib/config.js). The
# MockServer image is MOCKSERVER_IMAGE (default snapshot).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

K6_IMAGE="grafana/k6:1.7.1@sha256:4fd3a694926b064d3491d9b02b01cde886583c4931f1223816e3d9a7bdfa7e0f"
MOCKSERVER_IMAGE="${MOCKSERVER_IMAGE:-mockserver/mockserver:mockserver-snapshot}"
RUN_ID="${BUILDKITE_BUILD_ID:-local}-$$"
NETWORK="mockserver-perf-${RUN_ID}"
SERVER="mockserver-perf-${RUN_ID}"
UPSTREAM="mockserver-upstream-${RUN_ID}"
SAMPLE_INTERVAL="${PERF_SAMPLE_INTERVAL:-5}"

OUT_DIR="$(mktemp -d "${TMPDIR:-/tmp}/perf-result.XXXXXX")"
# The k6 image runs as a NON-root user (uid 12345); mktemp -d creates the dir
# 0700 owned by the agent user, so k6's handleSummary() can't write its result
# JSONs into the `/out` bind mount ("permission denied"). World-write the shared
# output dir so the unprivileged container user can write its artifacts. Only the
# k6 result files land here (no secrets), and the dir is per-run + cleaned up.
chmod 0777 "$OUT_DIR"
RESULT_JSON="$OUT_DIR/result.json"
SAMPLE_LOG="$OUT_DIR/samples.csv"

SAMPLER_PID=""
cleanup() {
  [ -n "$SAMPLER_PID" ] && kill "$SAMPLER_PID" >/dev/null 2>&1 || true
  docker rm -f "$SERVER" "$UPSTREAM" >/dev/null 2>&1 || true
  docker network rm "$NETWORK" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# --- core pinning --------------------------------------------------------------
CORES="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 0)"
SERVER_CPUS=""; UPSTREAM_CPUS=""; K6_CPUS=""
if [ "$CORES" -ge 16 ]; then
  SERVER_CPUS="0-5"; UPSTREAM_CPUS="6"; K6_CPUS="8-13"   # 7,14,15 left for kernel/docker/sampler
  echo "--- core-pinning enabled (${CORES} vCPU): server=$SERVER_CPUS upstream=$UPSTREAM_CPUS k6=$K6_CPUS"
else
  echo "--- WARNING: ${CORES} vCPU (<16) — core-pinning skipped; numbers will be noisier"
fi
cpuset_arg() { [ -n "$1" ] && printf -- '--cpuset-cpus=%s' "$1"; }

docker network create "$NETWORK" >/dev/null

start_mockserver() {
  local name="$1" cpus="$2" alias="$3" publish="${4:-}"
  # shellcheck disable=SC2046
  docker run -d --rm --name "$name" --network "$NETWORK" --network-alias "$alias" \
    $(cpuset_arg "$cpus") \
    ${publish:+-p 127.0.0.1::1080} \
    -e MOCKSERVER_LOG_LEVEL=ERROR \
    -e MOCKSERVER_DISABLE_SYSTEM_OUT=true \
    -e MOCKSERVER_METRICS_ENABLED=true \
    "$MOCKSERVER_IMAGE" -serverPort 1080 >/dev/null
}

wait_ready() {
  local name="$1"
  for _ in $(seq 1 60); do
    local status
    status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}nohealth{{end}}' "$name" 2>/dev/null || echo missing)"
    case "$status" in
      healthy) return 0 ;;
      nohealth) sleep 10; return 0 ;;
      missing) echo "ERROR: container $name exited early" >&2; docker logs "$name" 2>&1 | tail -20 >&2 || true; return 1 ;;
    esac
    sleep 2
  done
  echo "ERROR: $name did not become ready" >&2; return 1
}

# The server's network alias is `mockserver`, which k6's config.js treats as a
# local/private target — so the HTTPS pass auto-trusts the self-signed cert with
# no per-VU TLS warning, and no reliance on an explicit insecure flag.
SERVER_ALIAS="mockserver"
echo "--- starting upstream + MockServer ($MOCKSERVER_IMAGE)"
start_mockserver "$UPSTREAM" "$UPSTREAM_CPUS" "mockserver-upstream"
start_mockserver "$SERVER" "$SERVER_CPUS" "$SERVER_ALIAS" "publish"
wait_ready "$UPSTREAM"
wait_ready "$SERVER"

# Host-mapped metrics port so the sampler reads /mockserver/metrics from the host
# (curl on the agent) instead of spawning a container per sample — avoids adding
# CPU noise to the very box being measured. k6 still reaches the server over the
# docker network (container alias), unaffected by this host publish.
SERVER_METRICS="$(docker port "$SERVER" 1080/tcp 2>/dev/null | head -1)"
SERVER_METRICS_URL="http://${SERVER_METRICS:-127.0.0.1:1080}/mockserver/metrics"

echo "--- seeding upstream /simple (forward target)"
docker run --rm --network "$NETWORK" curlimages/curl:8.11.1 -s -X PUT \
  "http://${UPSTREAM}:1080/mockserver/expectation" -H 'Content-Type: application/json' \
  -d '[{"httpRequest":{"path":"/simple"},"httpResponse":{"statusCode":200,"body":"upstream"},"times":{"unlimited":true}}]' \
  -o /dev/null -w 'upstream seed HTTP %{http_code}\n'

run_regression() {
  local proto="$1" base_url="$2" insecure="$3" out="$4"
  echo "--- regression.js ($proto)"
  # shellcheck disable=SC2046
  docker run --rm --network "$NETWORK" $(cpuset_arg "$K6_CPUS") \
    -v "$REPO_ROOT/mockserver-performance-test/k6:/k6:ro" \
    -v "$OUT_DIR:/out" \
    -e "BASE_URL=$base_url" \
    -e "PROTO=$proto" \
    -e "INSECURE_SKIP_TLS_VERIFY=$insecure" \
    -e "K6_RESULT_PATH=/out/$out" \
    ${K6_REG_WARMUP:+-e K6_REG_WARMUP="$K6_REG_WARMUP"} \
    ${K6_REG_DURATION:+-e K6_REG_DURATION="$K6_REG_DURATION"} \
    ${K6_REG_RATE:+-e K6_REG_RATE="$K6_REG_RATE"} \
    "$K6_IMAGE" run /k6/regression.js
}

run_regression "http" "http://${SERVER_ALIAS}:1080" "false" "regression-http.json"
run_regression "https_h2" "https://${SERVER_ALIAS}:1080" "true" "regression-https.json"

# --- resource sampler (background) --------------------------------------------
# Append timestamped CPU% (docker stats) + heap bytes + gc seconds (metrics) every
# SAMPLE_INTERVAL seconds. Runs only during the growth phase so the trajectory is
# attributable to the sustained fill load.
sampler() {
  echo "ts,cpu_pct,heap_bytes,gc_seconds,threads" > "$SAMPLE_LOG"
  while true; do
    local cpu metrics heap gc threads ts
    ts="$(date -u +%s)"
    cpu="$(docker stats --no-stream --format '{{.CPUPerc}}' "$SERVER" 2>/dev/null | tr -d '% ' || echo '')"
    metrics="$(curl -s --max-time 4 "$SERVER_METRICS_URL" 2>/dev/null || echo '')"
    heap="$(printf '%s' "$metrics" | awk -F' ' '/^jvm_memory_used_bytes\{area="heap"\}/{print $2}')"
    gc="$(printf '%s' "$metrics" | awk -F' ' '/^jvm_gc_collection_seconds_sum/{s+=$2} END{print s}')"
    threads="$(printf '%s' "$metrics" | awk -F' ' '/^jvm_threads_current/{print $2}')"
    printf '%s,%s,%s,%s,%s\n' "$ts" "${cpu:-}" "${heap:-}" "${gc:-}" "${threads:-}" >> "$SAMPLE_LOG"
    sleep "$SAMPLE_INTERVAL"
  done
}

echo "--- growth.js (sustained load + resource sampling)"
sampler & SAMPLER_PID=$!
# shellcheck disable=SC2046
docker run --rm --network "$NETWORK" $(cpuset_arg "$K6_CPUS") \
  -v "$REPO_ROOT/mockserver-performance-test/k6:/k6:ro" \
  -v "$OUT_DIR:/out" \
  -e "BASE_URL=http://${SERVER_ALIAS}:1080" \
  -e "K6_GROWTH_RESULT_PATH=/out/growth.json" \
  ${K6_GROWTH_DURATION:+-e K6_GROWTH_DURATION="$K6_GROWTH_DURATION"} \
  ${K6_GROWTH_RATE:+-e K6_GROWTH_RATE="$K6_GROWTH_RATE"} \
  ${K6_GROWTH_PROBE:+-e K6_GROWTH_PROBE="$K6_GROWTH_PROBE"} \
  "$K6_IMAGE" run /k6/growth.js
kill "$SAMPLER_PID" >/dev/null 2>&1 || true; SAMPLER_PID=""

# --- derive resource slope ratios from the sample log -------------------------
# start = first non-empty sample, end = last, peak = max. ratio = end/start.
if [ "$(wc -l < "$SAMPLE_LOG" 2>/dev/null || echo 0)" -le 1 ]; then
  echo "WARNING: resource sample log is empty — growth resource metrics will be 0/null for this run" >&2
fi
read -r CPU_START CPU_END CPU_PEAK HEAP_START HEAP_END HEAP_PEAK GC_DELTA THREADS_PEAK <<EOF
$(awk -F',' 'NR>1 && $2!="" {
    if (cs=="") {cs=$2} ce=$2; if ($2+0>cp) cp=$2;
  }
  NR>1 && $3!="" {
    if (hs=="") {hs=$3} he=$3; if ($3+0>hp) hp=$3;
  }
  NR>1 && $4!="" { if (gcs=="") gcs=$4; gce=$4 }
  NR>1 && $5!="" { if ($5+0>tp) tp=$5 }
  END {
    printf "%s %s %s %s %s %s %s %s", cs+0, ce+0, cp+0, hs+0, he+0, hp+0, (gce-gcs)+0, tp+0
  }' "$SAMPLE_LOG")
EOF
ratio() { awk -v a="$1" -v b="$2" 'BEGIN{ if (b+0>0) printf "%.4f", a/b; else print "null" }'; }
CPU_RATIO="$(ratio "$CPU_END" "$CPU_START")"
HEAP_RATIO="$(ratio "$HEAP_END" "$HEAP_START")"

# --- assemble result JSON -----------------------------------------------------
COMMIT="${BUILDKITE_COMMIT:-$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || echo unknown)}"
BRANCH="${BUILDKITE_BRANCH:-$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)}"
TS="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
INSTANCE_TYPE="$(curl -s --max-time 2 http://169.254.169.254/latest/meta-data/instance-type 2>/dev/null || echo "${PERF_INSTANCE_TYPE:-unknown}")"
GROWTH_JSON="$(cat "$OUT_DIR/growth.json" 2>/dev/null || echo '{}')"

jq -n \
  --arg commit "$COMMIT" --arg branch "$BRANCH" --arg ts "$TS" \
  --arg build_number "${BUILDKITE_BUILD_NUMBER:-}" --arg build_url "${BUILDKITE_BUILD_URL:-}" \
  --arg instance_type "$INSTANCE_TYPE" --arg image "$MOCKSERVER_IMAGE" \
  --arg server_cpus "${SERVER_CPUS:-none}" --arg k6_cpus "${K6_CPUS:-none}" \
  --slurpfile http "$OUT_DIR/regression-http.json" \
  --slurpfile https "$OUT_DIR/regression-https.json" \
  --argjson growth "$GROWTH_JSON" \
  --arg cpu_start "$CPU_START" --arg cpu_end "$CPU_END" --arg cpu_peak "$CPU_PEAK" --arg cpu_ratio "$CPU_RATIO" \
  --arg heap_start "$HEAP_START" --arg heap_end "$HEAP_END" --arg heap_peak "$HEAP_PEAK" --arg heap_ratio "$HEAP_RATIO" \
  --arg gc_delta "$GC_DELTA" --arg threads_peak "$THREADS_PEAK" \
  '{
    schema_version: 1,
    commit: $commit, branch: $branch, timestamp_utc: $ts,
    build_number: $build_number, build_url: $build_url,
    agent: { instance_type: $instance_type, queue: "perf", server_cpus: $server_cpus, k6_cpus: $k6_cpus },
    mockserver_image: $image,
    behaviours: (($http[0].behaviours // {}) + ($https[0].behaviours // {})),
    growth: {
      duration_s: ($growth.duration_s // null),
      p95_ms: ($growth.p95_ms // null),
      cpu_pct: { start: ($cpu_start|tonumber), end: ($cpu_end|tonumber), peak: ($cpu_peak|tonumber), ratio: (try ($cpu_ratio|tonumber) catch null) },
      heap_used_bytes: { start: ($heap_start|tonumber), end: ($heap_end|tonumber), peak: ($heap_peak|tonumber), ratio: (try ($heap_ratio|tonumber) catch null) },
      gc_seconds_delta: ($gc_delta|tonumber),
      threads_peak: ($threads_peak|tonumber)
    }
  }' > "$RESULT_JSON"

echo "--- result.json"
cat "$RESULT_JSON"

if command -v buildkite-agent >/dev/null 2>&1; then
  cp "$RESULT_JSON" "$REPO_ROOT/perf-result.json"
  buildkite-agent artifact upload "perf-result.json" || true
  # Record the commit this run actually executed against. perf-test-guard.sh
  # reads this (via last_perf_run_commit) to decide "new commit since last run"
  # — keyed off real runs, NOT the lint build that passes on every push.
  buildkite-agent meta-data set "perf_regression_ran_commit" "$COMMIT" || true
else
  cp "$RESULT_JSON" "$REPO_ROOT/perf-result.json"
  echo "(local run) result copied to $REPO_ROOT/perf-result.json"
fi
