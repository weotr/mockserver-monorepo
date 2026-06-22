---
description: Start the session in an isolated git worktree so this agent can work in parallel with other sessions without conflicting
---
Follow the worktree workflow in `.opencode/rules/worktree-workflow.md`. This command does **Step 1** of that flow:

1. Ensure `.worktrees/` is gitignored. If not, add `.worktrees/` to `.gitignore` and commit that change to master first.
2. Pick a short ID: `SHORT_ID="$(date +%Y%m%d-%H%M%S)-$(uuidgen | head -c 6)"`.
3. Create the worktree based on the current `origin/master`:
   ```bash
   git fetch origin master --quiet
   git worktree add --quiet -b "agent/${SHORT_ID}" ".worktrees/agent-${SHORT_ID}" origin/master
   ```
4. Record the worktree path so the session can resume:
   ```bash
   mkdir -p .tmp && echo ".worktrees/agent-${SHORT_ID}" > .tmp/active-worktree
   ```
5. Change working directory into the worktree for the rest of the session. All subsequent commands run inside the worktree.
6. Tell the user clearly:
   - The worktree path.
   - That worktree isolation is the **default** for an independent session: work proceeds inside the worktree, and `git status` / `git diff` is the primary signal.
   - To merge work back to master, run `/worktree-merge`, which runs the 4 verification gates and the locked rebase.

If `.tmp/active-worktree` already exists, the session is already in worktree mode — report the existing path instead of creating a second one.

If the user provided additional instructions: $ARGUMENTS
