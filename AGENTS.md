# MockServer — Agent Instructions

## Instruction Priority

1. Direct user instructions (highest)
2. Rules in `.opencode/rules/`
3. This file (`AGENTS.md`)
4. Skills in `.opencode/skills/`
5. Reference docs in `.opencode/reference/`

## Project Overview

MockServer is an open-source HTTP(S) mock server and proxy for testing, written in Java. It uses Netty as the HTTP server framework, Maven for builds, and is deployed as Docker containers, JARs, and WARs.

**Tech stack:** Java 17+ (minimum supported), Netty 4.1, Jackson 2.14, Maven (multi-module), Node.js/TypeScript (UI + client), Python 3.9+ (client), Ruby 3.0+ (client), Docker, Helm, Jekyll (documentation site)
**CI/CD:** Buildkite (primary CI), GitHub Actions (Docker image builds, CodeQL)
**Infrastructure:** AWS (Buildkite build agents, documentation site hosting), Docker Hub (container images)
**Repository:** GitHub (github.com)

### Project Documentation

Comprehensive internal documentation is maintained in `docs/`. **Always consult these docs before making changes** to understand architecture, conventions, and dependencies:

| Document | When to consult |
|----------|----------------|
| [docs/README.md](docs/README.md) | Documentation index and quick reference |
| [docs/code/overview.md](docs/code/overview.md) | Before modifying any module — understand module boundaries and dependencies |
| [docs/code/netty-pipeline.md](docs/code/netty-pipeline.md) | Before modifying Netty handlers, protocol detection, or TLS |
| [docs/code/request-processing.md](docs/code/request-processing.md) | Before modifying mock matching, proxy forwarding, or action dispatch |
| [docs/code/event-system.md](docs/code/event-system.md) | Before modifying event logging, verification, or persistence |
| [docs/code/memory-management.md](docs/code/memory-management.md) | Before modifying maxLogEntries, maxExpectations, ring buffer sizing, or memory defaults |
| [docs/code/dashboard-ui.md](docs/code/dashboard-ui.md) | Before modifying the dashboard UI or WebSocket communication |
| [docs/code/domain-model.md](docs/code/domain-model.md) | Before modifying domain model, matchers, codecs, or configuration |
| [docs/code/tls-and-security.md](docs/code/tls-and-security.md) | Before modifying TLS, mTLS, certificates, or authentication |
| [docs/code/client-and-integrations.md](docs/code/client-and-integrations.md) | Before modifying client library, JUnit rules, or Spring integration |
| [docs/operations/build-system.md](docs/operations/build-system.md) | Before changing Maven config, plugins, or build scripts |
| [docs/infrastructure/ci-cd.md](docs/infrastructure/ci-cd.md) | Before modifying Buildkite or GitHub Actions pipelines |
| [docs/infrastructure/aws-infrastructure.md](docs/infrastructure/aws-infrastructure.md) | Before investigating AWS, Terraform, or Buildkite agent issues |
| [docs/infrastructure/docker.md](docs/infrastructure/docker.md) | Before modifying Dockerfiles, images, or Compose configs |
| [docs/infrastructure/aws-ses-email-forwarding.md](docs/infrastructure/aws-ses-email-forwarding.md) | Before modifying SES email forwarding, DNS mail records, or the Lambda forwarder |
| [docs/infrastructure/helm.md](docs/infrastructure/helm.md) | Before modifying Helm charts or Kubernetes deployment |
| [docs/operations/website.md](docs/operations/website.md) | Before modifying the Jekyll documentation site |
| [docs/operations/security.md](docs/operations/security.md) | Before adding, removing, or upgrading dependencies (Java 17 floor, version ceilings, CodeQL, Dependabot, Snyk) |
| [docs/operations/release-process.md](docs/operations/release-process.md) | When performing or automating releases |
| [docs/operations/release-principles.md](docs/operations/release-principles.md) | Before changing anything under `scripts/release/` or `.buildkite/release-*` |
| [docs/operations/ai-native-sdlc-principles.md](docs/operations/ai-native-sdlc-principles.md) | For the principles behind working with AI across the SDLC |
| [docs/operations/ai-assisted-development.md](docs/operations/ai-assisted-development.md) | For understanding the AI development approach, adversarial review, and testing backstop |
| [docs/operations/opencode-configuration.md](docs/operations/opencode-configuration.md) | Before modifying opencode config, agents, rules, skills, or commands |

