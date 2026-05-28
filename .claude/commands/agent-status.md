---
description: Show a status table of all active agent worktrees (created via /worktree), their branch, age, current activity, commit count ahead of master, and rebase-lock status
---
Run `.opencode/scripts/agent-status.sh` and print its output verbatim. The script reads `.worktrees/agent-*` under the repo root and renders a table.

If the user is in a worktree themselves (i.e. `git rev-parse --show-toplevel` is inside `.worktrees/`), highlight which row corresponds to the current worktree in the response (the row whose ID matches the suffix of the current worktree dir name).

If no worktrees are active, the script will say so — just relay that message; don't suggest creating one unless the user asks.

Recommend the convention to record activity for the dashboard: when an agent is working in a worktree, write the current activity to `.tmp/agent-activity` in that worktree (one line, e.g. `echo "Running mvn verify" > .tmp/agent-activity`). The next `/agent-status` run will surface it. See `.opencode/rules/worktree-workflow.md` for the convention details.

If the user provided additional instructions: $ARGUMENTS
