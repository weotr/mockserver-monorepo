# IntelliJ MCP Preference — Prefer IDE Tools Over Bash When Available

## Rule

When the conversation has the IntelliJ MCP toolset available (tools
prefixed `mcp__idea__*`, indicating IntelliJ is open with the
project loaded), **prefer the IDE tools over raw shell** for
anything the user might want to follow visually:

| Task | Preferred IntelliJ MCP tool | Bash fallback (avoid) |
|------|----------------------------|------------------------|
| Run a shell command (build, test, script) | `mcp__idea__execute_terminal_command` | `Bash(...)` |
| Run a saved Run/Debug Configuration | `mcp__idea__execute_run_configuration` | n/a |
| Java compile + error report | `mcp__idea__build_project` | `mvn compile` |
| Read a Java file | `mcp__idea__read_file` (decompiles JARs too) | `Read` |
| Edit a file | `mcp__idea__replace_text_in_file` | `Edit` |
| Open a file in the editor for the user | `mcp__idea__open_file_in_editor` | n/a |
| Find files by glob / regex / text | `mcp__idea__find_files_by_glob` / `search_in_files_by_*` | `find` / `grep` |
| Get per-file errors/warnings | `mcp__idea__get_file_problems` | n/a |
| Check open editor tabs | `mcp__idea__get_all_open_file_paths` | n/a |
| List Maven modules | `mcp__idea__get_project_modules` | parse `mvn -pl ...` |

## Why

1. **Transparency.** Every IntelliJ MCP call lands in a tool window
   the user can watch live: terminal commands in **Terminal**, builds
   in **Build**, Run configs in **Run**, file edits in **Changes** /
   **Local History**, project structure in **Maven** / **Project**.
   Bash output only lands in the agent's transcript, which the user
   sees as a summary at best.
2. **Live errors.** `mcp__idea__build_project` + `get_file_problems`
   surface inspection-level errors immediately, with click-to-source
   navigation in the IDE — much faster than `mvn compile` round-trips.
3. **Refactor safety.** `mcp__idea__rename_refactoring` and IntelliJ's
   built-in refactors apply across the whole project safely, vs.
   `sed`-based replacements that miss type-level references.
4. **Indexed search.** `search_in_files_by_text/regex` uses
   IntelliJ's persistent index — orders of magnitude faster than
   `grep -r` on a large repo.

## Long-Running Commands — Use Bash + IntelliJ Hybrid (Tested)

The IntelliJ MCP terminal tool caps output at 2000 lines and
effectively times out around 2–3 minutes regardless of the
`timeout` parameter — **and the timeout kills the shell hard, so
`&` backgrounded processes do not actually detach** (verified
twice during the jakarta mega-bump session 2026-05-28: both
`cmd &` and `nohup cmd & disown` patterns failed; the process
never spawned). Do not rely on MCP terminal alone for long builds.

The reliable pattern is a **hybrid**: Bash launches the build
(survives MCP timeouts and gives a clean completion notification);
IntelliJ opens the log file for live visibility (auto-refreshes
as the file grows; the user can scroll and search).

```
# 1. Launch via Bash run_in_background (returns immediately, fires
#    a notification when EXIT= is written by the wrapper)
Bash(
    command="cd mockserver && ./mvnw verify -fae \\
        > /Users/.../.tmp/maven-verify.log 2>&1; \\
        echo EXIT=$? >> /Users/.../.tmp/maven-verify.log",
    run_in_background=true
)

# 2. Open the log file in IntelliJ so the user can watch progress
mcp__idea__open_file_in_editor(
    filePath=".tmp/maven-verify.log",
    projectPath="/Users/.../mockserver-monorepo"
)
```

The user sees the log file in IntelliJ as it grows (IntelliJ tails
external file changes automatically). The agent waits for the Bash
notification — no polling — and reads the log via `Read` or
`Bash tail` to evaluate the result.

**Even better when a saved Run Configuration exists:**
`mcp__idea__execute_run_configuration("Maven verify")` streams into
IntelliJ's **Run** tool window with module nesting, error markers,
and click-through to source. Trade-off: requires the user to save
the Run Configuration once. Recommend this for repeat workflows.

## Short Commands (under 2 minutes)

MCP terminal works fine for short commands that complete
synchronously: `git status`, `ls`, `mvn dependency:list`, a small
test class re-run, etc. Use `mcp__idea__execute_terminal_command`
directly — output appears in IntelliJ's Terminal tool window and
also comes back to the agent.

## Default Behaviors When IntelliJ MCP Is Available

These four behaviors are the default when `mcp__idea__*` tools are
in the toolset. They cost a small number of extra tool calls per
edit; they buy the user real-time visibility of agent activity in
the IDE.

### 1. Open files in the editor before significant edits