When making changes to the project, **update the relevant docs/ file** if the change affects architecture, dependencies, build process, CI/CD, infrastructure, or deployment.

### Consumer Documentation

The consumer-facing documentation lives in `jekyll-www.mock-server.com/` and is published to https://www.mock-server.com. **Always consider consumer docs when making changes:**

1. **Read for context** — before changing behaviour, check the consumer docs to understand what users have been told to expect. Key pages:
   - `jekyll-www.mock-server.com/mock_server/configuration_properties.html` — all configuration properties with defaults and examples
   - `jekyll-www.mock-server.com/mock_server/_includes/running_docker_container.html` — Docker usage examples
   - `jekyll-www.mock-server.com/mock_server/_includes/performance_configuration.html` — performance tuning
2. **Update when behaviour changes** — if you change defaults, add properties, modify behaviour, or fix bugs that affect user-visible behaviour, update the consumer docs to match. Keep changes simple and clear — assume users have limited context.
3. **User-friendly language** — consumer docs should explain *what* a setting does and *why* a user might change it, not internal implementation details. Include practical guidance (e.g., "each HTTP request generates 2-3 log entries") rather than code-level details.

### AWS Accounts

| Purpose | CLI Profile |
|---------|-------------|
| Pipeline build agents and infrastructure | `mockserver-build` |
| Website (S3, CloudFront, DNS, TLS) | `mockserver-website` |

Account IDs, SSO portal URLs, and resource identifiers are in `~/mockserver-aws-ids.md` (not committed to the repo).

### AWS Prerequisites

To investigate or manage AWS infrastructure:

1. **Install AWS CLI**: `brew install awscli`
2. **Configure SSO profiles**:
   - Build: `aws configure sso --profile mockserver-build` (SSO region: `eu-west-2`, default region: `eu-west-2`)
   - Website: `aws configure sso --profile mockserver-website` (SSO region: `eu-west-2`, default region: `us-east-1`)
3. **Authenticate**: `aws sso login --profile mockserver-build` and/or `aws sso login --profile mockserver-website`
4. **Corporate TLS proxy**: if behind a TLS inspection proxy, set `AWS_CA_BUNDLE` to a **combined bundle** containing both the system root CAs AND your corporate root CA. Pointing it at the corporate root alone (e.g. `tesco_root_ca.pem`) is **not enough** — AWS endpoints whose certificates don't chain through the corporate proxy (e.g. SNS in us-east-1) will fail TLS validation and the AWS SDK / Terraform's AWS provider will retry silently, appearing to hang. Typical fix: build a combined `ca-bundle-with-tesco.pem` (system roots + corporate CA), then `export AWS_CA_BUNDLE=/path/to/ca-bundle-with-tesco.pem` (the same file commonly used for `REQUESTS_CA_BUNDLE` / `SSL_CERT_FILE`).
5. **macOS + Python 3.14 + Homebrew**: if you get `pyexpat` symbol errors, set `export DYLD_LIBRARY_PATH=/opt/homebrew/opt/expat/lib`

### Buildkite Agent Infrastructure

Build agents run on EC2 instances in AutoScaling Groups, managed by Lambda-based autoscalers. Infrastructure is managed by Terraform in `terraform/buildkite-agents/`. See [docs/infrastructure/aws-infrastructure.md](docs/infrastructure/aws-infrastructure.md) for full details.

Three agent queues separate workloads by resource needs:

| Queue | Purpose |
|-------|---------|
| `default` | Build and test (Maven, Docker, k3d) |
| `trigger` | Trigger polling jobs (sleep + curl) — cheap, small instances with multiple agents per instance |
| `release` | Release pipeline steps with release secrets |

