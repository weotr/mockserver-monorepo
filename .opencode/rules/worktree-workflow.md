# Worktree Workflow — Default Per-Session Isolation With Rebase Lock

## Rule

Every **independent session** — the primary interactive session, any
parallel Claude/opencode window, and every long autonomous run — works
inside its **own dedicated git worktree** on a **local-only branch**,
not the main checkout. Start in a worktree (via `/worktree`) at session
start. **This holds even when the session makes no changes** —
read-only investigation, analysis, and review work in a worktree too;
the rule is *no work runs in the bare main checkout*, not *only
change-making work is isolated*. Changes return to `master` only via a
gated merge using `flock` to serialize concurrent rebases.
Isolation-by-default is what lets concurrent sessions operate on the
same repo without stepping on each other, and matches the AI-in-SDLC
spec (`docs/operations/ai-sdlc-integration-spec.md` §8.3).

**Delegate, don't do — and delegating is how routing happens.** The
primary session's job is to *orchestrate* (see [[operating-model]] and
[[subagent-routing]]): it should hand almost all execution —
implementation *and* investigation — to subagents rather than doing it
inline, because a subagent is where the correct **model, temperature,
and reasoning effort** are selected for the task. Those subagents are
**helper subagents** and so share this session's worktree (next
paragraph); the point is that the work still lands in *this* worktree,
just run by a right-sized subagent.

**Helper subagents are the one deliberate exception.** A subagent
spawned by a primary via the `Agent` tool **shares the primary's tree**
and is *not* isolated — this is required for correctness, not
convenience: helper subagents (reviewers, investigators) read the
primary's **uncommitted in-flight diff**; a worktree branched off
`origin/master` would see only committed state and miss the very changes
they exist to analyse, silently breaking the commit gate chain.
Isolation is **between independent sessions, not within one**.

## When To Use This

| Situation | Use worktree? |
|-----------|---------------|
| **Primary interactive session** doing substantive work | **Yes (default)** — start in a worktree via `/worktree`. (This previously stayed in the main checkout for IDE visibility; that exception is gone now that IntelliJ integration has been dropped.) |
| **Primary session doing read-only work** (investigation, analysis, answering a question, reviewing) that makes no changes | **Yes** — still a worktree. The rule is *no work in the bare checkout*; isolation is not contingent on whether files change. The merge ceremony simply has nothing to merge (skip to cleanup). |
| **Subagents spawned from the main session** via the `Agent` tool | **No separate worktree — share the spawning session's checkout.** Helper subagents read/analyse in-flight work (uncommitted edits the primary just made). A worktree based on `origin/master` would only see committed state and miss the live changes — and would break the review gate, which reviews the primary's uncommitted diff. They are still "in a worktree" — the primary's. |
| **A second, independent Claude/opencode window** for parallel work on the same repo | **Yes** — that session invokes `/worktree` at start. Each independent session gets its own worktree |
| Long autonomous task (`/loop`, `/schedule`, "go work on X and come back when done") | **Yes** — invoke `/worktree` at session start |
| Trivial one-off (typo, comment, doc one-liner) in a quick session | **Yes — still a worktree** (cheap: it shares the `.git` object store). What scales down for a trivial change is the *merge ceremony* (the 4-gate chain), not the isolation — see [[operating-model]], Scale The Ceremony |

**Key principle**: worktrees provide isolation **between independent sessions** (different Claude windows / autonomous runs), not **within a session** (a primary + its helper subagents). Helper subagents inherit the primary's filesystem state so they can see its uncommitted work.

## Workflow

```
1. /worktree            ──→  Agent creates worktree, switches CWD into it
2. Agent makes changes        (commits as it goes, all on the worktree branch)
3. Agent runs tests           (verification gate 1)
4. Agent runs lint/checkstyle (verification gate 2)
5. Agent spawns review-final  (verification gate 3)
6. Agent shows diff summary    (verification gate 4 — summarise & proceed)
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
yet).

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

**Gate 4 — Summary & proceed.** Under the [[operating-model]] (DVRR),
gates 1–3 (tests, lint, adversarial review PASS) are the authority to
merge — they replace human pre-approval. Show the diff summary (`git
diff --stat origin/master...`, list of changed files, gate-1/2/3
results), then **proceed automatically** to the merge. A user can
interject at any point to halt or amend. This gate is **fail-closed**:
if any of gates 1–3 did not return a clean PASS, do NOT merge — leave
the worktree unmerged for inspection.

### Step 7 — Atomic merge via flock

```bash
# Respect the operator halt before any reintegration (see [[operator-halt]]).
.opencode/scripts/check-halt.sh || { echo "operator halt engaged — not merging"; exit 1; }

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
cd "$(git rev-parse --show-toplevel)/.."  # navigate to the parent of the worktree directory
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
| Gate 4 (a gate 1–3 not a clean PASS, or user interjects) | Worktree preserved; merge halted; user keeps the diff to iterate |
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
- **Diff size cap on Gate 4.** For very large diffs the summary
  presentation is awkward. Consider a "diff summary + risk score"
  presentation rather than raw diff dump.
