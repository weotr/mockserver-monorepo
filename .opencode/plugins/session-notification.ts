import type { Plugin } from "@opencode-ai/plugin"

export const SessionNotification: Plugin = async ({ $, worktree }) => {
  return {
    event: async ({ event }) => {
      if (event.type === "session.created") {
        await $`mkdir -p ${worktree}/.tmp && date +%s > ${worktree}/.tmp/.session-start || true`
      }
      if (event.type === "session.idle") {
        await $`osascript -e 'display notification "Task completed" with title "opencode — MockServer"'`
        await $`s=$(cat ${worktree}/.tmp/.session-start 2>/dev/null || echo); if [ -n "$s" ]; then echo "$(date +%s),$(( $(date +%s) - s ))" >> ${worktree}/.tmp/session-timing.csv; fi; bash ${worktree}/.opencode/scripts/aggregate-telemetry.sh ${worktree}/.tmp/decisions >/dev/null 2>&1 || true`
      }
      if (event.type === "session.error") {
        await $`osascript -e 'display notification "Session error occurred" with title "opencode — MockServer" sound name "Basso"'`
      }
    },
  }
}
