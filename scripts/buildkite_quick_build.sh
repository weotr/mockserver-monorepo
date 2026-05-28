#!/usr/bin/env bash

set -euo pipefail

log_debug() {
    echo "[$(date -u +"%Y-%m-%d %H:%M:%S UTC")] $*"
}

log_debug "=== BUILD START ==="
log_debug "User: $(whoami)"
log_debug "Memory: $(free -h 2>/dev/null | grep Mem || echo 'free command not available')"
log_debug "Disk: $(df -h /build/mockserver 2>/dev/null | tail -1 || echo 'df command not available')"

cd mockserver

echo
java -version
echo
./mvnw -version
echo
export MAVEN_OPTS="${MAVEN_OPTS:-} -Xms2048m -Xmx6144m"

if test "${BUILDKITE_BRANCH:-}" = "master"; then
    echo "BRANCH: MASTER"
else
    echo "BRANCH: ${CURRENT_BRANCH:-}"
fi

log_debug "Starting Maven build (foreground)..."
set +e
# -Djava.security.egd is supplied via .mvn/maven.config (file:/dev/./urandom)
./mvnw -T 1C clean install ${1:-} -Dmockserver.testOutput=quiet -DredirectTestOutputToFile=true -Dmockserver.testLogLevel=INFO "-Dmockserver.testArgLine=-Dmockserver.maxLogEntries=10000 -Dmockserver.maxExpectations=5000"
MVN_EXIT=$?
log_debug "Maven exited with code=$MVN_EXIT"
set -e

trap - SIGTERM SIGINT

# Bundle the per-class jacoco HTML reports into a single tarball so Buildkite's
# artifact_paths can upload one file per build instead of ~28000 small HTML
# pages (which trips the 5000-artifact-per-job cap). The XML data files are
# uploaded separately for downstream tooling.
log_debug "Bundling jacoco HTML reports..."
cd /build/mockserver 2>/dev/null || cd "$(dirname "$0")/../mockserver"
find . -type d \( -name jacoco -o -name jacoco-it \) -path '*/target/site/*' > /tmp/jacoco-dirs.txt 2>/dev/null || true
if [[ -s /tmp/jacoco-dirs.txt ]]; then
    tar czf jacoco-html-reports.tar.gz -T /tmp/jacoco-dirs.txt 2>/dev/null \
      && log_debug "  jacoco-html-reports.tar.gz: $(du -h jacoco-html-reports.tar.gz | cut -f1)" \
      || log_debug "  tar failed - skipping HTML bundle"
fi
rm -f /tmp/jacoco-dirs.txt

log_debug "=== BUILD END (exit $MVN_EXIT) ==="
exit $MVN_EXIT
