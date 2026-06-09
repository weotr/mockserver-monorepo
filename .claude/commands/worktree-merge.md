---
description: Merge the current worktree's work back to master through the 4-gate verification chain and locked rebase
---
Follow the worktree merge workflow in `.opencode/rules/worktree-workflow.md` (steps 3–8). Do not skip any gate unless the user explicitly says "skip gate X".

Run all four gates in order. **Any failure stops the merge** and leaves the worktree intact for inspection — never delete a worktree on a failed merge.

1. **Sanity check** — confirm CWD is inside a worktree (`git rev-parse --git-common-dir` differs from `.git`). If not, refuse and tell the user to `/worktree` first.

2. **Gate 1 — Tests.** Detect changed modules from `git diff --name-only origin/master...` and run targeted Maven tests **plus mockserver-core always**:
   ```bash
   ./mvnw verify -pl :mockserver-core $(...) -DforkCount=1 -DreuseForks=false -fae -Djacoco.skip=true
   ```
   For long runs, use the IntelliJ MCP background pattern (see `.opencode/rules/intellij-mcp-preference.md`): kick off in IntelliJ's terminal with `&` redirection and poll the log.

3. **Gate 2 — Lint / checkstyle / type checks.** Maven's `validate` phase runs checkstyle. For frontend changes also run `npm run lint && npm run build`.

4. **Gate 3 — Adversarial review.** Spawn `review-final` subagent on `git diff origin/master...HEAD`. Block on BLOCK verdict. Quote any concerns back to the user.

5. **Gate 4 — Summary & proceed.** Under the DVRR operating model (`.opencode/rules/operating-model.md`), gates 1–3 are the authority — they replace human pre-approval. Show:
   - `git diff --stat origin/master...HEAD`
   - List of changed files
   - Gate 1/2/3 results in one line each
   Then **proceed automatically** to the locked rebase. Do NOT wait for approval. Fail-closed: if any of gates 1–3 did not return a clean PASS, stop and leave the worktree intact. A user can interject at any time to halt.

6. **Locked rebase.** Acquire `flock --timeout 300 .git/agent-rebase.lock` and inside the lock:
   ```bash
   git fetch origin master --quiet
   git rebase origin/master
   git push origin HEAD:master
   ```
   If `flock` is missing (older macOS), fall back to the `mkdir`-based mutex documented in the rule.

7. **Cleanup.** On successful push only:
   ```bash
   cd "$(git rev-parse --show-toplevel)/.."
   git worktree remove "${WORKTREE_DIR}" --force
   git branch -D "${BRANCH}"
   rm -f .tmp/active-worktree
   ```

8. **Report.** Summarise to the user: gate results, commit hash on master, worktree cleaned up.

If the user provided additional instructions (e.g. "skip user approval", "skip tests because we already ran them"): $ARGUMENTS — only honour explicit skip flags, never silently drop a gate.
