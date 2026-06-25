#!/usr/bin/env bash
#
# Stop any leftover *forked* MockServer server JVM(s) from a previous build so a new
# test run does not hit a port clash.
#
# This is the SINGLE canonical copy, shared by every module that needs it:
#   - mockserver/pom.xml                         → ../scripts/stop_MockServer.sh   (Maven `clean` phase)
#   - mockserver/mockserver-maven-plugin/pom.xml → ../../scripts/stop_MockServer.sh (Maven `clean` phase)
#   - mockserver-node/Gruntfile.js               → ../scripts/stop_MockServer.sh
#   - mockserver-client-node/Gruntfile.js        → ../scripts/stop_MockServer.sh
# (It used to be copy-pasted into each of those locations — a leftover from when they
# were separate repos. Keep this the only copy.)
#
# IMPORTANT — match PRECISELY. The previous version grepped `ps -ef` for the bare
# string "mockserver" and killed every match. That string also appears in this repo's
# own path, in surefire fork temp dirs (…/mockserver-netty/target/surefire…), and in
# unrelated command lines (e.g. `bk api pipelines/mockserver-*`, editors, git, this
# very monorepo's tooling). On a machine running concurrent builds or developer tools
# it collaterally killed unrelated processes — including other builds' test forks and
# long-running monitors. It also relied on `ps -ef`, which truncates long command lines
# (the `-cp <huge classpath> org.mockserver.cli.Main` token sits far past the cut-off),
# so the match was unreliable anyway.
#
# Instead, target ONLY a genuine MockServer server JVM — a `java` process that is either:
#   - running the CLI main class `org.mockserver.cli.Main` (how the maven plugin forks it), or
#   - launched with `-jar …mockserver-netty-no-dependencies-<version>.jar` (standalone/CLI/docker), or
#   - launched with `-jar …mockserver-<…>-jar-with-dependencies.jar` (the mockserver-node launchers).
# `pgrep -f` reads the untruncated command line, and anchoring the jar forms to `-jar`
# avoids matching a jar that merely appears on a `-cp` classpath of an unrelated fork.
set -uo pipefail

# Extended regex matched against the full command line.
PATTERN='java .*(org\.mockserver\.cli\.Main|-jar +[^ ]*mockserver-netty-no-dependencies-[0-9][^ ]*\.jar|-jar +[^ ]*mockserver-[^ ]*-jar-with-dependencies\.jar)'

if ! pgrep -f "$PATTERN" >/dev/null 2>&1; then
  echo "stop_MockServer: no forked MockServer JVMs running"
  echo "done"
  exit 0
fi

echo "stop_MockServer: stopping forked MockServer JVM(s):"
pgrep -fl "$PATTERN" 2>/dev/null || true

# Graceful shutdown first (SIGTERM)…
pkill -f "$PATTERN" 2>/dev/null || true

# …then give them a few seconds and force-kill any stragglers (SIGKILL).
for _ in 1 2 3 4 5; do
  sleep 1
  pgrep -f "$PATTERN" >/dev/null 2>&1 || break
done
pkill -9 -f "$PATTERN" 2>/dev/null || true

echo "done"