Capacity, instance types, and agents-per-instance are defined in `terraform/buildkite-agents/` (`main.tf`, `variables.tf`, `terraform.tfvars`). The scaler runs every minute per queue. Treat values in Terraform and live AWS state as authoritative; avoid hard-coding instance types or capacity numbers in prompts.

#### Critical Cost Requirement: Scale to Zero

**IMPORTANT**: `min_size` MUST always be `0` in `terraform/buildkite-agents/terraform.tfvars`. This ensures:
- **Zero idle cost** — no agents run when builds are not queued
- Agents launch only when builds are queued (Lambda scaler sets desired capacity based on queue depth)
- Agents self-terminate when idle (ASG scales back to 0)

**NEVER** change `min_size` to a non-zero value. Pre-created agents are expensive and unnecessary. The Lambda autoscaler handles all scaling based on real-time demand.

## Git Policy

- This repository uses **trunk-based development**: commit directly to the default branch (`master`). Do NOT create feature/topic branches — there is no "branch first" step.
- NEVER commit or push without explicit user request
- NEVER run `git commit` without first completing the full pre-commit workflow in `.opencode/rules/commit-workflow.md` (classify → validate → adversarial review → commit). Use the `/commit` command to ensure the workflow is followed.
- NEVER run destructive git commands without confirmation (see `.opencode/rules/git-safety.md`)
- NEVER add Co-Authored-By, Signed-off-by, or any other trailers to commit messages
- NEVER amend commits that have been pushed to remote

### Parallel Session Safety

Multiple opencode sessions may run concurrently on the same repository. Follow `.opencode/rules/commit-workflow.md` and keep these non-negotiables:
- Stage explicit paths only (never `git add .` or `git add -A`)
- Re-read files before editing and check `git status` before commit
- Commit only files changed in this session
- Run `git pull --rebase` before push

## Pre-Commit Workflow

Use `/commit` for commits so the full workflow in `.opencode/rules/commit-workflow.md` is enforced (classify -> validate -> adversarial review -> commit). Skip steps only when the user explicitly requests it.

## Diagrams and Formatting

- **Always use Mermaid** for diagrams in markdown files. Never use ASCII art for flowcharts, sequence diagrams, or architecture diagrams.
- Use `flowchart`, `sequenceDiagram`, `graph`, or `classDiagram` as appropriate.
- Keep diagrams concise — if a diagram needs more than ~15 nodes, split it into multiple diagrams.
- **NEVER use HTML tags in Mermaid diagrams** — GitHub's Mermaid renderer does not support `<br/>`, `<i>`, `<b>`, or any other HTML markup. Use actual line breaks inside quoted node labels instead:
  - ❌ WRONG: `A[Node Label<br/><i>Description</i>]`
  - ✅ CORRECT: `A["Node Label\nDescription"]` or with actual newlines in quoted strings

## Code Navigation

- Use grep/glob for finding code across the codebase
- Read surrounding context before making changes
- Follow existing code conventions in neighboring files

## Java Compatibility Policy

MockServer targets **Java 17** as the minimum supported version.

**Rules:**
- The Maven compiler source/target MUST remain at `17` (`mockserver/pom.xml` properties `maven.compiler.source` and `maven.compiler.target`)
- NEVER use Java language features or APIs introduced after Java 17
- The codebase uses the `jakarta` namespace for what used to live under `javax.*` (servlet, annotation, validation, xml.bind, ws.rs). JDK-namespace `javax.*` classes (`javax.net.ssl`, `javax.xml.*`, `javax.script.*`, `javax.security.*`, `javax.annotation.Nullable` JSR-305) are unchanged — those are still part of the JDK and stay `javax`.

## Helm Chart Versioning Policy

