#!/usr/bin/env bash
set -euo pipefail

# Micro-benchmark step (perf queue) — the ABSOLUTE backstop the rolling-history
# baseline can't provide. Runs the JMH MatchingBenchmark (matcher hot path) and
# captures gc.alloc.rate.norm (bytes/op) + time/op per param combo. JMH is
# low-noise, so an absolute regression here is trustworthy even on cloud CI and
# independent of the stored baseline — this is the class of signal that proved
# issue #2329 (O(n)-vs-O(1) per-op cost).
#
# Emits perf-microbench.json {microbench: {<matcherType>_<count>: {...}}} as a
# Buildkite artifact; perf-test-compare.sh merges it into the run result.
#
# Heavy (builds mockserver-core + forks a JVM per param), so it runs only in the
# scheduled/manual perf pipeline on the dedicated box. Tune JMH via JMH_ARGS.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

MAVEN_IMAGE="${MAVEN_IMAGE:-mockserver/mockserver:maven}"
# Focused, bounded param sweep: 3 matcher types at the realistic 100-expectation
# scan depth, INFO logging, short iterations. ~1-2 min of JMH after the build.
JMH_ARGS="${JMH_ARGS:--f 1 -wi 3 -i 5 -r 2 -w 2 -p matcherType=EXACT,REGEX,JSON_BODY -p expectationCount=100 -p logLevel=INFO -prof gc}"

RESULT_RAW="mockserver/mockserver-benchmark/target/jmh-result.json"
OUT_JSON="$REPO_ROOT/perf-microbench.json"

echo "--- building mockserver-core + JMH benchmark, then running MatchingBenchmark"
# shellcheck disable=SC2016
"$SCRIPT_DIR/../run-in-docker.sh" \
  -i "$MAVEN_IMAGE" \
  -m "${MAVEN_MEMORY:-7g}" \
  --entrypoint bash \
  -w /build \
  -e "JMH_ARGS=$JMH_ARGS" \
  -- -c '
    set -euo pipefail
    cd /build/mockserver                       # the Maven reactor root (pom.xml lives here, not /build)
    mvn -q -pl mockserver-core -am install -DskipTests -Djacoco.skip=true -Dcheckstyle.skip=true
    cd mockserver-benchmark
    mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt -Djacoco.skip=true
    CP="target/classes:$(cat target/classpath.txt)"
    # shellcheck disable=SC2086
    java -cp "$CP" org.openjdk.jmh.Main $JMH_ARGS -rf json -rff target/jmh-result.json
  '

if [ ! -f "$REPO_ROOT/$RESULT_RAW" ]; then
  echo "ERROR: JMH did not produce $RESULT_RAW" >&2
  exit 1
fi

# Reshape JMH's array into {microbench: {<matcherType>_<count>: {time_per_op, time_unit, alloc_bytes_per_op}}}.
jq '[.[] | {
      key: (.params.matcherType + "_" + .params.expectationCount),
      value: {
        time_per_op: .primaryMetric.score,
        time_unit: .primaryMetric.scoreUnit,
        alloc_bytes_per_op: (.secondaryMetrics["gc.alloc.rate.norm"].score // null)
      }
    }] | from_entries | {microbench: .}' \
  "$REPO_ROOT/$RESULT_RAW" > "$OUT_JSON"

echo "--- perf-microbench.json"
cat "$OUT_JSON"

if command -v buildkite-agent >/dev/null 2>&1; then
  buildkite-agent artifact upload "perf-microbench.json" || true
fi
