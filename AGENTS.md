# MockServer — Agent Instructions

## Instruction Priority

1. Direct user instructions (highest)
2. Rules in `.opencode/rules/`
3. This file (`AGENTS.md`)
4. Skills in `.opencode/skills/`
5. Reference docs in `.opencode/reference/`

## Project Overview

MockServer is an open-source HTTP(S) mock server and proxy for testing, written in Java. It uses Netty as the HTTP server framework, Maven for builds, and is deployed as Docker containers, JARs, and WARs.

**Tech stack:** Java 17+ (minimum supported), Netty 4.2, Jackson 2.22, Maven (multi-module), Node.js/TypeScript (UI + client), Python 3.9+ (client), Ruby 3.0+ (client), Docker, Helm, Jekyll (documentation site)
**CI/CD:** Buildkite (primary CI), GitHub Actions (Docker image builds, CodeQL)
**Infrastructure:** AWS (Buildkite build agents, documentation site hosting), Docker Hub (container images)
**Repository:** GitHub (github.com)

### Local Development Environment

**Docker is available locally.** Docker Desktop runs on the developer Mac, so Docker is available to agents in this environment (not only in CI). This means:

- **Docker-gated tests CAN and SHOULD be run locally**, not just in CI. Tests that guard on `Assume.assumeTrue(DockerClientFactory.instance().isDockerAvailable())` (Testcontainers live-broker tests, `NET_ADMIN` transparent-proxy e2e, QUIC/HTTP-3 client tests, etc.) will actually execute here — validate them by running and passing them, not merely by confirming they skip.
- **Keep the Docker-gating in place anyway.** The `assumeTrue(...isDockerAvailable())` guard is still the correct design so the suite degrades gracefully on any CI agent or machine without Docker. Docker being present locally changes how we *validate*, not how we *write* the tests.
- `DockerClientFactory.instance().isDockerAvailable()` is the canonical availability probe; Testcontainers is the preferred harness. The probe works correctly with Testcontainers 1.21.4+ (docker-java 3.4.2) on Docker Desktop 4.67 / Engine 29.x / API 1.54. (Earlier versions — Testcontainers 1.20.6 / docker-java 3.4.1 — got a 400 on the info endpoint and the probe returned false even though Docker worked.)
- `docker` CLI commands (`docker build`, `docker run`) are also available for Dockerfile smoke checks in the commit workflow.

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
| [docs/code/drift-detection.md](docs/code/drift-detection.md) | Before modifying mock drift detection, DriftAnalyzer, DriftStore, or the /drift endpoint |
| [docs/code/wasm-rules.md](docs/code/wasm-rules.md) | Before modifying WASM custom rule engine, chicory integration, or WASM REST endpoints |
| [docs/code/telemetry.md](docs/code/telemetry.md) | Before modifying OpenTelemetry integration, OTLP export, GenAI spans, or W3C trace context propagation |
| [docs/code/async-messaging.md](docs/code/async-messaging.md) | Before modifying the AsyncAPI broker mocking module, AsyncApiParser, MessagePublisher adapters, or AsyncApiMockOrchestrator |
| [docs/code/http3.md](docs/code/http3.md) | Before modifying experimental HTTP/3 (QUIC) support, Http3Server, or QUIC native dependencies |
| [docs/code/clustered-state.md](docs/code/clustered-state.md) | Before modifying the StateBackend SPI, InMemoryStateBackend, InfinispanStateBackend, cross-node invalidation, or cluster configuration properties |
| [docs/code/llm-mocking.md](docs/code/llm-mocking.md) | Before modifying the LLM response builder, provider codecs, streaming physics, conversation matchers, isolation, MCP tools, or LLM dashboard |
| [docs/code/metrics.md](docs/code/metrics.md) | Before modifying Prometheus metrics, memory monitoring, or CSV metric export |
| [docs/code/configuration-reference.md](docs/code/configuration-reference.md) | Before adding a configuration property or changing property resolution order or the equivalent property forms |
| [docs/operations/build-system.md](docs/operations/build-system.md) | Before changing Maven config, plugins, or build scripts |
| [docs/infrastructure/ci-cd.md](docs/infrastructure/ci-cd.md) | Before modifying Buildkite or GitHub Actions pipelines |
| [docs/infrastructure/aws-infrastructure.md](docs/infrastructure/aws-infrastructure.md) | Before investigating AWS, Terraform, or Buildkite agent issues |
| [docs/infrastructure/docker.md](docs/infrastructure/docker.md) | Before modifying Dockerfiles, images, or Compose configs |
| [docs/infrastructure/service-mesh.md](docs/infrastructure/service-mesh.md) | Before modifying transparent HTTP interception or Kubernetes sidecar deployment |
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

### Reading Buildkite Builds and Logs

**To read a Buildkite build log, use the local `bk` CLI — NOT the API tokens in Secrets Manager.** The tokens at `mockserver-build/buildkite-api-token` (write) and `-readonly` work for build **state**, **triggering**, and **retrying** jobs, but they intentionally lack the `read_build_logs` scope, so fetching `/jobs/<id>/log` with them returns `"doesn't have the read_build_logs scope"`. The developer's locally-authenticated `bk` CLI (`~/.config/bk.yaml`) has full scope.