All MockServer components — Java modules, client libraries, Docker images, and Helm charts — share a single version number to keep things simple and transparent. The Helm chart `version` and `appVersion` in `Chart.yaml` **MUST always match the MockServer application version**. NEVER bump the chart version independently. See [docs/infrastructure/helm.md](docs/infrastructure/helm.md) for full details.

## Fix Placement Policy

Always fix bugs and add features at the architecturally correct layer. If a bug surfaces in `mockserver/mockserver-netty` but the root cause is in `mockserver/mockserver-core`, fix it in `mockserver/mockserver-core`.

## Temporary Files

Use `.tmp/` at the repo root for scratch files — never `/tmp/`. See `.opencode/rules/tmp-directory.md`.

## IDE Integration — Prefer IntelliJ MCP When Available

When the conversation has the IntelliJ MCP toolset (tools prefixed `mcp__idea__*`, indicating IntelliJ is open with the project loaded), **prefer the IDE tools over Bash / `Edit` / `Read`** so the user can watch progress live in tool windows. This applies to terminal commands (use `mcp__idea__execute_terminal_command`), Java builds (`mcp__idea__build_project`), file edits (`mcp__idea__replace_text_in_file`), search (`mcp__idea__search_in_files_by_*`), and per-file inspections (`mcp__idea__get_file_problems`).

For long-running commands that exceed the MCP terminal timeout (`mvn install`, `mvn verify`, large test suites): the MCP `&` background pattern is unreliable (MCP kills the shell before the process detaches — verified). Use **Bash run_in_background** to launch the build (for the completion notification) and `mcp__idea__open_file_in_editor` on the log file so IntelliJ auto-tails it for the user. Even better when a saved Run Configuration exists: `mcp__idea__execute_run_configuration` streams into IntelliJ's Run tool window with click-to-source. See `.opencode/rules/intellij-mcp-preference.md` for the full rule, the four default behaviors when MCP is available (auto-open-before-edit, auto-validate-java-edits, prefer rename refactoring, record activity in `.tmp/agent-activity`), gotchas, and fallback cases.

Multiple Claude/opencode sessions can run in parallel — the default agent stays in the main checkout (IntelliJ MCP visible); independent agents opt into `/worktree` for filesystem isolation; subagents spawned from the primary share the primary's filesystem. Run `/agent-status` to see active worktrees, their branch, age, current activity, commit count ahead of master, and rebase-lock status. See `.opencode/rules/worktree-workflow.md` for the full opt-in flow with verification gates and the `flock`-serialized merge.

## Code Review Routing

When the user asks for a code review:
- Quick pre-commit check: use `code-reviewer` agent
- Deep audit: use `/review-code` command
- Spec/design review: use `/review-spec` command

## Subagent Routing

| Task | Subagent Type |
|------|---------------|
| Code review (pre-commit) | `code-reviewer` |
| Intermediate deep review | `review-cheap` |
| Final authoritative review | `review-final` |
| Implementation work | `implementer` |
| Code simplification | `simplifier` |
| Test execution | `test-runner` |
| Security audit | `security-auditor` |
| Documentation writing | `docs-writer` |
| Pipeline investigation | `pipeline-investigator` |
| Debugging/investigation | `debugger` |
| AWS infrastructure | `debugger` (with `aws-investigation` skill) |
| Task decomposition | `taskify-agent` |
| Design council seat | `council-seat` |
| GitHub issue review | Direct skill (no subagent) |

## Subagent Routing

Follow `.opencode/rules/subagent-routing.md` for both slash-command and conversational routing. Keep skill descriptions focused on behavior; keep routing policy in command metadata and routing rules.

## Research-First Problem Solving

When investigating issues or answering technical questions:
1. Search the codebase first
2. Search online documentation
3. Only then rely on training data

## Release Preparation

- Use `/prepare-release` before running the MockServer release pipeline.
- The release preparation workflow recommends `release-version`, `next-version`, `old-version`, `release-type`, and `create-versioned-site` from `changelog.md`, Semantic Versioning rules, and release readiness checks.
- Prefix unreleased changelog bullets with `BREAKING:` when a major version bump is intended.
