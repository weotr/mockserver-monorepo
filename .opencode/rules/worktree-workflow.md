# Worktree Workflow — Opt-In Per-Agent Isolation With Rebase Lock

## Rule

When a session is started with `/worktree` (Claude) or when the user
explicitly asks to "start in a worktree", the agent works inside a
**dedicated git worktree** instead of the main checkout. Changes return
to `master` only via a gated merge using `flock` to serialize concurrent
rebases. This enables multiple agent sessions to operate on the same
repo simultaneously without stepping on each other.

**Default behaviour is unchanged**: without `/worktree`, the agent works
directly in the main checkout. This rule activates only when explicitly
opted in. The default keeps the IntelliJ MCP transparency contract (see
[[intellij-mcp-preference]]) — IntelliJ is open on the main checkout
and watching IDE tool windows live remains the primary feedback loop.

## When To Use This

| Situation | Use worktree? |
|-----------|---------------|
| Single interactive session, one user, one Claude window | **No** — stay in main checkout for IntelliJ MCP visibility (this is the default) |
| **Subagents spawned from the main session** via the `Agent` tool | **No, share the primary's checkout.** Subagents typically read/analyse in-flight work (uncommitted edits the primary just made). A worktree based on `origin/master` would only see committed state and miss the live changes the subagent needs to reason about |
| Short edits expected to merge in minutes | **No** — overhead of the 4-gate merge outweighs the safety |
| **A second, independent Claude/opencode window** opened by the user for parallel work on the same repo | **Yes** — that session invokes `/worktree` at start. Each independent session gets its own worktree |
| Long autonomous task (`/loop`, `/schedule`, "go work on X and come back when done") | **Yes** — invoke `/worktree` at session start. The user already accepts they won't watch in real time |
| `Agent` tool with explicit `isolation: "worktree"` parameter | **Yes, but only when the subagent genuinely needs a clean tree** (e.g., experimental refactor sandboxing where the primary doesn't want side effects). This is rare; the default is to share the primary's checkout |

**Key principle**: worktrees provide isolation **between independent agents** (different Claude sessions), not **within an agent** (primary + its subagents). Subagents are helpers of the primary and inherit the primary's filesystem state.

## Workflow

```
1. /worktree            ──→  Agent creates worktree, switches CWD into it
2. Agent makes changes        (commits as it goes, all on the worktree branch)
3. Agent runs tests           (verification gate 1)
4. Agent runs lint/checkstyle (verification gate 2)
5. Agent spawns review-final  (verification gate 3)
6. Agent shows diff to user   (verification gate 4 — synchronous confirm)
7. flock + rebase + cleanup   (atomic merge to master)
```

### Step 1 — Create the worktree

```bash
SHORT_ID="$(date +%Y%m%d-%H%M%S)-$(uuidgen | head -c 6)"
WORKTREE_DIR=".worktrees/agent-${SHORT_ID}"
BRANCH="agent/${SHORT_ID}"

# Ensure master is current
git fetch origin master --quiet
git worktree add --quiet -b "${BRANCH}" "${WORKTREE_DIR}" origin/master
cd "${WORKTREE_DIR}"
```

`.worktrees/` is gitignored (add to `.gitignore` if absent). The
worktree shares the `.git/` object store with main, so disk cost is
minimal. The agent records the worktree path in `.tmp/active-worktree`
so a session resumption can find it again.

### Step 2 — Work in the worktree

All edits, commits, builds, tests happen inside `WORKTREE_DIR`. The
agent commits incrementally on the worktree branch (no rebase to master
yet). IntelliJ MCP tools operate on the main checkout, **not** the
worktree — if the user wants IDE visibility for worktree work, they
must `File → Open` the worktree path in a new IntelliJ window. Calling
this out explicitly in the response when `/worktree` is invoked.

#### Activity recording for `/agent-status`

When multiple agent sessions run in parallel, the user can run
`/agent-status` to see a table of all active worktrees. To surface
**what each agent is currently doing** in that table, write a short
one-line description to `.tmp/agent-activity` inside the worktree
whenever the focus changes:

```bash
mkdir -p .tmp
echo "Running mvn verify on mockserver-netty" > .tmp/agent-activity
# ... later ...
echo "Reviewing diff for commit" > .tmp/agent-activity
```

The convention is intentionally lightweight: a single line, free
text, overwritten on each update. No state schema, no JSON. The
dashboard truncates to 32 characters so keep the message tight.
If the file is absent, the dashboard shows `(idle)` — the agent
need not write it, but doing so makes parallel work observable.

### Steps 3–6 — Gates (run by `/worktree-merge`)

All four must pass; any failure stops the merge and leaves the worktree
intact for inspection.

**Gate 1 — Tests.** Run targeted Maven tests for the modules the
worktree touched, plus the full mockserver-core suite (always run, it's
the blast-radius gate):

```bash
CHANGED_MODULES="$(git diff --name-only origin/master... \
    | sed -n 's|^mockserver/\([^/]*\)/.*|\1|p' | sort -u)"
./mvnw verify -pl :mockserver-core \
    $(printf ' -pl :%s' ${CHANGED_MODULES}) \
    -DforkCount=1 -DreuseForks=false -fae \
    -Djacoco.skip=true
```

**Gate 2 — Lint / checkstyle / type checks.** Maven's `validate` phase
already runs checkstyle. For frontend changes in `mockserver-ui` or
`mockserver-client-node`, also run `npm run lint` and `npm run build`
(strict tsc).

**Gate 3 — Adversarial review.** Spawn the `review-final` agent on the
worktree's diff vs master. Block on BLOCK verdict, proceed on PASS.

```
Agent(
    subagent_type="review-final",
    description="Adversarial review for worktree merge",
    prompt="Review the diff from `git diff origin/master...HEAD` in this worktree. Verdict must be PASS or BLOCK. Focus areas: correctness, security, MockServer conventions, missing tests."
)
```

**Gate 4 — User approval.** Show the diff summary (`git diff
--stat origin/master...`, list of changed files, gate-1/2/3 results)
to the user and ask for confirmation. Required even on PASS for all
prior gates — the user is the final authority. For autonomous loops
where there's no interactive user, fail closed and leave the worktree
unmerged for review in the next interactive session.

### Step 7 — Atomic merge via flock

```bash
LOCK_FILE=".git/agent-rebase.lock"
flock --timeout 300 "${LOCK_FILE}" bash -c '
    set -euo pipefail
    git fetch origin master --quiet
    git rebase origin/master   # may resolve interactively if conflicts
    git push origin HEAD:master
'
```

5-minute (`--timeout 300`) wait if another agent holds the lock. If
timeout exceeded, fail with a clear error: *"Rebase lock held for >5m
by another session — retry in a few minutes or check `lsof
.git/agent-rebase.lock` for the holder."*

`flock` is POSIX, present by default on macOS and Linux. On older macOS
without `flock`, fall back to `mkdir`-based mutex (atomic on POSIX):

```bash
LOCK_DIR=".git/agent-rebase.lockdir"
while ! mkdir "${LOCK_DIR}" 2>/dev/null; do
    [ "$(($(date +%s) - START))" -gt 300 ] && { echo "Lock timeout"; exit 1; }
    sleep 2
done
trap "rmdir ${LOCK_DIR}" EXIT
# ... rebase + push ...
```

### Step 8 — Cleanup

```bash
cd "$(git rev-parse --show-toplevel)/.."  # back to main checkout's parent
git worktree remove "${WORKTREE_DIR}" --force
git branch -D "${BRANCH}"
rm -f .tmp/active-worktree
```

## Concurrency Examples

### Two agents working in parallel

- Agent A creates `.worktrees/agent-20260528-141500-abc123`
- Agent B creates `.worktrees/agent-20260528-141512-def456`
- Both edit files independently in their own worktrees — no interference
- A finishes first, acquires `flock`, rebases onto master, pushes, releases lock
- B finishes second, acquires `flock` (waits if A still rebasing),
  rebases onto **updated** master (includes A's changes), pushes
- If B's rebase finds conflicts with A's changes, B resolves them
  inside the worktree before pushing

### Lock contention timeout

- Agent C tries to rebase while Agent D's rebase has been running >5m
  (something pathological — e.g., D is blocking on an interactive
  conflict resolution)
- C's `flock --timeout 300` exits non-zero
- C surfaces a clear message and stops; user investigates D's worktree

## Failure Modes

| Failure | Outcome |
|---------|---------|
| Gate 1 (tests) fails | Worktree preserved; agent shows test failures; rebase blocked |
| Gate 2 (lint) fails | Same — fix lint and re-run merge |
| Gate 3 (review-final) returns BLOCK | Worktree preserved; agent shows review verdict; user decides |
| Gate 4 (user disapproves) | Worktree preserved; user keeps the diff to iterate |
| flock timeout | Worktree preserved; agent reports lock holder; retry later |
| Rebase conflict | Worktree preserved; agent attempts resolution or hands back to user |

The invariant: **no failed merge ever destroys work**. The worktree is
deleted only after a successful push.

## What This Replaces (Partially)

The "Parallel Session Safety" section in `AGENTS.md` lists rules that
exist because multiple sessions might step on each other in a shared
checkout:

- "Stage explicit paths only (never `git add .`)" — still good practice
- "Re-read files before editing" — still good, race conditions remain
- "Commit only files changed in this session" — still good
- "Run `git pull --rebase` before push" — obsolete inside a worktree
  (the merge step does this implicitly under the lock)

When using `/worktree`, the rebase-lock makes the last point automatic.
The first three remain good hygiene.

## Open Questions / Future Work

- **A real script wrapper.** The bash snippets above should be packaged
  as `scripts/agent-worktree-create.sh` and `scripts/agent-worktree-
  merge.sh`. Today the agent inlines them; later they become reusable.
- **Per-module test selection in Gate 1.** Currently runs core + touched
  modules. Could be smarter by parsing the dependency graph.
- **Diff size cap on Gate 4.** For very large diffs the synchronous
  user-approval step is awkward. Consider a "diff summary + risk score"
  presentation rather than raw diff dump.
- **IntelliJ MCP visibility inside a worktree.** Currently lost.
  Possible future: have the agent open the worktree in a second IntelliJ
  window and use a separate MCP connection — but multi-window MCP isn't
  yet supported reliably.
