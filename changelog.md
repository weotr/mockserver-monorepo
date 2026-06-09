# Changelog
All notable and significant changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **Chaos auto-halt circuit-breaker** — when enabled (`chaosAutoHaltEnabled=true`), MockServer automatically disables all active service-scoped chaos profiles if the number of chaos-injected errors within a sliding window exceeds a configurable threshold, preventing chaos experiments from causing cascading outages. Reflected in the `mock_server_chaos_auto_halt` Prometheus counter and a WARN log event.
- **Dashboard Composer — template snippet palette** — the Response Template and Forward Template panels now include an "Insert snippet" button that opens a categorised palette of curated template snippets (request echoes, dynamic data, structure patterns). The palette is engine-aware, showing the correct Velocity / Mustache / JavaScript syntax for the selected template engine and including a live preview of each snippet's output.
- **Request replay from the dashboard** — a new `PUT /mockserver/replay` control-plane endpoint re-issues a previously recorded/proxied request to its original target and returns the upstream response (reuses the existing `NettyHttpClient`/forward client; 10 MB body-size cap; behind control-plane auth). The dashboard Traffic view gains a Replay button on every selected request that opens a dialog to re-issue the request with one click and inspect the live response.
- **Dashboard Library view — Import tab** — the Library view now opens on an Import tab (alongside Export) that lets users paste, upload, or URL-import specs and collections directly from the dashboard (Expectation JSON, OpenAPI, WSDL, HAR, Postman), wiring to the existing server endpoints without any new backend changes.
- **Dashboard "Get Started" onboarding panel** — new users land on a guided first-run view with action cards to import an OpenAPI spec, set up proxy recording, try docker-compose quick-start recipes, and explore the dashboard docs. The view is the default when no expectations or traffic exist; it auto-transitions to the dashboard once data arrives and remains accessible via the nav bar.
- **Developer-friendly `--dev` mode** — opt-in `--dev` CLI flag (or `MOCKSERVER_DEV_MODE=true` / `-Dmockserver.devMode=true`) applies laptop-appropriate defaults: `maxLogEntries=1000` and `maxExpectations=1000`, reducing memory usage for local development and test suites. Explicit configuration always overrides dev-mode defaults. Default behaviour (without `--dev`) is completely unchanged.
- **`mockserver-node` binary launcher** — `npx -p mockserver-node mockserver run -p 1080` downloads the JVM-less binary bundle for the current platform (no Java, no Docker), verifies its SHA-256, caches it per-user, and runs it. Honours `MOCKSERVER_BINARY_BASE_URL` (mirror), `MOCKSERVER_SKIP_BINARY_DOWNLOAD`, `MOCKSERVER_BINARY_CACHE` and `NODE_EXTRA_CA_CERTS`. Reference implementation of the on-demand-binary pattern for the client libraries.
- **Self-contained binary distribution (no JVM, no Docker)** — every release now publishes downloadable MockServer bundles (a jlink-trimmed Java runtime + the server + a `mockserver` launcher) for Linux, macOS and Windows (x86_64 + aarch64) as assets on the GitHub Release, each with a SHA-256. Download, extract, and run `bin/mockserver run -p 1080` — no pre-installed JVM or Docker required. Built from one host via `scripts/build-binary-bundle.sh` / `scripts/build-all-bundles.sh`.
- **Redesigned command-line interface** — a `mockserver` CLI (built on picocli) with `run` (default), `proxy`, `openapi`, `version` and `help` subcommands, per-command `--help`, short flags (`-p`/`--port`, `--proxy-to`, `--openapi`, `--init`, `--persist`, `-l`/`--log-level`) and scheme-aware proxy targets (`--proxy-to https://host` infers the port). The `org.mockserver.cli.Main` entry point, all existing flags (`-serverPort`, `-proxyRemotePort`, `-proxyRemoteHost`, `-logLevel`) and the configuration precedence (command line > system property > environment variable > properties file) remain fully supported. Documented in `docs/code/cli.md` and the *Running MockServer* site page.
- **CLI validation-proxy flags** — `--validate-openapi <spec>` and `--validate-enforce` on the `run` and `proxy` subcommands let users launch a validating proxy in one command, wiring directly to the existing `validateProxyOpenAPISpec` / `validateProxyEnforce` configuration properties.
- **Multi-language client libraries** — hand-written idiomatic clients for the MockServer control plane in **Go** (`mockserver-client-go`, pkg.go.dev), **.NET** (`MockServer.Client`, NuGet), **Rust** (`mockserver-client`, crates.io) and **PHP** (`mock-server/mockserver-client`, Packagist), covering create-expectation, verify/verifySequence, clear, reset and retrieve. Each ships unit tests plus a skippable integration test.
- **Testcontainers modules** — a `MockServerContainer` for **Node**, **Python**, **.NET**, **Go** and **Rust** (under `mockserver-testcontainers/`) that starts the `mockserver/mockserver` image, waits on `/mockserver/status` and exposes the mapped URL.
- **Editor integrations** — a **VS Code** extension (`mockserver-vscode`: start/stop the Docker container, open the dashboard, expectation snippets) and an initial **JetBrains/IntelliJ Platform** plugin scaffold (`mockserver-jetbrains`).
- **GHCR image mirror** — every release now mirrors the multi-arch images to `ghcr.io/mock-server/mockserver` (copied from Docker Hub by digest, cosign-signed). Error-isolated: a GHCR failure never affects the Docker Hub / ECR publish.
- **Automated MCP registry publishing** — the release pipeline publishes `server.json` to `registry.modelcontextprotocol.io` under the DNS-verified `com.mock-server/mockserver` namespace (non-interactive auth via an ed25519 key in Secrets Manager + an apex TXT record). Soft-fail — never blocks a release.
- **Release pipeline distribution channels** — soft-fail release components that publish the new clients, Testcontainers modules and editor extensions (NuGet, crates.io, Packagist, pkg.go.dev, npm, PyPI, VS Code Marketplace / Open VSX, JetBrains Marketplace), with post-release liveness checks.
- **One-command quick-start recipes** — curated `docker compose up` recipes under `examples/docker-compose/` for the most common use cases (`mock-from-openapi`, `record-replay-proxy`, `validation-proxy`, `chaos-proxy`), each self-contained with a short README and a "Getting started in 60 seconds" path in the repository README.
- **Dashboard request diffing from the Traffic view** — a "Compare" toggle in the Traffic inspector lets you pick two recorded or proxied requests and open the field-level diff inline (reusing the existing `PUT /mockserver/diff` endpoint and diff dialog), pre-populated with the two selected requests.
- **Consolidated "Self-Hosting MockServer" guide** — a single task-oriented site page (`/mock_server/self_hosting_mockserver.html`) that brings together every way to run MockServer yourself with copy-paste commands: Docker and the one-command docker-compose recipes, the `mockserver` CLI and the JVM-less binary bundle, Helm/Kubernetes, the executable JAR, Testcontainers, initializers/persistence, and bootstrapping from a browser HAR. Linked from the repository README.
- **Dashboard Chaos tab — full HTTP fault-type controls** — the HTTP Service Chaos register/edit form now exposes every `HttpChaosProfile` field: Retry-After header, body truncation fraction, malformed body toggle, slow (dribbled) response chunk size/delay, quota rate-limiting (name/limit/window/error status), degradation ramp, and outage time window — so users can configure the complete fault set without writing JSON.
- **MockServer UI docs — Traffic compare/diff and full Chaos fault set** — the *MockServer UI* site page (`/mock_server/mockserver_ui.html`) now documents the Traffic view's "Compare" toggle for diffing two captured requests (`PUT /mockserver/diff`) and the Chaos tab's complete HTTP service-chaos fault set wired to `PUT /mockserver/serviceChaos` (error/connection faults, body corruption, slow-response chunking, quota/rate limit, count and time windows, gradual degradation, GraphQL error envelope, and TTL).
- **Client-visible match feedback** — new opt-in config property `attachMismatchDiagnosticToResponse` (default `false`) attaches closest-match diagnostic info (header `x-mockserver-closest-match` + JSON body with per-field diffs) to 404 responses for unmatched requests, so test authors can see why their mock didn't match without checking the dashboard or logs.
- **Generalised capture-to-expectation** — the dashboard "Capture as Mock" dialog now works for **any** recorded or proxied request (plain HTTP, gRPC, GraphQL), not just LLM traffic. A three-level **matcher precision toggle** (Exact / Moderate / Loose) controls how tightly the generated `httpRequest` matcher binds: from method+path+query+headers+body down to method+path only. Generic captures register via `PUT /mockserver/expectation` with `httpResponse`; the existing LLM capture path is unchanged.
- **Create expectation from unmatched request** — the "Why Didn't This Match?" mismatch diagnostic dialog now includes a "Create Expectation" button that opens the capture-as-mock dialog pre-filled with the unmatched request, letting users turn a near-miss into a working stub in one click.
- **Opt-in realistic OpenAPI example data** — new config property `generateRealisticExampleValues` (default `false`) makes OpenAPI example generation produce schema/format-aware values via Datafaker (email, UUID, date, date-time, URI, hostname, IPv4/IPv6, byte, password, integers/numbers respecting min/max) instead of static placeholders, with a fixed seed for deterministic output. Existing behaviour is unchanged when the flag is off.
- **`mockserver-bom` (Bill of Materials)** — a new published artifact consumers can import into their `dependencyManagement` to pin every MockServer module **and** every third-party dependency MockServer relies on to a single, mutually consistent version. This makes downstream builds reproducible and satisfies strict version-alignment checks such as the Maven Enforcer `dependencyConvergence` rule, which previously flagged the differing transitive versions MockServer resolves internally (via its parent POM's `dependencyManagement`) but did not export to consumers. Usage: import `org.mock-server:mockserver-bom` with `<type>pom</type>` and `<scope>import</scope>`.

### Changed
- **CI** — the build pipeline now runs unit tests for the new Go, .NET, Rust and PHP libraries, the five Testcontainers modules and the editor extensions (each in its language toolchain Docker image), triggered by changes under their paths.
- **Slimmer `mockserver-client-java` classpath** — the Java client no longer drags the server-only engines (Velocity/Mustache templating, GraalVM JavaScript, WASM/Chicory, DataFaker, protobuf/gRPC transcoding **and the Swagger/OpenAPI parser**) onto a consumer's classpath when it is the only MockServer artifact depended upon. Those all run inside the server, never in the client JVM, so they are excluded from the client's `mockserver-core` dependency. `mockserver-core`'s object mapper now registers its Swagger-coupled serializers only when swagger-core is present (see Fixed), so the client serialises OpenAPI expectations as plain spec strings without the parser on its classpath. In-process-server usages (e.g. `mockserver-junit-jupiter` → `mockserver-netty`) are unaffected — the engines still arrive via the server module. Verified by the full 155-test client suite, 718 core serialization/OpenAPI tests, and a runtime check that round-trips expectations with swagger genuinely absent.

### Fixed
- **Startup crash when a properties file has entries** ([#2338](https://github.com/mock-server/mockserver-monorepo/issues/2338)) — MockServer 7.0.0 failed to start with `NoClassDefFoundError: Could not initialize class org.mockserver.configuration.ConfigurationProperties` (caused by a `NullPointerException` during static initialisation) whenever a `mockserver.properties` file — or the Helm chart's `app.config.properties` — contained any entries. The startup property-dump redaction added in 7.0.0 read its `SENSITIVE_SUBSTRINGS` set from the `PROPERTIES` static initialiser but declared it ~3000 lines later in the class, so it was still `null` when class initialisation ran (a static-init ordering bug). The redaction fields are now initialised before the property file is read, with a regression test that initialises `ConfigurationProperties` afresh against a populated property file.
- **Downstream `dependencyConvergence` failures** — consuming MockServer (e.g. `mockserver-client-java` with `MockServerContainer`) under the Maven Enforcer `dependencyConvergence` rule failed with multiple version-conflict errors, because MockServer's transitive version pins lived in the parent POM's `dependencyManagement`, which Maven does not export to consumers. Three changes address this: a new **`mockserver-bom`** to import (above); the slimmer client classpath (above); and pruning the stale `velocity-engine-core 2.3` that `velocity-tools-generic` dragged in alongside the `2.4.1` the build already uses (all 21 Velocity engine tests still pass). With the BOM imported, a client-only consumer's convergence errors drop from 17 to 0.
- **Latent undefined `${jetty.version}` in the parent POM** — three Jetty HTTP-client `dependencyManagement` entries referenced a `jetty.version` property that was only ever defined in the `examples/java` module, so the managed versions were unresolved for any other consumer of the published parent POM. The dead entries were removed from the parent and the `examples` module now declares its Jetty client versions explicitly.
- **Object mapper Swagger coupling made optional** — `ObjectMapperFactory` registered its Swagger/OpenAPI-coupled serializers (the schema serializers and the OpenAPI-derived `HttpRequestsPropertiesMatcher` serializer) unconditionally, so initialising the object mapper loaded `io.swagger.v3.oas.models.*` even on a client that never produces those objects. They are now isolated in a `SwaggerSerializers` helper and registered only when swagger-core is on the classpath, which is what lets `mockserver-client-java` exclude the Swagger/OpenAPI parser (eliminating the bulk of a client-only consumer's remaining `dependencyConvergence` conflicts). The single `com.github.fge` (json-tools) pretty-print call on the client-reachable path was replaced with a small `JsonPrettyPrinter`, and `jackson-datatype-jsr310` — used directly by the object mapper but previously only arriving transitively via the Swagger parser — is now a direct `mockserver-core` dependency. Server behaviour is unchanged (swagger-core is always present there).
- **Remaining non-Swagger convergence conflicts pruned** — with the Swagger parser excluded from the client, three transitive version splits remained for a client-only consumer: `slf4j-api` (older versions via `java-uuid-generator`, `json-path` and `com.networknt:json-schema-validator`), `jackson-annotations` (2.21 via the validator's Jackson 3 transitive) and `jakarta.xml.bind-api` (2.3.3 via `xmlunit-core`). `mockserver-core` now excludes those stale transitive edges; in every case it already declares the winning version directly (`slf4j-api` 2.0.18, `jackson-annotations` 2.22, `jakarta.xml.bind-api` 4.0.5), so its own resolved classpath is unchanged (255 XML/JSON-schema/JSON-path core tests still pass). A consumer depending only on `mockserver-client-java` now passes the Maven Enforcer `dependencyConvergence` rule with **zero** errors even without importing the BOM.

## [7.0.0] - 2026-06-06

This cycle centres on **first-class LLM / AI-agent mocking** and a major **platform modernisation**, alongside broader resilience-testing and dashboard improvements. Highlights (see the per-item entries below for detail):

- **HTTP/3 streaming responses** — SSE, chunked proxy forwarding, and LLM streaming are now fully supported over HTTP/3 (QUIC). Each body chunk is sent as an HTTP/3 DATA frame with backpressure via `StreamingBody.requestMore()`; the QUIC stream is cleanly shut down on completion or error. Bundled native QUIC removes the need for a separately downloaded BoringSSL library.
- **TPROXY (IP_TRANSPARENT) transparent proxy** — a new default-off `transparentProxyTproxy` configuration property enables `IP_TRANSPARENT` socket binding so that with iptables TPROXY rules the kernel preserves the original destination as the listening socket's local address, which MockServer reads via `channel.localAddress()` — avoiding the conntrack `SO_ORIGINAL_DST` lookup used with REDIRECT rules. Requires Linux, `epoll` transport, and `CAP_NET_ADMIN`. Verified end-to-end with a real Docker `NET_ADMIN` integration test.
- **Testcontainers 1.21.4** — upgrades from 1.20.6, fixing `DockerClientFactory.isDockerAvailable()` returning `false` on Docker Desktop 4.67 / Engine API 1.54 (docker-java 3.4.2 probe fix).
- **Clustered MockServer state (opt-in)** — a new `mockserver-state-infinispan` module provides an embedded Infinispan `StateBackend` that can replicate expectations and scenario state across a JGroups cluster. Single-node behaviour is completely unchanged (the in-memory `StateBackend` remains the default). New configuration properties: `stateBackend`, `clusterEnabled`, `clusterName`, `clusterTransportConfig`, `blobStoreType`.
- **LLM / AI-agent mocking suite** — provider-correct mock completions and streaming for seven providers (Anthropic, OpenAI, OpenAI Responses, Azure OpenAI, Gemini, Bedrock, Ollama), with embeddings for OpenAI and Azure OpenAI; multi-turn scripted conversations with per-session isolation and deterministic prompt normalisation; and a runtime-LLM client SPI (off unless configured, fails closed) that powers the opt-in features. A broad MCP toolset drives it from an agent: `mock_llm_completion`, `create_llm_conversation`, `verify_tool_call`, `explain_agent_run` (with a correlated call graph), `verify_structured_output`, `verify_cost_budget`, `detect_llm_drift`, `mock_adversarial_llm_response`, and `run_mcp_contract_test`.
- **Agent resilience & correctness testing** — structured-output (JSON-Schema) validation on both the response path (`outputSchema`, fail-soft) and the verification path (`verify_structured_output`); a deterministic CI **cost-budget gate** (`verify_cost_budget`) over a built-in pricing table; declarative **LLM fault/chaos profiles** (probabilistic provider errors, mid-stream truncation, malformed SSE) plus a **stateful request-quota** rate limit; VCR record/replay with strict mode and body/header redaction; a prompt-injection / adversarial-response harness; and OpenTelemetry GenAI span + metrics export. The dashboard surfaces all of it (conversation wizard, sessions & call-graph, metrics view, export).
- **HTTP chaos/fault injection** — a general `HttpChaosProfile` (probabilistic error status + latency) attachable to any mocked **or forwarded** response, making MockServer usable as a chaos proxy for unreliable upstreams.
- **Platform modernisation (breaking)** — minimum runtime raised to **Java 17**; full **Jakarta EE 10 / Servlet 6** migration (Spring 7 / Boot 4, Tomcat 11, Jetty 12, Jersey 4, Netty 4.2); `json-schema-validator` 3.x; a bundled DataFaker template helper; and ZGC tuning guidance.

### Security

- **Released Docker images are now cosign-signed by digest** (Docker Hub and ECR Public), using the same signing key infrastructure as the Helm OCI chart. Consumers can verify image provenance with `cosign verify`. Signing is non-fatal in the pipeline if the key is unavailable, so it never blocks a release.
- **Website security hardening** — the documentation site (mock-server.com) now sends `Strict-Transport-Security`, `Content-Security-Policy`, `X-Content-Type-Options`, `X-Frame-Options`, and `Referrer-Policy` response headers via CloudFront, and the domain publishes CAA records pinning certificate issuance to Amazon.
- **Build/release infrastructure hardening (internal)** — least-privilege scoping of CI secrets per Buildkite agent queue, removal of release-only permissions (ECR push) from the PR-build queue, secrets passed to release containers via `0600` files instead of `docker -e` environment variables, robust git-push-token cleanup, scoped cross-account `AssumeRole` (ExternalId) and tfstate IAM, full VPC flow logging, GuardDuty→SNS alerting, CloudTrail data-events on secrets/state, and SSE-KMS on the state and AWS Config buckets. See `docs/infrastructure/aws-infrastructure.md`, `docs/infrastructure/ci-cd.md`, and `docs/operations/website.md`.

### Added

- Added a **daily performance-regression pipeline** (notify-only) that guards response latency, throughput, and CPU/memory against drift across releases. It runs on a dedicated, pinned, on-demand, scale-to-zero Buildkite `perf` queue and fires once per day only when `master` moved since the last run. Each run measures four behaviours (mock match, forward/proxy, Velocity template, large-body) over HTTP and HTTPS/HTTP-2 (`k6/regression.js`), a sustained resource-growth run that surfaces "increases over time" regressions such as the issue #2329 O(n) log-eviction CPU climb (`k6/growth.js`, CPU/heap/latency slope ratios), and the JMH `MatchingBenchmark` allocation backstop. Results are persisted to S3 and each run is compared against a rolling median+MAD baseline of recent runs, posting a Buildkite annotation table when a metric regresses. See `docs/operations/performance-tuning.md`.

#### LLM & AI-agent mocking
- Added a dedicated **`retrieve_logs` MCP tool** so an AI assistant debugging a failing test can pull MockServer's recorded log messages (request matching, mismatches, actions and errors) directly. It is a thin, discoverable wrapper over the existing LOGS retrieval path (shared with `raw_retrieve`), with an optional `correlationId` filter (trace one request's full lifecycle) and a `limit` (most-recent N, default 100, max 500). This fills the gap left by its sibling tools `retrieve_recorded_requests` / `retrieve_request_responses`, which already existed. See the AI/MCP tools page.
- Added a **runtime-LLM client SPI** (`org.mockserver.llm.client`) that lets MockServer call a real LLM you already run, as the foundation for opt-in features such as drift detection and exploratory semantic matching. Mirrors the existing codec registry: an `LlmClient` per provider (Ollama, OpenAI, OpenAI Responses, Azure OpenAI, Anthropic, Gemini, Bedrock) registered in `LlmClientRegistry`, an immutable `LlmBackend` config (with the API key redacted in logs), and a three-layer `LlmBackendResolver` (provider env vars → `mockserver.llmProvider`/`llmApiKey`/`llmModel`/`llmBaseUrl` → named-backends JSON via `mockserver.llmBackendsConfig`). All runtime-LLM use goes through `LlmCompletionService`, which is **off unless a backend is configured**, **fails closed** on any timeout/error/non-2xx (never flipping a deterministic result), and caches per normalised prompt for reproducibility. Ollama is the reference backend (no key, local); Bedrock builds the Anthropic-on-Bedrock request and relies on the `headers` escape hatch pending automatic SigV4 signing. See the configuration properties page and `docs/code/llm-mocking.md`.
- LLM conversation mocks can now opt into deterministic **prompt normalisation** before the `latestMessageContains` / `latestMessageMatches` predicates are evaluated, so a match is not blocked by cosmetic differences in dynamically-assembled agent prompts. A new `normalization` block on `conversationPredicates` (also exposed per-turn in the `create_llm_conversation` MCP tool and the dashboard conversation wizard) supports collapsing whitespace, lowercasing, sorting JSON object keys, dropping built-in volatile values (ISO-8601 timestamps, UUIDs, `req_`/`msg_`/`call_` ids), and dropping named JSON fields. Normalisation is pure and idempotent — it never makes a test flaky — and has no effect unless a text predicate is set. See the AI/MCP tools page and `docs/code/llm-mocking.md`.
- Added two MCP tools for **agent-run analysis and tool-call assertions**, both backed by a new deterministic `org.mockserver.llm.analysis.AgentRunAnalyzer` that reconstructs an agent run by decoding the LLM requests MockServer recorded. `verify_tool_call` asserts that an agent called a named tool a given number of times (`atLeast`/`atMost`, with an optional regex over the tool-call arguments); `explain_agent_run` summarises the run's structure (message and assistant-turn counts, the ordered tool-call sequence, tool results, and the latest message role). Read-only and offline — no LLM call. See the AI/MCP tools page and `docs/code/llm-mocking.md`.
- Added a **correlated agent-run call graph**. `AgentRunAnalyzer.buildCallGraph` reconstructs a recorded run as a graph — a node per message and per assistant tool call, with `NEXT` (sequence), `INVOKES` (turn→tool call), and `RESULT` (tool call→its result, correlated by tool-call id) edges — exposed in the `explain_agent_run` MCP result as a `callGraph` field. The dashboard **Sessions** view renders it per session (a "Call graph" button loads it via `explain_agent_run`): each step shows the message role and the tool calls it made, with a result indicator, plus a copyable Mermaid `flowchart` source. Deterministic and read-only. See `docs/code/llm-mocking.md`.
- Added opt-in, **exploratory semantic prompt matching** for LLM conversations: a `semanticMatch` turn predicate (the intent the latest message should express) judged by a runtime LLM via the client SPI. It is **off by default and never on the assertion path** — the predicate is ignored unless `mockserver.llmSemanticMatchingEnabled` is set *and* a runtime backend resolves, so deterministic matching is never affected by default. Non-deterministic by nature (a live LLM judge), so it is documented for exploration only, never for CI assertions; fails closed (a non-affirmative/empty/errored judge does not match). Exposed in the Java `TurnBuilder.whenSemanticMatch`, the `create_llm_conversation` MCP tool, and the dashboard wizard (clearly flagged exploratory). See `docs/code/llm-mocking.md`.

#### LLM resilience, validation & cost testing
- Added a **`verify_structured_output` MCP tool**: validate that the structured (JSON) output of recorded LLM responses conforms to a JSON Schema. It decodes each recorded response for a given provider (via the runtime-LLM client SPI), extracts the assistant's output text, and checks it against the schema — so you can assert that an agent (or a mocked model) produced schema-valid structured output. Read-only and deterministic; responses with no text output are reported separately as skipped, and the result gives per-response conformance with validation errors. See the AI/MCP tools page and `docs/code/llm-mocking.md`.
- A mock LLM completion can now declare an **`outputSchema`** (a JSON Schema) that its response `text` is expected to conform to. As the response is encoded, MockServer validates the configured text against the schema and, on a mismatch, **fail-soft**: the response body is returned exactly as configured but an `x-mockserver-structured-output-invalid` diagnostic header is added and a warning logged — so a malformed structured-output fixture is surfaced immediately while a deliberately non-conforming fixture still returns unchanged. A blank schema, absent text, or a malformed schema are all treated as "nothing to check" and never affect the response. Exposed on the Java `Completion.withOutputSchema(...)`, the `outputSchema` field in expectation JSON, and the `mock_llm_completion` MCP tool (string or inline object). Complements the read-side `verify_structured_output` tool. See the AI/MCP tools page and `docs/code/llm-mocking.md`.
- Added a **`verify_cost_budget` MCP tool**: a deterministic, read-only cost gate for agent runs. It decodes each recorded LLM response for a provider (via the runtime-LLM client SPI), sums the input/output tokens from each response's usage, prices them with a new built-in pricing table (`org.mockserver.llm.cost.LlmPricing`, mirroring the dashboard's `llmPricing.ts` — same prefixes/rates), and asserts the total estimated USD cost is at or below `maxCostUsd`. The model can be pinned via a `model` param or read per-response from the recorded request body; responses with no usage are skipped and responses whose model has no known price are reported as `unpriceable` and excluded from the total. The result gives token/cost totals, `withinBudget`, and a per-response breakdown. Pricing is public list pricing captured 2025-Q4 (an estimate, not an invoice). See the AI/MCP tools page and `docs/code/llm-mocking.md`.
- Added declarative **LLM fault/chaos profiles** for resilience testing, attachable to any mock LLM response (`mock_llm_completion`, each `create_llm_conversation` turn, the Java `LlmConversationBuilder`, and raw expectation JSON via a `chaos` block). Supports probabilistic provider errors (e.g. 429/529 with a `Retry-After` header), mid-stream truncation of an SSE stream (keep a leading fraction of events), and appending a malformed (broken-JSON) SSE chunk. Errors are deterministic at probability 0.0/1.0 and reproducible at fractional probabilities via a `seed`; truncation and malformed-SSE are always deterministic. A new `LLM_CHAOS_INJECTED_COUNT` metric tracks injections. The dashboard conversation wizard exposes the profile per turn. See the AI/MCP tools page and `docs/code/llm-mocking.md`.
- Added a **stateful request quota** to the LLM chaos profile — a deterministic fixed-window rate limit, the stateful counterpart to the existing probabilistic 429. Set `quotaName`, `quotaLimit`, and `quotaWindowMillis` (optional `quotaErrorStatus`, default 429) on a `chaos` block and requests beyond the limit within the window are rejected with that status and the `retryAfter` header. Expectations sharing a `quotaName` share one counter (model an upstream account limit across several mocks); the count resets when the window elapses and on server reset. Backed by a new process-wide, thread-safe `org.mockserver.llm.LlmQuotaRegistry` (injectable clock for deterministic tests). Exposed in expectation JSON, the `mock_llm_completion`/`create_llm_conversation` `chaos` MCP parameter, and the Java `LlmChaosProfile`. A misconfigured/partial quota fails open (never rate-limits). See the AI/MCP tools page and `docs/code/llm-mocking.md`.
- Added a **prompt-injection / adversarial-response harness** for testing agent resilience. A new `mock_adversarial_llm_response` MCP tool returns a curated adversarial payload as the mock LLM response — prompt-injection ("ignore previous instructions…"), jailbreak persona-swaps, data-exfiltration requests, malformed/truncated JSON, an empty response, and an over-long repetition — so you can verify your agent *resists* hostile or malformed model/tool output. Backed by `AdversarialResponseLibrary` (deterministic; the payloads are benign test fixtures, not working exploits). A defensive testing aid. See the AI/MCP tools page and `docs/code/llm-mocking.md`.
- Added **drift detection** for LLM fixtures (`detect_llm_drift` MCP tool): replays a recorded cassette's exchanges against the live provider (via the runtime-LLM client SPI) and reports **structural** drift — new/removed fields and type changes in the responses — not semantic differences, so benign wording changes never flag. Built on a reusable, pure `StructuralShapeDiff` and a `DriftDetector` that **fails closed** per exchange (a network error or non-2xx live response is reported as could-not-check, never as drift, never thrown). Off unless a runtime backend is configured. Intended for an opt-in/scheduled CI lane (real API keys + tokens), never the per-commit build. See the AI/MCP tools page and `docs/code/llm-mocking.md`.
- Completed the **VCR (record/replay) toolkit** for LLM fixtures with three additions. (1) **Strict mode** — `load_expectations_from_file` accepts `strict` (or set `mockserver.llmVcrStrict`), which registers a low-priority catch-all per cassette path so a request matching no recorded fixture returns HTTP 599 instead of silently falling through. (2) **Body-field redaction** — `record_llm_fixtures` accepts `redactBodyFields` (or set `mockserver.fixtureBodyRedactFields`) to redact named JSON fields from recorded request/response bodies, complementing the existing header redaction. (3) **Replay field normalisation** — `load_expectations_from_file` accepts `normalizeRequestBodyFields` to drop volatile JSON fields from each recorded request body and match the remainder loosely (ignoring extra fields), so per-run values (request ids, timestamps) do not block replay. These are operational settings exposed via config and MCP. See the AI/MCP tools and configuration properties pages.

#### HTTP chaos & protocol contract testing
- Added a **time-to-live (auto-revert) to service-scoped chaos** — an optional `ttlMillis` on a `PUT /mockserver/serviceChaos` registration makes the chaos automatically revert after that many milliseconds (a "dead-man's switch" so a fault self-heals even if the matching clear is never sent — e.g. an external chaos orchestrator crashes mid-experiment). It is also the one-shot time-box form: a single call breaks a host for a bounded window. Expiry is measured with the controllable clock (real-time by default, deterministic under `PUT /mockserver/clock`) and is applied lazily on the next lookup. Exposed via the endpoint, the Java/Node/Python/Ruby clients (`setServiceChaos(host, chaos, ttlMillis)` / `ttl_millis`), and the `manage_service_chaos` MCP tool. See the [Chaos Testing](/mock_server/chaos_testing.html#service_scoped_chaos) page.
- Added **service-scoped chaos** — register one `HttpChaosProfile` for an upstream host and have it applied to all matched forwards to that host, instead of attaching a `chaos` block to every forwarding expectation (the "break service X" control for running MockServer as a chaos proxy). Manage it through a new control-plane endpoint `PUT/GET /mockserver/serviceChaos` (`{"host":...,"chaos":{...}}` to register, `{"host":...,"remove":true}` to remove, `{"clear":true}` to clear all), protected by control-plane authentication. Resolution happens only on the matched-forward path keyed by the request `Host` header (case-insensitive, port-ignored); an expectation's own `chaos` always takes precedence, the anonymous proxy fall-through is unaffected, and registrations clear on server reset. Backed by a new process-wide `org.mockserver.mock.action.http.ServiceChaosRegistry`. Convenience wrappers are exposed in all four clients (`setServiceChaos`/`removeServiceChaos`/`clearServiceChaos`/`serviceChaosStatus` in Java/Node, the snake-case equivalents in Python/Ruby) and via the `manage_service_chaos` MCP tool. See the [Chaos Testing](/mock_server/chaos_testing.html#service_scoped_chaos) page.
- Added **gradual degradation** to the HTTP `chaos` block — a `degradationRampMillis` that linearly ramps `errorProbability` and `dropConnectionProbability` from 0 up to their configured values over the window from the expectation's first match, modelling a dependency that deteriorates over time (for alerting / SLO-burn tests). The ramp is measured with MockServer's controllable clock, so it is deterministic under clock freeze/advance with no real-time waiting; only the probabilistic rates ramp (latency, body corruption, slow response and quota are unaffected). Exposed in expectation JSON, the Java/Node/Python/Ruby clients, and the `create_expectation` `chaos` MCP parameter. See the [Chaos Testing](/mock_server/chaos_testing.html#gradual_degradation) page.
- Added a **stateful request quota** to the HTTP `chaos` block — a deterministic fixed-window rate limit, the HTTP counterpart of the existing probabilistic 429 and of the LLM quota. Set `quotaName`, `quotaLimit` and `quotaWindowMillis` (optional `quotaErrorStatus`, default 429) and requests beyond the limit within the window are rejected with that status and the `retryAfter` header. Expectations sharing a `quotaName` share one counter (model an upstream account limit across several mocks); the count resets when the window elapses and on server reset. The quota gate takes priority over the probabilistic error and the body/slow faults (after connection-drop). Backed by a new process-wide, thread-safe `org.mockserver.mock.action.http.HttpQuotaRegistry` (separate from the LLM quota registry). Exposed in expectation JSON, the Java/Node/Python/Ruby clients, and the `create_expectation` `chaos` MCP parameter; metered as `fault_type=quota`. See the [Chaos Testing](/mock_server/chaos_testing.html#request_quota) page.
- Added a **slow (dribbled) response** fault to `HttpChaosProfile` — `slowResponseChunkSize` + `slowResponseChunkDelay` trickle the response body to the client in small chunks with a delay between each (via chunked transfer-encoding), for testing read timeouts and slow-network handling (distinct from `latency`, which delays the whole response by a fixed amount). Both fields are required; deterministic; applies to the real mocked or forwarded response within the active count and outage windows; skipped for streaming bodies; metered as `fault_type=slow`. Exposed in expectation JSON, the Java/Node/Python/Ruby clients, and the `create_expectation` `chaos` MCP parameter. See the [Chaos Testing](/mock_server/chaos_testing.html#slow_response) page.
- Added **response-body corruption** faults to `HttpChaosProfile` — `truncateBodyAtFraction` keeps only a leading fraction of the body bytes (e.g. `0.5` returns the first half, `0.0` empties it) and `malformedBody` appends a broken-JSON fragment so the payload fails to parse, for testing client-side body-parsing and partial-response resilience. Both are deterministic (no probability draw), apply to the real mocked or forwarded response within the active count and outage windows, preserve the `Content-Type` and drop any stale `Content-Length` (the encoder then sets the correct length) so the response stays well-framed, and are skipped for streaming bodies. Connection-drop and error injection still take priority (an injected error body is never corrupted). Exposed in expectation JSON, the Java/Node/Python/Ruby clients, and the `create_expectation` `chaos` MCP parameter; metered as `fault_type=truncate` / `fault_type=malformed`. See the [Chaos Testing](/mock_server/chaos_testing.html#body_corruption) page.
- Added **time-based outage windows** (`outageAfterMillis` / `outageDurationMillis`) to `HttpChaosProfile` — chaos becomes active a configurable time after the expectation's first match and (optionally) self-heals after a bounded duration, modelling a dependency that degrades for a transient window then recovers. The window is measured with MockServer's controllable clock, so it is deterministic under clock freeze/advance (`PUT /mockserver/clock`) with no real-time waiting; it composes with the count window and the probability fields.
- Added **connection-drop chaos fault** (`dropConnectionProbability`) to `HttpChaosProfile` — probabilistic TCP connection drops (no response sent) on both mocked and forwarded responses, simulating hard network failures. Drop faults take priority over error and latency injection (drop > error > latency). Uses a derived seed for independent but reproducible draws alongside `errorProbability`.
- Added declarative **HTTP chaos/fault injection** (`HttpChaosProfile`) for resilience testing, attachable to any expectation via a top-level `chaos` block. Supports probabilistic error-status injection (e.g. 500, 503, 429 with an optional `Retry-After` header) and latency injection. Works on **both mocked responses** (RESPONSE, RESPONSE_TEMPLATE, RESPONSE_CLASS_CALLBACK) **and forwarded/proxied responses** (FORWARD, FORWARD_TEMPLATE, FORWARD_CLASS_CALLBACK, FORWARD_REPLACE, FORWARD_VALIDATE), making MockServer usable as a chaos proxy for testing how applications handle unreliable upstream dependencies. Deterministic at `errorProbability` 0.0/1.0; reproducible at fractional probabilities via a `seed`. Exposed in the Java client (`ForwardChainExpectation.withChaos()`), REST API, and expectation JSON. See the new [Chaos Testing & Fault Injection](/mock_server/chaos_testing.html) documentation page.
- Added **count-based stateful faults** to the HTTP `chaos` block — a `succeedFirst` / `failRequestCount` request-count window so an expectation can succeed the first N matches, then fault the next M, then recover. Expresses fail-first-N-then-recover (retry/backoff testing), succeed-N-then-fail, and fail-only-the-Nth, on both mocked and forwarded responses; deterministic by match index, composes with `errorProbability`, and is backward compatible (no window fields = unchanged). See the [Chaos Testing](/mock_server/chaos_testing.html#stateful_count_based_faults) page.
- Added a **Driving MockServer from Chaos Orchestrators** guide showing how external chaos-engineering tools drive MockServer's service-scoped chaos through the control-plane endpoint — concrete inject/verify/revert recipes for Chaos Toolkit, AWS FIS (SSM RunShellScript), Azure Chaos Studio (Automation runbook / pipeline), LitmusChaos (BYOC cmdProbe/httpProbe), and any cron/CI/Step Functions scheduler — all using the `ttlMillis` dead-man's switch so a fault auto-reverts even if the orchestrator never sends the clear. See the [Chaos Orchestrators](/mock_server/chaos_testing_orchestrators.html) page.
- Added a **Chaos Proxy in Kubernetes** guide showing how to deploy MockServer as a chaos proxy in Kubernetes to inject faults into real service-to-service and external API calls — reverse-proxy, egress/forward-proxy, and sidecar deployment patterns with concrete Kubernetes manifests and expectation JSON examples. See the [Chaos Proxy in Kubernetes](/mock_server/chaos_testing_kubernetes.html) page.
- Added a **chaos-proxy example to the Helm chart** — a commented reverse-proxy + chaos `initializerJson` block in `values.yaml` and a "Chaos Proxy (fault injection)" section in the chart README, showing how to deploy MockServer in front of an upstream Service and inject faults through the chart's inline configuration. Links to the Chaos Testing and Chaos Proxy in Kubernetes guides.
- Added an **MCP server conformance tester** (`run_mcp_contract_test` MCP tool): point it at a target MCP (Model Context Protocol) server's Streamable HTTP endpoint and it runs the required JSON-RPC handshake and core methods — `initialize`, `notifications/initialized`, `ping`, `tools/list`, and unknown-method rejection (expects error code `-32601`) — validating the **shape** of each response (JSON-RPC 2.0 envelope and required result fields), never the semantics of any tool. Optionally exercises one `tools/call` (skipped by default, since a call may have side effects on the target). Fully deterministic and offline-from-LLMs (no model is involved); each request has a 10-second timeout. Backed by a network-free, unit-testable `McpContractTest` orchestrator with an injected transport. See the AI/MCP tools page and `docs/code/llm-mocking.md`.

#### Observability & dashboard
- Added an **active service-scoped chaos gauge** — a Prometheus `mock_server_active_service_chaos` gauge (when `metricsEnabled`) labeled by `fault_type` (`drop`/`error`/`latency`/`truncate`/`malformed`/`slow`/`quota`), reporting per fault type how many currently-active service-scoped chaos profiles are configured with that fault (a profile with several faults counts under each). It is a callback gauge that reads `ServiceChaosRegistry` at scrape time, so each series drops to 0 as profiles are cleared or their TTLs lapse (making `sum(mock_server_active_service_chaos) > 0` a natural "chaos still live" alert and letting you alert on a specific fault type), and it is mirrored over OTLP alongside the chaos-fault-injection counter. See the [Chaos Testing](/mock_server/chaos_testing.html) page.
- The dashboard **Metrics view "HTTP Chaos Faults" section now shows every fault type** the server emits (`drop`, `error`, `latency`, `truncate`, `malformed`, `slow`, `quota`) — previously only `error` and `latency` — with a per-fault-type chart of cumulative injections and a separate per-fault-type chart of the active service-scoped chaos gauge (plotted by type rather than as a single counter). Fault types are discovered from the scrape, so a future type renders automatically without a UI change. See `docs/code/dashboard-ui.md`.
- Added a **Chaos tab to the dashboard UI** for managing service-scoped chaos interactively (`ServiceChaosPanel`): register a host with an error status / error probability / drop probability / latency (and an optional TTL), see every active registration with a summary of its faults, watch the live TTL auto-revert countdown, and remove a single host or clear them all. It polls `GET /mockserver/serviceChaos` and drives the same control-plane endpoint as the clients and the `manage_service_chaos` MCP tool. The `/mockserver/serviceChaos` responses now carry CORS headers unconditionally (matching the metrics and MCP endpoints), so the dashboard works when served from a different origin (e.g. the UI dev server) without needing `enableCORSForAPI`. See the [Chaos Testing](/mock_server/chaos_testing.html#service_scoped_chaos) page and `docs/code/dashboard-ui.md`.
- Added optional **OpenTelemetry (OTLP) export**, in two independent, off-by-default parts. (1) **Metrics export** — MockServer's existing metrics (the same explicitly-defined gauges already exposed for Prometheus: `REQUESTS_RECEIVED_COUNT`, `RESPONSE_EXPECTATIONS_MATCHED_COUNT`, the LLM/SSE/chaos counters, etc.) can also be pushed to an OTLP collector as an alternative to Prometheus (`mockserver.otelMetricsEnabled`). Implemented as OTel observable gauges reading the current values, so the Prometheus and OTLP views stay in lock-step. (2) **GenAI span export** — MockServer emits one explicit OpenTelemetry GenAI semantic-convention span per LLM completion it serves (`gen_ai.system`, `gen_ai.request.model`, `gen_ai.usage.input_tokens`/`output_tokens`, `gen_ai.response.finish_reasons`, tool-call count) (`mockserver.otelTracesEnabled`). These are spans MockServer codes deliberately — **no auto-instrumentation** is added. Both use the OTLP HTTP/protobuf exporter with the JDK HttpClient sender (no gRPC/OkHttp), share `mockserver.otelEndpoint`, and are fail-soft (a setup error logs one line and never stops the server or affects a response). `io.opentelemetry.*` is relocated in the shaded JAR. See the configuration properties page.
- Added **JVM runtime metrics** to MockServer's Prometheus endpoint (`GET /mockserver/metrics`, when `metricsEnabled`): heap and non-heap memory (used / committed / max, labelled by `area`), live and daemon thread counts, and total GC collection count and time. Exposed via a dependency-free collector that reads JDK MX beans, so Grafana and the dashboard Metrics view can chart process health alongside the existing request/action counters.
- Added a **request-latency histogram** to MockServer's Prometheus endpoint (`mock_server_request_duration_seconds`, when `metricsEnabled`): classic histogram buckets from 0.5 ms to 10 s, recorded per request from receipt to response. Enables latency percentiles (p50 / p95 / p99 via `histogram_quantile`) in Grafana and the dashboard. Recording is fully gated behind `metricsEnabled`, so it adds nothing to the request path when metrics are off.
- Added a **Metrics view** to the dashboard UI: a new top-bar tab that polls MockServer's Prometheus endpoint (`GET /mockserver/metrics`) and renders live activity — request / matched / not-matched / forwarded counts with inline sparklines, a derived requests-per-second throughput chart, a per-action breakdown, **JVM heap / thread / GC panels**, and **request-latency percentiles (p50 / p95 / p99)** — the JVM and latency panels appear only when the server exposes those metrics — plus the served MockServer version. Time-series charts use `@mui/x-charts`, lazy-loaded so they add nothing to the initial dashboard load. It degrades gracefully: when MockServer is started without `metricsEnabled` the endpoint returns 404 and the view shows guidance to enable it (`-Dmockserver.metricsEnabled=true` / `MOCKSERVER_METRICS_ENABLED=true`). See `docs/code/dashboard-ui.md`.
- Recorded requests can now be exported as **cURL commands**. A new `CURL` value for the `/mockserver/retrieve` `format` parameter (valid for `type=REQUESTS` and `type=REQUEST_RESPONSES`) renders one `curl` command per recorded request via the existing `HttpRequestToCurlSerializer`; the expectation scopes return a clear "not supported" message. Surfaced in the dashboard Export page. See the configuration/retrieve docs.

#### Templating & runtime
- Added a **clock-control endpoint** (`PUT /mockserver/clock`, `GET /mockserver/clock`) for deterministic time-based testing. Freeze the server clock at a specific ISO-8601 instant, advance it by a duration in milliseconds, or reset it to real wall-clock time. The controllable clock affects response template date/time helpers (`now_iso_8601`, `now_epoch`, `now_rfc_1123`, and the `dates` helper object) and **expectation TimeToLive expiry**, so frozen time prevents expectations from expiring mid-test. Protected by control-plane authentication (JWT/mTLS) when configured. Limitation: event-log timestamps and JWT token issuance use a separate time source and are not affected. See the [Clearing, Resetting & Clock Control](/mock_server/clearing_and_resetting.html#clock_control) page.
- DataFaker (`net.datafaker:datafaker:2.5.4`) is now bundled as a template helper. A single shared `Faker` instance is exposed as `faker` in all three response-template engines (Velocity, Mustache, JavaScript) via `TemplateFunctions.BUILT_IN_HELPERS`, giving templates access to 250+ realistic-fake-data providers (`faker.name().firstName()`, `faker.internet().emailAddress()`, `faker.address().city()`, etc.). The instance is thread-safe and produces fresh random values on each call. See the consumer docs (response templates page) for the full provider list and per-engine syntax. Java 17 unlocked this — DataFaker 2.x requires Java 17; the previous Java 11 floor pinned us to the abandoned 1.9.0 line.
- Documented ZGC (`-XX:+UseZGC`) as a recommended GC for deployments with large heaps (≥ 4 GB) or deep `maxLogEntries` ring buffers. Java 17 ships production-ready ZGC; for matcher-path latency this can reduce p99 pauses from tens or hundreds of milliseconds (G1 under sustained allocation) into single-digit milliseconds. ZGC is not the default because typical MockServer fixtures run small heaps where Parallel/G1 are fine and ZGC's fixed memory overhead hurts sub-2 GB scenarios. Includes container-memory headroom guidance (size container limit at ~1.5× heap when using ZGC). See the performance tuning page on the website.

#### HTTP/3, transparent proxy & infrastructure

- **HTTP/3 streaming / SSE responses** (`Http3ResponseWriter`): `StreamingBody` responses (Server-Sent Events, chunked proxy forwarding, LLM streaming) are now fully supported over HTTP/3. `Http3ResponseWriter` subscribes to the `StreamingBody`, sends HTTP/3 headers immediately, and forwards each chunk as an HTTP/3 DATA frame with backpressure via `StreamingBody.requestMore()`. The QUIC stream output is shut down on completion or error. Resolves the previous limitation where only static response bodies could be returned over HTTP/3. See `docs/code/http3.md`.
- **gRPC streaming over HTTP/3 — server-streaming and bidi-streaming** (completes the gRPC-over-HTTP/3 work). A `grpcStreamResponse` expectation now streams each message as its own HTTP/3 DATA frame (with per-message delays) followed by a trailing `grpc-status` HEADERS frame; `HttpActionHandler` routes the `GRPC_STREAM_RESPONSE` action to the new transport-neutral `GrpcStreamResponseWriter` seam (implemented by `Http3GrpcResponseWriter`) for HTTP/3, while HTTP/2 is unchanged. A `grpcBidiResponse` expectation now drives true bidirectional streaming over a single full-duplex QUIC stream via the new `Http3GrpcBidiStreamHandler` (gated by the existing `grpcBidiStreamingEnabled` flag, same two-phase peek-then-consume matching and `responseInProgress` lifecycle as the HTTP/2 path). Message encoding and rule matching are shared across transports via new `GrpcStreamMessageEncoder` / `GrpcBidiRuleMatcher` core helpers. Covered by native-QUIC integration tests (`Http3GrpcStreamingIntegrationTest`). With this, gRPC over HTTP/3 reaches full parity with HTTP/2 (unary, server-streaming, bidi-streaming). See `docs/code/http3.md`.
- **Bundled native QUIC** — the `netty-incubator-codec-http3` dependency pulls in `netty-incubator-codec-native-quic` classifiers for all five supported platforms (`linux-x86_64`, `linux-aarch_64`, `osx-x86_64`, `osx-aarch_64`, `windows-x86_64`) automatically; no separately downloaded BoringSSL library is required. An in-JVM Netty QUIC-client integration test verifies the full pipeline parity including streaming, gated on `Quic.isAvailable()` so the suite degrades gracefully where native QUIC is absent.
- **TPROXY (`IP_TRANSPARENT`) transparent-proxy strategy** — a new default-off `transparentProxyTproxy` configuration property (`-Dmockserver.transparentProxyTproxy=true` / `MOCKSERVER_TRANSPARENT_PROXY_TPROXY=true`) enables `IP_TRANSPARENT` socket binding so that, with iptables TPROXY rules, the kernel preserves the original destination as the listening socket's local address — which MockServer reads directly via `channel.localAddress()`, as an alternative to the existing conntrack `SO_ORIGINAL_DST` strategy (REDIRECT rules). Requires Linux, the `epoll` transport (NIO unsupported), and `CAP_NET_ADMIN`. The transparent proxy `enabled` flag (`transparentProxyEnabled`) is unchanged; the new property selects the kernel mechanism only. Verified end-to-end with a real Docker `NET_ADMIN` integration test for both `SO_ORIGINAL_DST` and TPROXY paths. eBPF sockmap-based redirection is deferred (placeholder added). See `docs/infrastructure/service-mesh.md`.
- **Testcontainers 1.21.4** — upgraded from 1.20.6, picking up docker-java 3.4.2 which fixes `DockerClientFactory.isDockerAvailable()` returning `false` on Docker Desktop 4.67 / Engine API 1.54 (the 3.4.1 `/info` probe sent the wrong Content-Type header and received HTTP 400, causing a false-negative result). No API or behaviour change for callers; tests that previously skipped on Docker Desktop 4.67+ now run correctly.

#### Clustered state (opt-in, `mockserver-state-infinispan`)

- Added a **`StateBackend` SPI** in `mockserver-core` (`org.mockserver.state.StateBackend`) — a pluggable interface that abstracts all shared MockServer state into three store types: a versioned `KeyValueStore<ExpectationEntry>` (expectations), a `KeyValueStore<String>` (scenario states), `KeyValueStore<ObjectNode>` (CRUD entities per namespace), and a `BlobStore` (persisted cassettes and fixtures). `InvalidationListener` callbacks allow clustered implementations to trigger node-local rebuilds when a remote write arrives. The default implementation is `InMemoryStateBackend`, which wraps the existing concurrent data structures — single-node behaviour and performance are completely unchanged.
- Added `mockserver-state-infinispan`, a new optional Maven module providing an embedded Infinispan `StateBackend` that can replicate MockServer expectations and scenario state across a JGroups cluster. Classpath-auto-discovered when `mockserver.stateBackend=infinispan` is configured (via `StateBackendFactory` reflection — `mockserver-core` has no compile-time dependency on Infinispan). Two modes: **LOCAL** (single-node, no JGroups, heap-only Infinispan cache, permissive serialization allow-list) and **CLUSTERED** (`clusterEnabled=true`, REPL_SYNC caches, JGroups transport, explicit serialization allow-list covering exactly the MockServer domain types). Expectations and scenario states use `REPL_SYNC` so all writes are synchronously replicated to every cluster member. An Infinispan `@Listener(clustered=true)` fires `InvalidationListener.onChanged()` on remote writes, triggering `RequestMatchers.reconcileFromBackend()` on the receiving node to rebuild its local `HttpRequestMatcher` cache. Approximate eviction (`maxCount`) on the expectations cache matches the `maxExpectations` configuration property. See `docs/code/clustered-state.md`.
- New configuration properties for state clustering:

  | Property | Env var | Default | Description |
  |----------|---------|---------|-------------|
  | `mockserver.stateBackend` | `MOCKSERVER_STATE_BACKEND` | `memory` | Backend type: `memory` or `infinispan` |
  | `mockserver.blobStoreType` | `MOCKSERVER_BLOB_STORE_TYPE` | `filesystem` | Blob store type: `filesystem` or `memory` |
  | `mockserver.clusterEnabled` | `MOCKSERVER_CLUSTER_ENABLED` | `false` | Enable JGroups cluster transport |
  | `mockserver.clusterName` | `MOCKSERVER_CLUSTER_NAME` | `mockserver-cluster` | JGroups cluster identifier |
  | `mockserver.clusterTransportConfig` | `MOCKSERVER_CLUSTER_TRANSPORT_CONFIG` | _(built-in loopback)_ | Path to a custom JGroups XML transport config |

  Setting `stateBackend=infinispan` without `clusterEnabled=true` starts Infinispan in LOCAL mode (single-node, functionally equivalent to the default in-memory backend but adds Infinispan on the classpath). A misconfigured `stateBackend=infinispan` where the module is absent fails fast with `IllegalStateException` rather than silently falling through to in-memory (which would cause split-brain). Scenario-state transitions are atomic cluster-wide (versioned compare-and-set), and shared `Times` counters (per-expectation match limits) are enforced cluster-wide via backend CAS (exactly-once across nodes). Remaining node-local aspects: the request/event log and `verify()` are per-node (verification queries a single node's log). See `docs/code/clustered-state.md`.

### Changed
- Upgraded the Prometheus metrics client (`io.prometheus:prometheus-metrics-core`, `-exposition-formats`, `-model`) from `1.6.1` to `1.7.0`. Source- and behaviour-compatible (metrics are emitted only when `metricsEnabled`); the metrics exposition format is unchanged. `io.netty:netty-tcnative-boringssl-static` is deliberately **not** bumped alongside it — tcnative is version-locked to Netty (its per-platform classifier artifacts arrive transitively at Netty's tcnative version, so an independent bump breaks Maven `dependencyConvergence`); it is now in the Dependabot ignore list and is upgraded manually in lockstep with the `netty.version` bump.
- `LlmChaosProfile` now validates its numeric fields in its `withX` builder methods, matching the validation `HttpChaosProfile` already enforces: `errorProbability` / `truncateAtFraction` must be in `[0.0, 1.0]`, `errorStatus` / `quotaErrorStatus` in `[100, 599]`, and `quotaLimit` / `quotaWindowMillis` ≥ 1. An out-of-range value now throws `IllegalArgumentException` with a clear message when a profile is built via the Java client or parsed from the `chaos` MCP parameter, instead of being silently accepted.
- Reworked the dashboard **Export** page: choose the scope (Active expectations / Recorded requests) with a radio and the file format with a dropdown, instead of one long combined list. Added **JAVA** (expectations), **log-entries** (requests) and **cURL** (requests) formats, filtered by the chosen scope, and the best-effort caveat is now shown only when it applies. Export is now the first Library tab. The **run comparison** tool moved out of Library into a new **Compare** tab under **Sessions** (where it belongs, since it diffs sessions).
- Upgraded the **chicory** WASM interpreter (`com.dylibso.chicory:runtime`) from `0.0.12` to `1.7.5`, moving off the old pre-1.0 release onto the stable 1.x line. `WasmRuntime` is migrated to the new API (`Parser.parse(bytes)` → `WasmModule`, `Instance.builder(module).build()`, and `ExportFunction.apply(long…)` returning `long[]`). The experimental WASM custom-rule feature's behaviour and module ABI (`match(i32 ptr, i32 len) -> i32`) are unchanged.
- Upgraded `com.networknt:json-schema-validator` from 1.5.9 to 3.0.3. The 3.x line uses the `tools.jackson` (Jackson 3.x) namespace internally and `snakeyaml-engine` for YAML schemas. MockServer's external Jackson usage stays on 2.22.0; the two Jackson namespaces coexist because they are in different Java packages. `JsonSchemaValidator` is rewritten against the new `Schema` / `SchemaRegistry` / `SpecificationVersion` API and uses the string-based `getSchema(String, InputFormat.JSON)` and `validate(String, InputFormat.JSON)` entry points to avoid passing Jackson 2.x `JsonNode` objects into Jackson 3.x APIs. `PathType.JSON_PATH` is configured so validation messages keep the existing `$.property` format and no test fixture had to change. The shaded uber-JAR adds two new relocations (`tools.jackson` and `org.snakeyaml`).
- BREAKING: minimum supported Java runtime raised from **Java 11** to **Java 17**. `mockserver/pom.xml` `maven.compiler.source` and `maven.compiler.target` are now `17`, so published artifacts are Java 17 bytecode and will not run on a Java 11 JVM. The CodeQL workflow, Buildkite build agent image, and local dev scripts have all been aligned to JDK 17.
- BREAKING: coordinated upgrade to the Jakarta EE 10 / Servlet 6 stack and the upstream dependencies that required it. The full `javax.*` → `jakarta.*` namespace migration (servlet, ws.rs, annotation, inject, persistence) is now complete. Library bumps: Spring Framework 5.3 → 7.0, Spring Boot 2.7 → 4.0, Tomcat embed 9 → 11, Jetty 9.4 → 12, Jersey 3.1 → 4 (`jersey-apache-connector` → `jersey-apache5-connector` with Apache HttpClient 5), `jakarta.xml.bind-api` 3 → 4, `jakarta.servlet-api` 4 → 6, `jakarta.ws.rs-api` 2.1 → 4, `jakarta.annotation-api` 1.3 → 3, JUnit Jupiter 5.14 → 6.1, json-unit 2 → 5, json-path 2 → 3, Netty 4.1 → 4.2.15.Final (introduced via `netty-bom` so the new `netty-codec-base` / `netty-codec-compression` / `netty-codec-http3` sub-modules stay aligned).
  - Runtime deployment in a servlet container now requires a Servlet 6 / Jakarta EE 10 host: Tomcat 11+, Jetty 12+, WildFly 32+, or equivalent. Servlet 5 / Jakarta EE 9 containers are no longer supported.
  - `MockServerServlet` and `ProxyServlet` runtime contract is unchanged for consumers using `jakarta.servlet.*`. Consumers still importing `javax.servlet.*` must update their imports.
  - WAR test scaffolding that configured TLS via the removed `Connector.setAttribute("keystoreFile"/"keystorePass"/…)` API must migrate to the Tomcat 11 `SSLHostConfig` + `SSLHostConfigCertificate` pattern. The four WAR/proxy-war integration test classes in this repo show the working shape.
  - Servlet 6 preserves RFC 6265 surrounding double quotes on cookie values returned by `Cookie.getValue()`. MockServer's request decoder now strips them so cookie semantics are unchanged for clients.
  - Spring 7 requires the `-parameters` javac flag for `@PathVariable` / `@RequestParam` name resolution; this is now enabled project-wide in `maven-compiler-plugin`.
  - Spring 7's `MappingJackson2HttpMessageConverter` is deprecated for removal in favour of `JacksonJsonHttpMessageConverter`. MockServer keeps Jackson at 2.22.0 for now because `swagger-parser` is still locked to Jackson 2; Jackson 3 upgrade will land once `swagger-parser` ships a Jackson 3 line (see #1970).
- BREAKING: Nashorn (`org.openjdk.nashorn:nashorn-core:15.7`) removed as a managed dependency. `JavaScriptTemplateEngine` now uses the GraalVM Polyglot API directly (`org.graalvm.polyglot.Context` with `HostAccess.ALL` + `allowHostClassLookup` for the existing class-deny-list security policy). GraalJS 25.x dropped the JSR-223 `javax.script` bridge, so the previous Nashorn-or-GraalJS-via-JSR-223 fallback would have silently returned a null engine and broken every JavaScript template at runtime. Downstream consumers that previously relied on Nashorn arriving transitively must add `org.openjdk.nashorn:nashorn-core` to their own dependencies, or migrate to GraalVM polyglot directly.
- Drop the `--add-exports=java.base/sun.security.{x509,util}=ALL-UNNAMED` javac flags inherited from the Java 11 era. Repo-wide audit found zero `sun.security.*` references after the Java 17 / jakarta migration, so the flags were dead weight.
- Performance: the request-matching hot path no longer builds the human-readable "did not match because…" diagnostic string (the per-field message assembly and per-field hint generation) when it would only be discarded — i.e. when the log level is below `INFO`. The match evaluation, the match-difference data behind `detailedMatchFailures` / debugMismatch / explainUnmatched / verification, and the match result are unchanged; only the discarded narrative is skipped, and the per-matcher `StringBuilder` is no longer allocated in that case. For a server with many registered expectations running below `INFO` under sustained load this measurably cuts per-request allocation and GC pressure (JMH `-prof gc`: ~36% less matching-path allocation at 1000 expectations and log level `WARN`; no change at the default `INFO`). See the performance documentation's note on `logLevel` and matching throughput. A new on-demand `mockserver-benchmark` JMH module (excluded from the default build) backs these numbers.

### Fixed
- **CPU no longer climbs as the request/event log fills (issue #2329).** `CircularConcurrentLinkedDeque` — the bounded ring used for the request/event log — checked capacity on every insert with `ConcurrentLinkedDeque.size()`, which is **O(n)** (it walks the whole list). Once the log reached `maxLogEntries` (default 100,000) each request paid an O(n) traversal per log entry, so CPU rose as the log filled and stayed high (and clearing *expectations* does not clear the *log*, so it never recovered). Size is now tracked in an `AtomicInteger`, making the eviction check and `size()` **O(1)**. Measured per-insert cost at the default capacity dropped from ~210µs to ~15ns (~14,000× at 100k entries; the old cost scaled linearly with `maxLogEntries`). No behaviour change — same bounded FIFO semantics and eviction callback. Tip for high-throughput users: also clear the log (`PUT /mockserver/clear?type=LOG` or `?type=ALL`, or `PUT /mockserver/reset`), not just expectations, or lower `maxLogEntries`.
- **Regex matching in the GraphQL, JSON-RPC and LLM-conversation matchers is now ReDoS-bounded.** User-supplied regular expressions for a GraphQL `operationName`, a JSON-RPC `method`, and an LLM conversation's `latestMessageMatches` are now evaluated under the shared `mockserver.regexMatchingTimeoutMillis` timeout via `MatchingTimeoutExecutor` — the same protection `RegexStringMatcher` already applies to path/header/body regexes — so a pathological pattern can no longer pin a worker thread (ReDoS). A timed-out evaluation is treated as a non-match. (Resolves CodeQL alert for `GraphQLMatcher`; the same fix is applied to the two sibling matchers.)
- Dashboard **Log Messages** panel: a non-breaking space is now rendered after each expandable JSON block, so the text that follows (e.g. `} matched expectation:`) no longer butts directly against the closing brace.
- **CORS for the dashboard served cross-origin.** When `mockserver.corsAllowOrigin` is blank (the default) MockServer now reflects the request's `Origin` in `Access-Control-Allow-Origin` instead of emitting an empty (invalid) header, and falls back to sensible `Access-Control-Allow-Methods` / `Access-Control-Allow-Headers` when those are blank (reflecting the requested headers on preflight). The MCP endpoint (`/mockserver/mcp`) now answers the CORS preflight and exposes `Mcp-Session-Id` via `Access-Control-Expose-Headers`. Together these let the dashboard (and any browser client) call the control-plane API and MCP endpoint from a different port or domain. An explicit `corsAllowOrigin` is still honoured as an allow-list, and `*` is never combined with `Access-Control-Allow-Credentials: true`.
- **CORS for the metrics endpoint (`/mockserver/metrics`).** The endpoint now adds the same `Access-Control-Allow-Origin` headers as the rest of the API, so the dashboard's Metrics view can fetch metrics when served cross-origin (e.g. the UI dev server on a different port). The disabled-state `404` carries the headers too, so the UI reads it cleanly and shows its "metrics disabled" guidance instead of a browser CORS fetch error.
- Helm chart downloads for older versions: every chart listed in `index.yaml` now returns a valid `.tgz` from `https://www.mock-server.com/`. Previously, releases that created a new versioned site could leave older chart archives missing from the live bucket while `index.yaml` still referenced them, so `helm pull` / `helm install` failed for any version other than the latest. The release pipeline now syncs the full set of charts on every run, making the bucket self-healing (fixes #2282).
- **`Content-Encoding` no longer leaks across requests on a reused (pooled) connection.** When a compressed request (e.g. `Content-Encoding: gzip`) was followed by an uncompressed request on the same keep-alive connection, the second request was incorrectly recorded with the first request's `Content-Encoding` header. The preserved-headers state is now reset per request, so each recorded request carries only its own encoding headers (fixes #2322).
- **Compressed request bodies now retain their original on-the-wire bytes.** When an HTTP/1.1 request arrives with a `Content-Encoding` (e.g. gzip), MockServer still decompresses it for matching/recording as before, but now also keeps the original compressed bytes alongside the decompressed body. A new `HttpRequest#getBodyAsOriginalRawBytes()` returns the exact bytes the client sent (the compressed payload when compressed, otherwise the decompressed bytes), so you can verify a client actually compressed its body; `getBodyAsRawBytes()` is unchanged (decompressed). A `BinaryBody` expectation now matches against **either** the decompressed body or the original compressed bytes, so a mixture of compressed and uncompressed requests matches automatically with no configuration. The original bytes are serialised (as `originalBody`) so they survive `retrieveRecordedRequests` and persistence (fixes #2326).
- **WASM custom-rule security controls are now enforced.** The `wasmEnabled` (default `false`) and `wasmMaxMemoryPages` (default `256`) configuration properties were documented as gating the experimental WASM custom-rule feature but were never actually read. WASM support is now disabled by default and fails closed: the WASM module control-plane endpoints (`PUT`/`GET`/`DELETE /mockserver/wasm/modules`) return `403` and `WasmBodyMatcher` does not match unless `mockserver.wasmEnabled=true`, and a loaded module's linear memory is now capped at `wasmMaxMemoryPages` via chicory `MemoryLimits` at instance creation. Set `wasmEnabled=true` to opt in.

### Removed
- Removed the **xDS route discovery** feature (REST endpoint `GET /mockserver/xds/routes`, gRPC RDS server, `xdsEnabled`/`xdsPort` configuration properties, and Helm `sidecar.xdsEnabled`/`sidecar.xdsPort` values). The feature shipped behind default-off flags and saw no adoption; real service mesh integration routes traffic to MockServer via an Istio VirtualService rather than having MockServer act as an RDS server. The **transparent proxy / sidecar mode** (`transparentProxyEnabled`, conntrack `SO_ORIGINAL_DST`, iptables init container) is fully retained.

## [6.1.0] - 2026-05-27

### Security
- SSRF protection for forward and forward-template actions: new `mockserver.forwardProxyBlockPrivateNetworks` property (default `false` for backwards compatibility) rejects forward targets that resolve to loopback, link-local, RFC 1918 private, or cloud metadata addresses (e.g. `169.254.169.254`). Enable in hardened or multi-tenant deployments where untrusted callers can register expectations. A future major release is expected to flip the default to `true`.
- ReDoS protection in regex matchers: regex evaluation now runs on a shared cached daemon-thread pool with a configurable timeout `mockserver.regexMatchingTimeoutMillis` (default `5000`ms). Patterns that exceed the budget are treated as non-matches and a WARN log entry is written, so a pathological pattern cannot wedge a Netty worker.
- XPath DoS protection: XPath evaluation in body matching now uses the same shared timeout executor with `mockserver.xpathMatchingTimeoutMillis` (default `5000`ms).
- Cryptographically secure randomness: `UUIDService` and `TemplateFunctions` now use `SecureRandom` instead of `java.util.Random` for UUID generation, `rand_int`/`rand_int_10`/`rand_int_100`, and `rand_bytes` template helpers.
- Loud insecure-mode warning logs at startup / SSL-context init: a WARN is emitted when (a) the forward proxy trusts all TLS certificates (`forwardProxyTLSX509CertificatesTrustManagerType=ANY`), (b) Velocity class loading is enabled (`velocityDisallowClassLoading=false`), (c) JavaScript templates have no class restrictions (`javascriptDisallowedClasses` empty), or (d) `tlsProtocols` includes the deprecated TLSv1 / TLSv1.1.
- `mockserver.tlsAllowInsecureProtocols` configuration property (default `true` for backwards compatibility): when set to `false`, any `TLSv1` or `TLSv1.1` entries in `mockserver.tlsProtocols` are filtered out before the SSL context is built, giving users an opt-in hardened TLS profile without having to rewrite their existing `tlsProtocols` value. A future major release is expected to flip this default to `false`.
- Secrets are no longer logged in plaintext: the startup property dump now redacts the values of properties whose name indicates a secret (password, secret, access key, API key, connection string, token, private key, credential, passphrase) as `***REDACTED***`. This covers the cloud blob credentials (`blobStoreSecretAccessKey`, `blobStoreConnectionString`), `llmApiKey`, `proxyAuthenticationPassword`, and similar, so they are not leaked to log aggregation.
- Kubernetes admission-webhook Helm hardening: fixed a shell-injection vector where the `webhook.tls.certValidityDays` value was interpolated unquoted into the self-signed-cert bootstrap Job (now quoted and integer-coerced); narrowed the TLS-bootstrap RBAC from cluster-wide Secret access to a namespace-scoped `Role` plus a `resourceNames`-restricted `ClusterRole` for the `MutatingWebhookConfiguration` caBundle patch only; and removed the running webhook's unused Kubernetes API RBAC (the webhook is a pure HTTPS server) in favour of `automountServiceAccountToken: false`.
- HTTP/3 CONNECT-UDP (MASQUE) open-relay risk documented: when `http3ConnectUdpEnabled=true` the relay forwards to any target the client names (SSRF-equivalent); it is default-off and now clearly flagged as test-only in the configuration and HTTP/3 documentation.

### Fixed
- HTTP/3 request bodies are now capped at `maxRequestBodySize` (default 10 MiB), matching the HTTP/1.1 and HTTP/2 paths; an over-cap HTTP/3 request is rejected (413 / QUIC stream shutdown) instead of being accumulated unboundedly in memory.
- Cloud BlobStore backends: cloud SDK clients (S3/GCS) are now closed on server shutdown (the `BlobStore` SPI is `AutoCloseable`, closed via the state backend) instead of leaking connection pools and threads; the Azure backend now encodes metadata keys reversibly so keys such as `x-custom-type` round-trip exactly and no longer collide with `x_custom_type` (previously both were silently mapped to the same key), and writes data + metadata atomically; the S3 and GCS `get()` paths no longer make a redundant second network call per read.
- Release pipeline now downloads the `mockserver-k8s-webhook` jar artifact before building its image, so the webhook image is published reliably on multi-agent CI.

### Added
- First-class LLM and agent mocking: new `httpLlmResponse` action type lets you mock LLM provider APIs at the semantic level — describe the model's reply (text, tool calls, stop reason, usage) and MockServer produces the byte-correct provider wire format. Supports all 7 major providers: Anthropic Messages, OpenAI Chat Completions, OpenAI Responses, Google Gemini, AWS Bedrock, Azure OpenAI, and Ollama. Non-streaming responses return provider-correct JSON; streaming responses generate the full SSE event sequence (e.g. `message_start` through `message_stop` for Anthropic, `chat.completion.chunk` with `finish_reason` for OpenAI) with configurable timing physics (`timeToFirstToken`, `tokensPerSecond`, `jitter`). OpenAI embeddings are also supported with deterministic vector generation via `deterministicFromInput()`.
- Conversation-aware matchers for multi-turn agent testing: `whenTurnIndex(n)`, `whenLatestMessageContains(text)`, `whenLatestMessageRole(role)`, and `whenContainsToolResultFor(toolName)` predicates match against the parsed `messages` array in the inbound request body, enabling scripted multi-turn conversations where turn 1 returns a `tool_use` and turn 2 (after the agent sends a `tool_result`) returns the final answer. All predicates compose with AND semantics and integrate with the scenario state machine for automatic turn advancement.
- Per-session conversation isolation via `isolateBy(header("x-session-id"))`, `isolateBy(queryParameter("agent"))`, or `isolateBy(cookie("sid"))`: each unique value of the configured attribute gets independent scenario state, so concurrent agents sharing the same mocked endpoint do not interfere. Missing attributes fall back to shared state gracefully.
- `mock_llm_completion` MCP tool: set up a single-turn LLM expectation from the MCP control plane, specifying provider, path, model, text, tool calls, and streaming mode
- `create_llm_conversation` MCP tool: build a multi-turn scenario-chained LLM conversation with optional per-session isolation from the MCP control plane; returns the generated scenario name and per-turn state values
- LLM Response badge in the dashboard expectation row showing provider, model, and text preview; Conversation view extended with a scripted-turns panel
- `mockserver.maxLlmConversationBodySize` configuration property (default 1 MiB; clamped to 16 KiB - 64 MiB; env var `MOCKSERVER_MAX_LLM_CONVERSATION_BODY_SIZE`): request bodies larger than this limit skip conversation-aware parsing and are treated as no-match, preventing DoS via oversized JSON payloads
- Custom json-unit matcher support for JSON body matching: implement `org.mockserver.matchers.CustomJsonUnitMatcherProvider` and point `mockserver.customJsonUnitMatchersClass` at it to register named Hamcrest matchers that JSON body expectations can reference via the `${json-unit.matches:name}` placeholder (e.g. `{ "price": "${json-unit.matches:largerThan}" }`); misconfigured providers are logged at WARN and ignored, so matching never fails because of an unloadable extension (fixes #2279)
- `http2Enabled` configuration property to disable HTTP/2: when set to false ALPN no longer advertises `h2` (and h2c is not detected) so HTTP/2 capable clients fall back to HTTP/1.1
- Agent-friendly mismatch diagnostics: `explain_unmatched_requests` MCP tool and `PUT /mockserver/explainUnmatched` REST endpoint return recent requests that matched no expectation, each with ranked closest-expectation diffs and actionable remediation hints (e.g., "use method POST not GET", "add missing header Authorization"); `debug_request_mismatch` results are now ranked by closeness and include remediation hints; new `mockserver://unmatched` MCP resource
- `create_expectations_from_recorded_traffic` MCP tool: converts traffic recorded by MockServer's forwarding/proxy mode into active mock expectations in one call, enabling an "observe then mock" workflow; supports `method`/`path` filtering and `preview` mode to inspect expectations before activating them
- OpenAPI contract verification MCP tools: `verify_traffic_against_openapi` validates recorded request-response pairs against an OpenAPI spec (passive conformance checking); `run_contract_test` sends example requests derived from an OpenAPI spec to a running service and validates the responses (active contract testing); both return structured per-operation pass/fail results with validation errors
- OpenAPI resiliency testing MCP tool: `run_resiliency_test` sends deliberately malformed and boundary-case requests derived from an OpenAPI spec to a running service (omitting required fields, type violations, numeric/string boundary violations, oversized strings, malformed JSON) and classifies each outcome as HANDLED (4xx) or UNEXPECTED (5xx/2xx/error); returns per-mutation results with operation summaries
- Deterministic LLM record/replay: `record_llm_fixtures` MCP tool snapshots LLM/MCP traffic recorded through MockServer's forwarding proxy into a committable JSON fixture file with secrets automatically redacted (Authorization, api-key, Cookie, etc.); SSE streaming responses (Anthropic, OpenAI, etc.) are converted to `HttpSseResponse` actions for faithful event-by-event replay; `load_expectations_from_file` MCP tool loads fixture files as active expectations for offline, deterministic, zero-cost test replay

### Changed
- **BREAKING** Inbound HTTP/1.1 and HTTP/2 request bodies are now capped at 10 MiB by default (`mockserver.maxRequestBodySize`). Previously unbounded. Requests larger than the limit are rejected with `413 Payload Too Large`. Raise the limit (e.g. `-Dmockserver.maxRequestBodySize=52428800`) if you intentionally mock large uploads.
- **BREAKING** Upstream response bodies received when MockServer is acting as a proxy or forwarder are now capped at 50 MiB by default (`mockserver.maxResponseBodySize`). Previously unbounded. Raise if you forward to services that legitimately return larger payloads.
- Each published JAR (including the `-no-dependencies` shaded artifacts) now declares a stable `Automatic-Module-Name` in its `MANIFEST.MF`, so downstream JPMS consumers can `requires` MockServer modules with names that no longer change with each version: `org.mockserver.core` (`mockserver-core`), `org.mockserver.client` (`mockserver-client-java`), `org.mockserver.netty` (`mockserver-netty`), `org.mockserver.test` (`mockserver-testing`), `org.mockserver.testing` (`mockserver-integration-testing`), `org.mockserver.junit.rule` (`mockserver-junit-rule`), `org.mockserver.junit.jupiter` (`mockserver-junit-jupiter`), `org.mockserver.springtest` (`mockserver-spring-test-listener`), `org.mockserver.examples` (`mockserver-examples`), `org.mockserver.maven` (`mockserver-maven-plugin`); each `*-no-dependencies` shaded variant shares its unshaded counterpart's module name and is an alternative packaging (place only one on the JPMS module path)

### Fixed
- Dynamic CA / SSL certificate generation no longer fails when `dynamicallyCreateCertificateAuthorityCertificate=true` (or any auto-generated server certificate path) is used: the four `Configuration` fluent setters for `certificateAuthorityCertificate`, `certificateAuthorityPrivateKey`, `privateKeyPath`, and `x509CertificatePath` no longer file-existence-check at set-time, because the internal generator sets these to the destination path before the file is written. User-supplied path typos are still surfaced by `CertificateConfigurationValidator` at TLS-init time.
- HTTP/2 requests through the HTTPS CONNECT forward proxy no longer hang and emit a GOAWAY after ~30s; the internal relay now negotiates HTTP/1.1 or HTTP/2 per connection via ALPN instead of mismatching its TLS layer and codec (fixes #2260)
- Docker image and standalone executable JAR produced no log output because the shaded server JAR did not include an SLF4J logging provider (fixes #2097)
- `*-no-dependencies` shaded artifacts leaked their un-shaded source module (and its transitive dependencies) onto consumers' classpaths; these artifacts are now truly dependency-free

## [6.0.0] - 2026-05-20

### Added

**Protocol & transport**
- gRPC protocol mocking without a grpc-java dependency: upload a Protobuf descriptor and mock unary, client-streaming, server-streaming, and bidirectional-streaming RPCs; `GrpcStreamResponse` supports multi-frame streaming responses
- GraphQL body matching: whitespace-normalised query comparison, `operationName` matching, and `variablesSchema` JSON Schema validation for variables
- binary request/response mocking via `BinaryRequestDefinition` and `BinaryResponse` for non-HTTP protocols
- DNS mocking with `dnsEnabled`/`dnsPort` configuration and support for A, AAAA, CNAME, MX, SRV, TXT, and PTR record types
- IPv6 CONNECT proxy support including correctly bracketed IPv6 address handling in the `CONNECT` tunnel

**Request matching**
- probabilistic expectation matching: set a `percentage` field (0–100) on an expectation so only a fraction of matching requests are served by it, enabling fault-injection scenarios (fixes #2122)
- HTTP method factory methods on `HttpRequest`: `HttpRequest.get(path)`, `.post(path)`, `.put(path)`, `.delete(path)`, `.patch(path)`, `.head(path)`, `.options(path)` for more concise expectation definitions (fixes #1509)

**Responses & actions**
- multi-response expectations: define an `httpResponses` list with a `responseMode` of `SEQUENTIAL` (cycle repeatedly through the list in order) or `RANDOM` (pick at random) to serve different responses on successive matched requests
- multi-action expectations: compose response, forward, and callback actions in a single expectation with a primary action and post-action callbacks
- stateful scenarios with atomic state transitions: gate expectations behind named states and advance through them by setting `newScenarioState` on the expectation, making it straightforward to model multi-step protocols
- CRUD simulation via `PUT /mockserver/crud`: supply a data model and MockServer auto-generates a fully stateful REST API (list, create, read, update, delete) backed by an in-memory store
- `FileBody` response body type that loads content from a file path at response time, useful for large or binary payloads (fixes #2163)
- in-memory file store: upload files via `PUT /mockserver/files/store`, retrieve via `PUT /mockserver/files/retrieve`, list via `PUT /mockserver/files/list`, and delete via `PUT /mockserver/files/delete`; stored files can be referenced by `FileBody` (fixes #1652)
- `respondBeforeBody` flag on the request matcher to dispatch the configured response (and optionally close the connection) before MockServer reads the request body, useful for reproducing client behaviour when a server responds and closes mid-upload (fixes #1831)

**Delays & timing**
- response delays with statistical distributions (uniform, Gaussian, log-normal) for realistic latency simulation (fixes #1688)
- global response delay via `mockserver.globalResponseDelayMillis` configuration property to add a baseline delay to every response
- connection timeout emulation via `mockserver.connectionDelayMillis` configuration property: a configurable delay before protocol detection fires, so slow-connect scenarios can be tested without a real network (fixes #1604)
- chunked dribble delay via `ConnectionOptions.withChunkSize()` / `withChunkDelay()` to drip-feed any response body in configurable-size chunks at a configurable rate

**Response templates**
- template helper functions: JWT generation, string manipulation, JSON path extraction, date arithmetic, and math operations available inside JavaScript, Velocity, and Mustache templates

**Record & replay**
- HAR 1.2 export: pass `format=HAR` to the retrieve API to get a standard HAR file of all recorded requests and responses (fixes #2175)
- automatic persistence of recorded expectations: `persistRecordedExpectations` and `persistedRecordedExpectationsPath` configuration properties save recorded traffic to disk so it survives restarts (fixes #2175)

**Debugging & diagnostics**
- per-expectation match count tracking: each expectation now exposes an invocation counter so tests can assert exactly how many times an endpoint was hit
- closest-match tracking: when a request does not match any expectation, MockServer identifies the expectation with the most fields satisfied and surfaces it via the API and dashboard
- `debugMismatch()` client method and `PUT /mockserver/debugMismatch` endpoint to programmatically retrieve the closest-match analysis for the last unmatched request
- match failure hints: actionable suggestions attached to `EXPECTATION_NOT_MATCHED` log events to guide correction of common mistakes
- "Why didn't this match?" debug dialog in the dashboard: click any unmatched request to see a field-by-field comparison against the closest expectation with per-field pass/fail indicators
- expectation ID included in `EXPECTATION_NOT_MATCHED` log messages to make it easier to correlate log output with the intended expectation (fixes #1937)

**Logging**
- compact log format: set `mockserver.compactLogFormat=true` to emit single-line JSON log entries instead of multi-line formatted output (fixes #1510)
- per-category log level overrides via `mockserver.logLevelOverrides` so individual event types can have different log levels (fixes #1694)
- correlation ID retrieval: `retrieveLogsByCorrelationId()` client method and a correlationId chip in the dashboard for tracing a single request across all related log events
- `retrieveLogEntries()` client method returning typed `LogEntry` objects with optional time-range filtering; pass `LOG_ENTRIES` as the format to the retrieve API for programmatic access
- custom log event listener via a `Consumer<LogEntry>` callback registered with the `Configuration` object, enabling integration with external observability tools (fixes #1960)

**Proxy & forwarding configuration**
- `mockserver.forwardDefaultHostHeader` configuration property: set a specific `Host` header value to send on all forwarded requests, overriding the original client `Host` header (fixes #1782)
- `mockserver.proxyRemoteHost` and `mockserver.proxyRemotePort` configuration properties to route all proxy traffic through an upstream proxy (fixes #1753)
- request forwarding timings captured per forwarded request: both connect time and total round-trip time are available in the log and dashboard (fixes #1574)

**OpenAPI**
- OpenAPI callback support: MockServer reads `callbacks` entries in an OpenAPI specification and automatically creates `AfterAction` webhook expectations (fixes #1483)

**TLS & security**
- BouncyCastle FIPS provider support for environments that require FIPS 140-2 compliant cryptography (fixes #1769)
- support for custom TLS protocols TLSv1.2 and TLSv1.3
- better error messages when MockServerClient fails due to TLS or networking errors

**Client & test integration**
- `@MockServerTest` now applies `mockserver.*` prefixed properties to the per-instance MockServer `Configuration` object, enabling declarative configuration of `initializationClass`, `logLevel`, `maxExpectations`, and other settings directly in the annotation (fixes #1554)
- Jackson `StreamReadConstraints` maximum string length raised to 100 MB to handle large JSON bodies without `StreamConstraintsException` (fixes #1754)

**Build & deployment**
- Maven plugin `initializationJson` now accepts glob patterns to load multiple expectation files from a directory (fixes #2231)
- `mockserver/mockserver:graaljs` Docker image tag that bundles the GraalJS engine JARs, enabling native ECMAScript 2022 support in response templates without Nashorn
- Docker HEALTHCHECK instruction added to all official images so container orchestrators can determine readiness without an external probe
- Helm chart `podLabels` value to attach arbitrary labels to MockServer pods, useful for service-mesh injection and internal routing rules (fixes #1884)

### Changed
- BREAKING: removed implicit reliance on internal java-certificate-classes (thanks to @Arkinator)
- BREAKING: the `classifier=shaded` form of `mockserver-client-java`, `mockserver-netty`, `mockserver-junit-jupiter`, `mockserver-junit-rule`, and `mockserver-spring-test-listener` is no longer published. Use the corresponding `*-no-dependencies` artifactId instead (e.g. depend on `mockserver-netty-no-dependencies` rather than `mockserver-netty` with `<classifier>shaded</classifier>`). The `*-no-dependencies` variants are now proper Maven modules and are the supported way to consume a shaded MockServer jar.

### Fixed

**Proxy & forwarding**
- proxy forwarding failures now return `502 Bad Gateway` instead of `404 Not Found`, making it clearer to clients that the upstream could not be reached (fixes #1519)
- `Host` header updated to match the forwarding target to prevent `421 Misdirected Request` errors from strict servers (fixes #1897)
- request/response bodies with `Content-Encoding` are now re-compressed correctly when forwarding, preventing garbled bodies on the upstream (fixes #1668)
- `Transfer-Encoding` header preserved on forwarded responses; spurious `Content-Length` header no longer added when `Transfer-Encoding` is present (fixes #1733)

**Request & response handling**
- cookie values starting with `!` were corrupted in forwarded responses (fixes #1875)
- duplicate query parameter values are now preserved instead of being deduplicated (fixes #1866)
- binary response bodies (e.g. `application/octet-stream; charset=utf-8`) were corrupted because a `charset` parameter in `Content-Type` caused the body to be treated as a string; now correctly treated as binary (fixes #1910)
- JSON body serialization preserved numeric precision — `0.00` was incorrectly serialized as `0.0` (fixes #1740)

**OpenAPI**
- `ByteArraySchema` (`string` format `byte`) properties were omitted from generated OpenAPI examples (fixes #1788)
- `$ref` inside OpenAPI example values was not resolved, leading to raw `$ref` strings in generated responses (fixes #1474)
- `allOf`/`anyOf`/`oneOf` composed schemas now generate merged example responses (fixes #1852)
- OAS 3.0 boolean `exclusiveMinimum`/`exclusiveMaximum` now correctly translated to JSON Schema Draft-07 numeric format (fixes #1896)
- OpenAPI 3.1 `types` array field now correctly preserved during schema serialization (fixes #1940)

**XML**
- XSD schemas with `xs:include` or `xs:import` using relative paths now resolve correctly (fixes #2118)

**JUnit & Spring integration**
- `@MockServerTest` field injection now works in `@Nested` JUnit 5 test classes (fixes #1979)
- double server start when `@MockServerSettings` (carrying `@ExtendWith`) is combined with explicit `MockServerExtension` registration is now prevented (fixes #1977)
- `clientCertificateChain`, `localAddress`, and `remoteAddress` fields on `HttpRequest` were serialized but not deserialized — both directions now work (fixes #1973)
- `MockServerClient` parameter injection now works with `@TestInstance(PER_CLASS)` where the test instance is created before `@BeforeAll` (fixes #1621)
- `ClassNotFoundException` for callback classes when running in a Spring Boot uber JAR (fixes #1571)

**Dashboard & WebSocket**
- dashboard WebSocket returned 404 when MockServer was running behind a reverse proxy with a path prefix (fixes #1693)
- HTTP/2 `CONNECT` proxy no longer hangs when the client advertises `h2` via ALPN (fixes #1933)
- WebSocket upgrade over HTTP/2 is now rejected cleanly instead of hanging the dashboard (fixes #1803)

**Concurrency & thread safety**
- `Times.remainingTimes()` made thread-safe with `AtomicInteger` to prevent race conditions under concurrent load (fixes #1834)
- `XmlStringMatcher` made thread-safe by creating a new `DiffBuilder` per match instead of sharing one (fixes #1796)
- Disruptor ring buffer is drained before `verify()` to prevent false-positive or false-negative results under high throughput (fixes #1757)
- expired TTL expectations are now filtered from the event bus and event bus subscribers are cleared after publish to prevent stale matches (fixes #1847, #1874)

**TLS & mTLS**
- mTLS (data-plane) enforcement moved from transport layer to application layer, fixing scenarios where client certificate validation was applied to non-mTLS connections (fixes #1766)

**Docker & deployment**
- `netty-tcnative` native libraries no longer bundled in the shaded JAR, preventing native library conflicts (fixes #1778)
- Helm chart sub-chart deployments generated conflicting Kubernetes resource names when chart name was omitted (fixes #1752)

**Glob & file initialization**
- glob brace expansion in `initializationJson` path failed to find the starting directory in some environments (fixes #1715)
- `WebSocket` channel leak when the `CircularHashMap` evicted the oldest callback client (fixes #1543)
- verify failure message incorrectly said "was not found" even when matching requests existed; message now accurately describes the mismatch (fixes #1789)

## [5.15.0] - 2023-01-11

### Added
- an image tag that allows container to run as root
- HTTP2 protocol support for mocking
- ability to proxy multiple binary messages without waiting for response 
- support to disallow loading of specific class in javascript templates 
- support to disallow specific text in javascript templates 
- support to disallow loading of any class in velocity templates
- support to disallow specific text in velocity templates
- support to disallow specific text in mustache templates
- support to velocity templates to load files via $import.read(...)

### Changed
- improved error message for not valid HTTP requests that are not being proxied
- improved error message when client doesn't trust MockServer's CA

### Fixed
- references to globally-scoped values within Ingress template
- fixed error passing configuration in MockServerClient
- fixed handling of additional content-type parameter and special characters in the content-type such as '+'
- removed invalid extra content-encoding header add when forwarding if content-encoding was not present

## [5.14.0] - 2022-08-22

### Added
- added support for json serialisation and de-serialisation java date time
- support for server urls in OpenAPI specification, by adding server url path as path prefix to operations
- improved documentation of clear functionality and type parameter and added examples
- local ip and port exposed to callbacks and log, useful when bound on multiple ports
- ability to match on content-encoding header
- added support for custom HTTP methods (via assumeAllRequestsAreHttp)

### Changed
- used helm release name in K8s resources to avoid conflicts for multiple deployments in same namespace (without extra values being set)
- tlsMutualAuthenticationCertificateChain is used if configured, even if tlsMutualAuthenticationRequired is false, so clients can choose correct certificate for optional client auth

### Fixed
- error matching header or parameters using array schema
- updated Ingress apiVersion in helm chart to non deprecated value
- removed the jdk14 slf4j bindings from the shaded and no-dependencies jars
- fixed NullPointerException and added more context information for match failures
- fixed NullPointerException during matcher logging
- fixed override logic for query and path parameters
- fixed verification of path parameters with multiple path parameter expectations
- fixed matching for array parameters using OpenAPI or a schema based parameter matcher
- resolved errors matching path by regex against expectations with path parameters
- resolved error with some deleted logs still appearing in the dashboard
- Content-Length is not added if a mock response set Transfer-Encoding

## [5.13.2] - 2022-04-05

### Fixed
- fixed artefact name in no-dependencies pom which caused issue with gradle builds
- added support for yml in addition to yaml for yaml files

## [5.13.1] - 2022-04-02

### Added
- simplified JSON format accepted for headers and other multi-value maps by allowing single values to be used as value list
- added warning message when content-length in expectation response is shorter than the body length
- improved log output for multimap failures, especially when using schema matcher (i.e. with OpenAPI) for parameters, headers, etc
- added support for endpoints examples in addition to existing schemas examples in an OpenAPI specifications

### Changed
- improved error messages from main method
- always serialise default fields for StringBody and JsonBody when retrieving recorded expectations for consistency even when the charset changes
- allow (and ignore) additional timestamp field for expectation JSON to support record request and responses to be submitted as JSON expectations
- upgraded JVM version in docker (and helm) to 17
- reduced memory footprint from log and simplified calculation of maximum log size
- use JVM trust store in addition to MockServer CA for MockServerClient to allow control plane requests to go via proxies or load balancers that terminate TLS

### Fixed
- allow callback which is nested inside initializer class for maven plugin initializer
- fixed HttpClassCallback static builder signature
- improved parsing of media type parameters to handle parameter values with equal symbol
- fixed serialising certificate chain to dashboard UI
- used absolute URI form for requests to an HTTP proxy as per [rfc2068 section 5.1.2](https://www.rfc-editor.org/rfc/rfc2068#section-5.1.2)
- removed content-length and other hop by hop response headers for forward actions
- fixed handling of headers and parameters specified without any values
- fixed logLevel in MockServer instance Configuration, so it now sets the SystemProperty read by the logging configuration
- fixed parallel execution of MockServerExtension to prevent port bind errors
- fixed error parsing body parameters containing '/'
- removed external references to schema specification to remove required network connectivity
- fixed docker latest tag by worked around bug in sonatype not updating the LATEST metadata for snapshots
- fixed partial deletion of expectations from watched file initialiser
- resolved small memory leak during proxy authentication
- updated verify by expectation id so it uses expectation match log events instead of the request matcher from the expectation

## [5.13.0] - 2022-03-17

### Added
- added support for configuring log level via properties file
- allow proactively initialisation of TLS so dynamic TLS CA key pair is created at start up
- added control plane authorisation using mTLS
- added control plane authorisation using JWT
- added support for control plane JWTs supplier to client
- added support for control plane JWT authorisation to specify required audience, matching claims and required claims
- added control plane authorisation using both JWT and mTLS
- added property to control maximum number of requests to return in verification failure, defaults to 10
- added field to verifications to control maximum number of requests to return in verification failure, defaults to configuration property - item above
- added remote address field to http requests that can be used by class or method callbacks
- exposed remote address (i.e. client address) to method and class callbacks, logs and dashboard
- exposed client certificate chain to method and class callbacks, logs and dashboard
- added simpler mustache style response templates (in addition to existing javascript and velocity support)
- added response template variables and functions for date, uuid, random, xPath and jsonPath for mustache
- added response template variables for date, uuid and random for velocity
- added response template variables for date, uuid and random for javascript
- added path parameters, remote address and client certificate chain to response template model
- added support for EMCAScript 6 in JavaScript response templates for Java versions between 9 and 15
- added support for numerous velocity tools for example for JSON and XML parsing to velocity response templates

### Changed
- included Bouncy Castle now used by default to resolve issues with modules in Java 16+ and backwards compatibility for Java 8
- improved configuration for dynamically creating CA so the directory is defaulted if not set and log output is clearer
- improved UI handling of match failures with a because section and more complex log events
- improved log configuration during startup when loading of properties file
- simplified support for multiline regex by allow . to match newlines
- improved regex matching by support Unicode (instead of US-ASCII) and native case-insensitive matching
- improved performance of negative matches by reducing the number of regex matches when not matching
- disabled privilege escalation in helm chart
- added setting of command line flags (i.e. serverPort) via system properties and properties file in addition to environment variables
- improved log output for command line flags, environment variables and system properties
- removed deprecated configuration properties for forward proxying
- changed docker distroless base image to distroless image for nonroot user
- changed docker distroless base image for snapshot to distroless image for debugging
- changed client to launch dashboard in HTTP (not HTTPS) to avoid issues with self-signed certificates
- simplified the body field for response template model
- improved XML matching by ignoring element order
- improved security by change CORS defaults to more secure values that prevent cross-site requests by default

### Fixed
- worked around JDK error 'flip()Ljava/nio/ByteBuffer; does not exist in class java.nio.ByteBuffer'
- null pointer exception when serialising string bodies with non string content types (i.e. image/png)
- disabled native TLS for netty to improve TLS resilience
- fixed handling of circular references in OpenAPI specifications to be as gracefully as possible

## [5.12.0] - 2022-02-12

### Added
- index.yaml to www.mock-server.com so it can be used as a helm chart repository
- command line flags can now be set as environment variables simplifying some container deployment scenarios
- glob support for initialisation files to allow multiple files to be specified
- request and response modifiers to dynamically update path, query parameters, headers, and cookies
- custom factory for key and certificates to provide more flexibility
- support for Open API expectations in json initialisation file
- improved @MockServerTest to support inheritance
- more flexibility over semicolon parsing for query parameters
- shaded jar for mockserver-netty and mockserver-client-java to reduce impact of dependency version mismatches with projects including these dependencies

### Changed
- ensured that TCP connections are closed immediately when shutting down to improved time before operating system frees the port
- reduce noise from Netty INFO logs that were not correct or misleading
- retrieveRecordedRequests now returns HttpRequest[]
- made it easier to set priority and id both in Java and Node clients in multiple places
- changed default charset for JSON and XML to UTF-8 from ISO 8859-1
- error handling for Open API so only single operation is skipped on failure not the entire file
- reduced over resolution of OpenAPI that triggered bugs in Swagger Parser V3 library
- replaces JDK FileWatcher with custom solution for watch file changes to work around multiple JDK bugs
- improved helm chart by supporting more configuration options
- remove explicit calls to System.gc()

### Fixed
- resolved multiple issues with clearing by expectation id
- resolved multiple issues with verifying by expectation id
- resolved multiple NullPointerExceptions in backend for UI
- ensure exact query parameter string is proxied allowing for empty values, leading `!` or or other special scenarios
- improved expectation updates from FileWatcher so only expectation from matching source are updated resolving multiple bugs
- ensured socket protocol of HTTPS is honoured resulting in forwarded requests using TLS
- fixed logging of exceptions such as port already bound at startup
- fixed retrieval of active exceptions where expectations were no longer active but not yet removed from expectations list
- no longer treat ndjson as json
- accessing UI via a reverse proxy or load balancer

## [5.11.2] - 2020-10-08

### Added
- clearing by expectation id
- verifying by expectation id

### Changed
- improved reliability and performance around stopping especially when stop is called multiple times for the same instance
- improved grouping of logs and stopped TRACE level logs from being grouped which caused inconsistency in the UI

### Fixed
- fixed recursive loop on stopAsync for ClientAndServer
- header matching for subsets to ensure notted header keys don't exist

## [5.11.1] - 2020-07-22

### Added
- port is now printed at start of each log line
- shutdown log message specifying port
- UI updated prior to stopping MockServer to ensure all pending log messages are sent over UI web socket
- added listener for expectation modifications that can be used with ExpectationInitializer for custom expectation persistence

### Changed
- performance improvements of expectation sorting and comparisons
- reduced creation of objects at WARN log level
- ensured all threads are daemon threads (except port binding thread)
- simplified and improve performance of matching for headers, query string parameters, path parameters, cookies and body parameters
- only mark log events as deleted for log level of TRACE, DEBUG, or INFO so log can be view in UI
- improved performance of handling large OpenAPI specifications
- improved error message format for errors when loading OpenAPI specifications
- changed name of `optionalString` static factory method to `optional` to improve consistency with `not`

### Fixed
- fixed field name error when serializing ParameterBody
- error when log level DEBUG cleared log events were returned from the API

## [5.11.0] - 2020-07-08

### Added
- added basic support to proxy binary requests that are not HTTP
- dynamic maximum log events and maximum expectations based on available memory
- added ability to switch between BouncyCastle and vanilla JDK for key and certificate generation
- added support for TLS over SOCKS4 or SOCKS5
- request matching and expectations using OpenAPI or Swagger specification
- create expectation using OpenAPI or Swagger specification with automatic example responses
- verifications of requests or request sequences using OpenAPI or Swagger specification
- clear log, clear expectations, retrieve logs and retrieve requests using OpenAPI or Swagger specification
- json schema matchers for method, path, headers, query string parameters and cookies
- path variables matched by nottable string, regex or json schema (as per query string parameters)
- support for optional query parameters, header and cookies
- support for nullable keyword in JSON Schemas (part of Open API specification not JSON Schema specification)
- matching xml bodies against JSON Schema matchers
- matching parameter bodies against JSON Schema matchers
- support to match path parameters, query parameters and header either by sub set or by matching key
- grouping of log events in UI to simplify analysis of expectation matches / non matches for a request
- added extra log messages to indicate progress for large json expectation initializers
- added log messages for invalid control plane request to make control plane errors clearer in the UI
- added support for easily mapping jar and config into the docker container
- added support for easily mapping jar and config into the helm chart

### Changed
- reduced time range of CA certificates to increase likelihood they will be accepted by strict systems (i.e. VMWare vCenter Server)
- improved error message when exception loading or reading certificates or keys (i.e. file not found)
- certificate and private key are saved to directoryToSaveDynamicSSLCertificate when preventCertificateDynamicUpdate is enabled
- returns created expectations from /mockserver/expectation so that it is possible to view the id for new (or updated) expectations
- added ability to inherit @MockServerSettings for Junit5 tests
- switched to distroless container base for security and size
- added explicit gc suggestion after reset and clear
- upgraded docker container to Java 11 to ensure JVM honours container memory constraints (i.e. inside kubernetes)
- improved parsing of invalid content-type header parameters by handling error gracefully and outputting a clear error message
- improved performance through multiple minor tweaks around handling of expectations
- added version to log output to improve resolution of github issues with logs attached
- improved logic around proxies to make HTTP CONNECT, SOCKS4 and SOCKS5 more reliable and faster
- reduced object creation (and therefore GCs) for log especially during request matching
- print logs timestamp with milliseconds
- reduced expiry of certification to one year to avoid errors from modern systems that don't like long lived certificates (such as Chrome or VMWare)
- defaulted charset for XML and JSON to UTF8 as per rfc3470 and rfc8259
- version matching logic for client now only matches on major and minor version and not bug fix version
- improved handling of body matching for control plane to clearly separate control plane and data plan matching
- simplified and improved stability for UI by moving all data processing into back-end and other simplifications

### Fixed
- fixed but with environment variable configuration for long, integer and integer list values
- removed call to ReflectionToStringBuilder.setDefaultStyle to avoid impacting toString globally for JVM
- fixed destination port and ip in Socks5CommandResponse which prevented SOCKS5 proxied connections
- fixed Subject Alternative Names with wildcards or other valid DNS name formats not supported by certain versions of the JDK (<= 1.8)
- fixed json body responses by returning blank or null fields, objects and arrays
- fixed generics for withCallbackClass to allow ExpectationResponseCallback to be specified as a Class (not only a string)

## [5.10.0] - 2020-03-24

### Added
- closure / object callbacks uses local method invocation (instead of Web Socket) when both the client in same JVM (i.e. ClientAndServer, JUnit Rule, etc)
- support to specify a fixed TLS X509 Certificate and Private Key for inbound TLS connections (HTTPS or SOCKS)
- ability to prioritise expectations such that the matching happens according to the specified priority (highest first) then creation order
- ability to create or update (if id matches) expectations from the client using upsert method
- ability to return chunked responses where each chunk is a specific size by using response connection options
- support for XmlUnit placeholders https://github.com/xmlunit/user-guide/wiki/Placeholders
- added ability to control (via configuration) whether matches fail fast or show all mismatching fields
- configuration to disable automatically attempted proxying of request that don't match an expectation and look like they should be proxied

### Changed
- improved X509 certificates by adding Subject Key Identifier and Authority Key Identifier
- stopped delay being applied twice on response actions (#721)
- improve support for clients making initial SOCKS or HTTP CONNECT requests over TLS
- replaced JSONAssert with JsonUnit to improve JSON matching and remove problematic transitive dependencies
- added more detail of cause of match failure

### Fixed
- fixed null point for expectation initialiser with file watcher in working directory specified with relative path
- fixed error resulting in enum not found exception for log events
- fixed error with parsing of json arrays for expectation responses with json body as json object not escaped string
- fixed meaning of disableSystemOut property so that only system out is disabled not all logging
- fixed key store type in key store factory to avoid issue with the JVM changing the defaults

## [5.9.0] - 2020-02-01

### Added
- added stopAsync method to ClientAndServer to allow stop without waiting
- log events for UPDATED_EXPECTATION and REMOVED_EXPECTATION
- ability to update existing expectation by id
- hot re-loading of expectation initialiser file
- addition configuration for web socket client event loop size
- addition configuration for action handler thread pool size
- exposed request raw bytes to object callbacks (allows forwarded requests body parsing that is inconsistent with Content-Type header)
- added support to delay socket closure using connection options
- added support to control trusted certificate authorities (trust store) for proxied & forwarded requests
- added support for two-way TLS (mTLS), also called client authentication
- now sends TLS X509 certificate from proxy (i.e. support forward client authentication / mTLS)
- added ability to dynamically create local unique Certificate Authority (CA) X.509 and Private Key to improve security of clients trusting the CA

### Changed
- performance improvements for header and cookie handling
- improved JSON validation errors by adding link to OpenAPI Specification
- removed duplicate packages between modules to prepare for java modules
- caught Jackson configuration exception to improve resilience with other Jackson versions in classpath
- moved Junit4 to separate module to reduce size of jar-with-dependencies, simplify code and increase build speed
- enabled case insensitive matching for regex matches
- improved documentation (i.e. on website)
- switched from Bouncy Castle to JDK for certificate and private key generation

### Fixed
- fixed error where ClientAndServer does fully wait for client to stop
- fixed ability to specific a log level of OFF
- fixed bug with keystore type configuration not being used in all places
- added file locking and jvm locking for expectation persistence file to avoid file corruption
- fixed verification incorrectly matching verifier non-empty bodies against empty request bodies
- stopped response callbacks for proxied requests blocking threads
- fixed bug that caused JSON bodies in specified expectations as raw JSON to ignore empty arrays and empty strings

### Security
- updated tomcat (used in integration tests) to version without vulnerabilities

## [5.8.1] - 2019-12-23

### Added
- changelog
- added configuration for all CORS headers
- added support for forward proxy authentication (via configuration)
- added support for overriding forward responses by class or closure
- requests sent to MockServerClient can be updated / enhanced i.e. to support proxies
- dynamic creation of a unique (i.e. local) Certificate Authority X509 Certificate and Private Key instead of using the fixed Certificate Authority X509 Certificate and Private Key in the git repo.
- configuration to require mTLS (also called client authentication or two-way TLS) for all TLS connections / HTTPS requests to MockServer
- configuration of trust store and client X.509 used during forwarded and proxied requests to endpoints requiring mTLS
- extended TLS documentation significantly

### Changed
- reduced default number of fail handles used by nio event loop
- improved performance and scalability of logging ring buffer
- improved performance of json serialisation
- deprecated isRunning and replaced with hasStopped and hasStarted to make behaviour more explicit and faster
- improved, simplified and unified handling of Content-Type for bodies
- remove closure callback clients and connections for expectation that no longer exist
- ensure WebSockets for closure callback auto re-connect for unreliable networks
- simplified XML and JSON of bodies in the log and UI
- improved logging for CORS
- added support for TLS with closure / WebSocket callbacks
- simplified handling of TLS and HTTP CONNECT (which is always TLS)
- improved JSON format for expectation to support objects instead of escaped strings

### Fixed
- fixed reading logLevel from system property or environment variable
- ensure all errors are printed to console
- removed TLSv1.3 to avoid any issues with JVM version that do not support TLSv1.3
- handle proxying requests without Content-Length header
- added support for JSON array for raw JSON in requests or responses body

### Security
- updated jetty (used in code examples) to version without vulnerabilities

## [5.8.0] - 2019-12-01

### Added
- added support for configuration via environment variables
- added support for overriding responses which an forward overridden request
- added persistence of expectations to file (as json)

### Changed
- ensured all Netty threads are marked as daemon to ensure MockServer does not prevent / delay JVM shutdown
- improved docker-compose example
- improved helm document & example to show how to provide configuration file or expectation initialiser
- improved performance and throttled load for UI

### Fixed
- WARN and ERROR is logged even if logLevel not yet initialised
- ensured exceptions thrown in Main method are always logged
- separated control plane and data plane matching to avoid reverse regex matches and other similar strange behaviour
- fixed handling of multiple parameters in Content-Type header
- autodetect WS or WSS for UI update WebSocket depending on HTTP or HTTPS
- stopped usage being printed multiple time under certain error scenarios

### Removed
- removed reentrant WebSocket prevention by creating WebSocket client per expectation to improve resilience

## [5.7.2] - 2019-11-16

### Added
- added setting to control maximum size of event log

### Changed
- performance enhancements
- improved matcher failure log messages to output detail at DEBUG level
- made log level configuration more resilient
- allowed exceptions to be thrown from all types of callback methods

### Fixed
- fixed duplicate logging or request when optimistic proxying
- added missing exception on bind error
- ensured client event bus is not static so it not shared across multiple client instances except were server port is identical

## [5.7.1] - 2019-11-09

### Added
- added disruptor ring buffer in front of log to improve performance
- added configuration to ensure MockServer certificate is not updated once created

### Changed
- improved performance with request matcher fast failure
- refactored CPU or memory hot spots
- switched logging to simpler more resilient approach without external dependencies

### Fixed
- fixed log levels to support disabling the log completely without impacting verifications
- ensured clear, reset and verify guarantee all pending log events are completed
- ensured all thread pools (i.e. added disruptor, etc) are stopped with stopping MockServer or Servlets
- respond with not found response (instead of hanging) when failure during template rendering

## [5.7.0] - 2019-11-01

### Added
- added support for retrieving requests and associated responses from log
- added support for access-control-request-headers with CORS

### Changed
- updated to Java 8
- made Jackson more relaxed when parsing JSON already validated by JSON Schema
- improved resilience of request and response parsing, such as when Content-Type is blank string
- improved proxy loop prevention to only break loops within a single instance of MockServer
- increased length of TLS keys to RSA 2048
- increased default request log size and maximum number of expectation

### Fixed
- added global thread-safety to javascript templates for local variables defined without keyword var

## [5.6.1] - 2019-07-21

### Changed
- delayed creation of Nashorn JS engine

### Fixed
- fixed multi-threaded handling of javascript templates
- fixed duplicate logging errors

## [5.6.0] - 2019-06-21

### Added
- added delay to actions that did not already have it
- added configuration for certificate authority private key and x509
- added support for large HTTP headers

### Changed
- simplified the certificate generation
- configured logback file appender programmatically

### Fixed
- ensure port binding exception are thrown and MockServer stops if port already allocated
- fixed log configuration to ensure no class loading exception thrown
- fixed control plane matching of expectations with notted entries




