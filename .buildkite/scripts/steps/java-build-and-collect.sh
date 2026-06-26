#!/usr/bin/env bash
#
# Runs the :maven: build, then collects failing-test artefacts and prints an
# end-of-log pass/fail summary. The collector NEVER fails the build, and the
# build's own exit code is preserved and re-raised so a red build stays red.
#
# Wiring lives in a script (not inline YAML) so the shell — not Buildkite's
# pipeline-upload interpolation — owns the `$?` / `$rc` expansion.

set -uo pipefail   # NOTE: deliberately no `set -e` — we want to run the
                   # collector and re-raise the build's exit code even when the
                   # build fails.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

"$SCRIPT_DIR/java-build.sh"
rc=$?

# Always collect failing-test artefacts + print the summary, even on a failed
# build. The collector exits 0 on its own, but `|| true` is belt-and-braces so
# a collector crash can never turn a green build red (or mask the real rc).
"$SCRIPT_DIR/java-collect-failures.sh" || true

exit "$rc"