```bash
# Job log (bk api prepends the org path — pass a RELATIVE endpoint):
bk api "pipelines/mockserver-release/builds/<N>/jobs/<JOB_ID>/log" \
  | python3 -c "import sys,json; print(json.load(sys.stdin).get('content',''))" \
  | sed 's/\x1b\[[0-9;]*m//g; s/_bk;t=[0-9]*//g; s/\r//g'
```

Use the API token (via `aws secretsmanager get-secret-value` + `curl`) only for build state, creating builds, and retrying jobs. Driving the Buildkite UI through the `chrome-devtools` MCP does **not** work for logs: that automation browser is a separate, logged-out profile from the developer's own browser. See [docs/infrastructure/ci-cd.md](docs/infrastructure/ci-cd.md).

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

## Agent Operating Model

The default way of working for every non-trivial task is the **Decompose ·
Verify · Review · Reintegrate (DVRR)** model: autonomous, parallel-first.
**Decompose** work into the smallest independent units, **delegate** them to
subagents and run them in parallel, **verify** each as fully as can be done
safely, subject each to **adversarial review until no major findings remain**,
**re-verify** after any review-driven change, then **commit each unit separately
and reintegrate it onto `master`**.

- **The gate chain is the authority to ship, not a human prompt.** Once a unit
  passes the full chain (classify → validate → changelog → adversarial review
  with a PASS verdict → re-verify), commit and push autonomously. Gates are **mandatory and
  fail-closed** — if any gate cannot run or does not return a clean PASS, do not
  commit; surface the failure and leave the work for inspection.
- **Scale the ceremony to the task** — full DVRR for substantial/risky work; a
  lightweight path (inline edit + one adversarial review + targeted verify) for
  small changes; a direct edit for trivial ones. Don't manufacture ceremony that
  adds no safety.
- **Isolate independent agents, not every task** — the primary interactive
  session stays in the main checkout (IntelliJ MCP visible); independent/long
  autonomous sessions use `/worktree`; subagents share the primary's filesystem.
- **Clarify well, rarely** — proceed on the strongest safe assumptions; escalate
  only when ambiguity materially affects correctness, safety, or intent, and use
  a structured `AskUserQuestion` (what's unclear, why it matters, recommended
  option first, alternatives, impact).
- **Summarise after each batch** of parallel work — what's done, what remains,
  blockers — leading with the bottom line.

This is the spine that ties the rules below together; the full model, including
how each phase maps to an owning rule, is in `.opencode/rules/operating-model.md`.

## Git Policy

- This repository uses **trunk-based development**: commit directly to the default branch (`master`). Do NOT create feature/topic branches — there is no "branch first" step.
- Commit and push **autonomously once the full pre-commit gate chain passes** — the gates replace human pre-approval (Agent Operating Model above). A user instruction always overrides this default.
- NEVER run `git commit` without first completing the full pre-commit workflow in `.opencode/rules/commit-workflow.md` (classify → validate → changelog → adversarial review (PASS) → re-verify → commit). Use the `/commit` command to ensure the workflow is followed. If any gate fails, do NOT commit.
- NEVER run destructive git commands without confirmation (see `.opencode/rules/git-safety.md`) — auto-commit/push of new commits is authorized; `reset --hard`, `push --force`, history rewrites, and discarding uncommitted work are NOT.
- NEVER add Co-Authored-By, Signed-off-by, or any other trailers to commit messages
- NEVER amend commits that have been pushed to remote

### Parallel Session Safety

Multiple opencode sessions may run concurrently on the same repository. Follow `.opencode/rules/commit-workflow.md` and keep these non-negotiables:
- Stage explicit paths only (never `git add .` or `git add -A`)
- Re-read files before editing and check `git status` before commit
- Commit only files changed in this session
- Run `git pull --rebase` before push

## Pre-Commit Workflow

The full workflow in `.opencode/rules/commit-workflow.md` (classify -> validate -> changelog -> adversarial review (PASS) -> re-verify -> commit) is the gate chain that authorizes an autonomous commit — run it whenever a unit of work is complete, not only when asked. `/commit` enforces it. Skip steps only when the user explicitly requests it; if any non-skipped gate fails, do NOT commit.

## Documentation Style

All documentation — `docs/` architecture pages, ADRs, READMEs, design specs,
investigation reports, and prose summaries to the user — follows the **Pyramid
Principle with progressive disclosure**: lead with the outcome, then layer
supporting detail beneath it so a reader can stop at any depth and still leave
correct.

Default skeleton (collapse layers for short docs; never reorder so detail
precedes its conclusion):

1. **Outcome / Decision (TL;DR)** — the bottom line in 2–5 lines
2. **High-level flow / model** — one Mermaid diagram of the shape
3. **Key options or components** — a table or tight bullet list
4. **Rationale / trade-offs** — why it is this way; what was rejected
5. **Detailed behaviour** — implementation-level prose
6. **Appendix / deep reference** — exhaustive tables, edge cases, config

This is a strong default, not a rigid form — see `.opencode/rules/documentation-style.md`
for the full rule, the judgement guidance for short/reference docs, and how it
relates to diagrams, reports, and specs.

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

## Local (Uncommitted) Plans

Working/plan docs that should NOT be committed go in `docs/plans/` with a `.local.md` suffix
(e.g. `docs/plans/sre-chaos-features.local.md`). The `*.local.md` glob is gitignored, so these
files live alongside committed plans but never get staged. Use this for brainstorms, in-flight
design notes, and session-resume docs. Committed plans use a plain `.md` suffix in the same
directory. See `.opencode/rules/local-plans.md`.

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