Before any non-trivial `Edit` / `Write` / `replace_text_in_file`
call, first call `mcp__idea__open_file_in_editor` so the file
appears as a tab in IntelliJ. The user sees what is about to change
in real time as the edit lands.

"Significant" = changes more than ~5 lines, adds/removes a method
or class, or modifies imports. Single-line typo fixes and other
trivial edits do not need the open-first preamble.

### 2. Auto-validate Java edits with `mcp__idea__get_file_problems`

After any `.java` file edit, immediately call
`mcp__idea__get_file_problems(filePath=..., errorsOnly=true)` on
that file. IntelliJ's resolver returns compile errors and
inspection hits in ~100ms — orders of magnitude faster than a
Maven cycle. Surface any new errors back to the user before moving
on. Skip for non-code files (markdown, yaml, json).

### 3. Use `mcp__idea__rename_refactoring` for symbol renames

When renaming a Java class, method, field, parameter, or local
variable, prefer the IntelliJ refactor tool over grep+sed. IntelliJ
updates every reference (including JavaDoc, annotations, string
literals in `@Value`, JSP/XML references) — the things text
replacement silently misses.

Pattern:
```
mcp__idea__rename_refactoring(
    filePath="path/to/Foo.java",
    line=42, column=14,         # cursor on the symbol declaration
    newName="BarBaz"
)
```

Fall back to text replacement only when the symbol is genuinely
text-only (e.g. a string constant value that isn't a class
reference).

### 4. Record current activity in `.tmp/agent-activity`

Even when working in the main checkout (not a worktree), write a
short one-line description to `.tmp/agent-activity` at meaningful
transitions: starting a new high-level task, before spawning a
subagent, before kicking off a long-running command, after a major
edit lands.

```bash
mkdir -p .tmp
echo "Implementing SSLHostConfig migration in WAR tests" > .tmp/agent-activity
```

This is the same convention `/agent-status` reads from worktrees.
Writing it from the main checkout lets the user see *what the
default agent is doing right now* by `cat .tmp/agent-activity`,
or have an automation watch the file. Zero cost when no one looks;
useful when the user wants to know what's happening without
reading the transcript.

## When NOT to Use IntelliJ MCP

These cases legitimately stay on Bash / Edit / Read:

1. **No IntelliJ MCP in the toolset.** If `mcp__idea__*` tools aren't
   listed (IntelliJ not open, plugin not installed, opencode runtime
   instead of Claude Code), shell tools are the only option.
2. **Operations outside the project root.** `git`, `gh`, `aws`, `kubectl`,
   anything touching the host or remote systems. IntelliJ's terminal
   can also run these, but there's no IDE-side advantage —  Bash
   `run_in_background` notification is cleaner for non-IDE actions.
3. **IntelliJ MCP is degraded.** If a tool call repeatedly times out
   or returns errors that wouldn't happen via shell (e.g. process
   freeze), fall back to Bash and note it in the response.
4. **Bulk file operations.** `find . -name '*.java' -exec sed ...`
   for codebase-wide changes is sometimes the only practical option.
   Even then, prefer IntelliJ's **Refactor → Migrate Packages and
   Classes** when the change is well-defined (e.g., `javax.*` →
   `jakarta.*`).

## Gotchas

- **Output limits.** `execute_terminal_command` truncates at 2000
  lines. Always redirect long output to a file (`> .tmp/foo.log
  2>&1`) and `tail` or `Read` the file rather than relying on the
  inline tool response.
- **Brave Mode.** Without "Brave Mode" enabled in the IntelliJ MCP
  plugin settings, every `execute_terminal_command` prompts for user
  confirmation. Mention this to the user if they want to enable it
  for smoother flow.
- **Project not imported.** If `mcp__idea__get_project_modules`
  returns only a partial module list (e.g., just a Node sub-project
  without the Java tree), the user needs to right-click the relevant
  `pom.xml` and **Add as Maven Project**. Until that happens,
  `build_project` and `get_file_problems` return misleading
  "success" / "no errors" responses because IntelliJ has no
  classpath for the unimported sources.
- **External file edits.** `Edit` (Claude Code's filesystem tool)
  writes directly to disk; IntelliJ detects external changes after a
  short delay. If the user has the file open with unsaved local
  edits, IntelliJ will prompt to reload — surface this risk before
  bulk Bash-based edits.
- **`mvn install` vs `mvn compile`.** Shade-plugin `-no-dependencies`
  jars are only produced by `install`. If a downstream module depends
  on a no-deps jar (e.g., `mockserver-examples`), `mvn compile` alone
  will fail. Use `mvn install -DskipTests` to populate local m2.

## Why a Rule

The user explicitly chose IntelliJ MCP for **transparency** —
they want to watch progress live in tool windows, not read agent
summaries. Defaulting to Bash defeats the entire purpose of the
setup. This rule makes the IDE-first behaviour explicit so future
sessions don't drift back to shell out of convenience.
