# Changelog
All notable and significant changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- A mock LLM completion can now declare an **`outputSchema`** (a JSON Schema) that its response `text` is expected to conform to. As the response is encoded, MockServer validates the configured text against the schema and, on a mismatch, **fail-soft**: the response body is returned exactly as configured but an `x-mockserver-structured-output-invalid` diagnostic header is added and a warning logged — so a malformed structured-output fixture is surfaced immediately while a deliberately non-conforming fixture still returns unchanged. A blank schema, absent text, or a malformed schema are all treated as "nothing to check" and never affect the response. Exposed on the Java `Completion.withOutputSchema(...)`, the `outputSchema` field in expectation JSON, and the `mock_llm_completion` MCP tool (string or inline object). Complements the read-side `verify_structured_output` tool. See the AI/MCP tools page and `docs/code/llm-mocking.md`.
- Added a **`verify_structured_output` MCP tool**: validate that the structured (JSON) output of recorded LLM responses conforms to a JSON Schema. It decodes each recorded response for a given provider (via the runtime-LLM client SPI), extracts the assistant's output text, and checks it against the schema — so you can assert that an agent (or a mocked model) produced schema-valid structured output. Read-only and deterministic; responses with no text output are reported separately as skipped, and the result gives per-response conformance with validation errors. See the AI/MCP tools page and `docs/code/llm-mocking.md`.
- Added a **Metrics view** to the dashboard UI: a new top-bar tab that polls MockServer's Prometheus endpoint (`GET /mockserver/metrics`) and renders live activity — request / matched / not-matched / forwarded counts with inline sparklines, a derived requests-per-second throughput chart, and a per-action breakdown — plus the served MockServer version. It is self-contained (no extra container, and no charting dependency — inline SVG) and degrades gracefully: when MockServer is started without `metricsEnabled` the endpoint returns 404 and the view shows guidance to enable it (`-Dmockserver.metricsEnabled=true` / `MOCKSERVER_METRICS_ENABLED=true`). See `docs/code/dashboard-ui.md`.
- Recorded requests can now be exported as **cURL commands**. A new `CURL` value for the `/mockserver/retrieve` `format` parameter (valid for `type=REQUESTS` and `type=REQUEST_RESPONSES`) renders one `curl` command per recorded request via the existing `HttpRequestToCurlSerializer`; the expectation scopes return a clear "not supported" message. Surfaced in the dashboard Export page. See the configuration/retrieve docs.
- Added declarative **HTTP chaos/fault injection** (`HttpChaosProfile`) for resilience testing, attachable to any expectation via a top-level `chaos` block. Supports probabilistic error-status injection (e.g. 500, 503, 429 with an optional `Retry-After` header) and latency injection. Works on **both mocked responses** (RESPONSE, RESPONSE_TEMPLATE, RESPONSE_CLASS_CALLBACK) **and forwarded/proxied responses** (FORWARD, FORWARD_TEMPLATE, FORWARD_CLASS_CALLBACK, FORWARD_REPLACE, FORWARD_VALIDATE), making MockServer usable as a chaos proxy for testing how applications handle unreliable upstream dependencies. Deterministic at `errorProbability` 0.0/1.0; reproducible at fractional probabilities via a `seed`. Exposed in the Java client (`ForwardChainExpectation.withChaos()`), REST API, and expectation JSON. See the new [Chaos Testing & Fault Injection](/mock_server/chaos_testing.html) documentation page.
- Added an **MCP server conformance tester** (`run_mcp_contract_test` MCP tool): point it at a target MCP (Model Context Protocol) server's Streamable HTTP endpoint and it runs the required JSON-RPC handshake and core methods — `initialize`, `notifications/initialized`, `ping`, `tools/list`, and unknown-method rejection (expects error code `-32601`) — validating the **shape** of each response (JSON-RPC 2.0 envelope and required result fields), never the semantics of any tool. Optionally exercises one `tools/call` (skipped by default, since a call may have side effects on the target). Fully deterministic and offline-from-LLMs (no model is involved); each request has a 10-second timeout. Backed by a network-free, unit-testable `McpContractTest` orchestrator with an injected transport. See the AI/MCP tools page and `docs/code/llm-mocking.md`.
- Added a **prompt-injection / adversarial-response harness** for testing agent resilience. A new `mock_adversarial_llm_response` MCP tool returns a curated adversarial payload as the mock LLM response — prompt-injection ("ignore previous instructions…"), jailbreak persona-swaps, data-exfiltration requests, malformed/truncated JSON, an empty response, and an over-long repetition — so you can verify your agent *resists* hostile or malformed model/tool output. Backed by `AdversarialResponseLibrary` (deterministic; the payloads are benign test fixtures, not working exploits). A defensive testing aid. See the AI/MCP tools page and `docs/code/llm-mocking.md`.
- Added opt-in, **exploratory semantic prompt matching** for LLM conversations: a `semanticMatch` turn predicate (the intent the latest message should express) judged by a runtime LLM via the client SPI. It is **off by default and never on the assertion path** — the predicate is ignored unless `mockserver.llmSemanticMatchingEnabled` is set *and* a runtime backend resolves, so deterministic matching is never affected by default. Non-deterministic by nature (a live LLM judge), so it is documented for exploration only, never for CI assertions; fails closed (a non-affirmative/empty/errored judge does not match). Exposed in the Java `TurnBuilder.whenSemanticMatch`, the `create_llm_conversation` MCP tool, and the dashboard wizard (clearly flagged exploratory). See `docs/code/llm-mocking.md`.
- Added a **correlated agent-run call graph**. `AgentRunAnalyzer.buildCallGraph` reconstructs a recorded run as a graph — a node per message and per assistant tool call, with `NEXT` (sequence), `INVOKES` (turn→tool call), and `RESULT` (tool call→its result, correlated by tool-call id) edges — exposed in the `explain_agent_run` MCP result as a `callGraph` field. The dashboard **Sessions** view renders it per session (a "Call graph" button loads it via `explain_agent_run`): each step shows the message role and the tool calls it made, with a result indicator, plus a copyable Mermaid `flowchart` source. Deterministic and read-only. See `docs/code/llm-mocking.md`.
- Added optional **OpenTelemetry (OTLP) export**, in two independent, off-by-default parts. (1) **Metrics export** — MockServer's existing metrics (the same explicitly-defined gauges already exposed for Prometheus: `REQUESTS_RECEIVED_COUNT`, `RESPONSE_EXPECTATIONS_MATCHED_COUNT`, the LLM/SSE/chaos counters, etc.) can also be pushed to an OTLP collector as an alternative to Prometheus (`mockserver.otelMetricsEnabled`). Implemented as OTel observable gauges reading the current values, so the Prometheus and OTLP views stay in lock-step. (2) **GenAI span export** — MockServer emits one explicit OpenTelemetry GenAI semantic-convention span per LLM completion it serves (`gen_ai.system`, `gen_ai.request.model`, `gen_ai.usage.input_tokens`/`output_tokens`, `gen_ai.response.finish_reasons`, tool-call count) (`mockserver.otelTracesEnabled`). These are spans MockServer codes deliberately — **no auto-instrumentation** is added. Both use the OTLP HTTP/protobuf exporter with the JDK HttpClient sender (no gRPC/OkHttp), share `mockserver.otelEndpoint`, and are fail-soft (a setup error logs one line and never stops the server or affects a response). `io.opentelemetry.*` is relocated in the shaded JAR. See the configuration properties page.
- Added **drift detection** for LLM fixtures (`detect_llm_drift` MCP tool): replays a recorded cassette's exchanges against the live provider (via the runtime-LLM client SPI) and reports **structural** drift — new/removed fields and type changes in the responses — not semantic differences, so benign wording changes never flag. Built on a reusable, pure `StructuralShapeDiff` and a `DriftDetector` that **fails closed** per exchange (a network error or non-2xx live response is reported as could-not-check, never as drift, never thrown). Off unless a runtime backend is configured. Intended for an opt-in/scheduled CI lane (real API keys + tokens), never the per-commit build. See the AI/MCP tools page and `docs/code/llm-mocking.md`.
- Completed the **VCR (record/replay) toolkit** for LLM fixtures with three additions. (1) **Strict mode** — `load_expectations_from_file` accepts `strict` (or set `mockserver.llmVcrStrict`), which registers a low-priority catch-all per cassette path so a request matching no recorded fixture returns HTTP 599 instead of silently falling through. (2) **Body-field redaction** — `record_llm_fixtures` accepts `redactBodyFields` (or set `mockserver.fixtureBodyRedactFields`) to redact named JSON fields from recorded request/response bodies, complementing the existing header redaction. (3) **Replay field normalisation** — `load_expectations_from_file` accepts `normalizeRequestBodyFields` to drop volatile JSON fields from each recorded request body and match the remainder loosely (ignoring extra fields), so per-run values (request ids, timestamps) do not block replay. These are operational settings exposed via config and MCP. See the AI/MCP tools and configuration properties pages.
- Added declarative **LLM fault/chaos profiles** for resilience testing, attachable to any mock LLM response (`mock_llm_completion`, each `create_llm_conversation` turn, the Java `LlmConversationBuilder`, and raw expectation JSON via a `chaos` block). Supports probabilistic provider errors (e.g. 429/529 with a `Retry-After` header), mid-stream truncation of an SSE stream (keep a leading fraction of events), and appending a malformed (broken-JSON) SSE chunk. Errors are deterministic at probability 0.0/1.0 and reproducible at fractional probabilities via a `seed`; truncation and malformed-SSE are always deterministic. A new `LLM_CHAOS_INJECTED_COUNT` metric tracks injections. The dashboard conversation wizard exposes the profile per turn. See the AI/MCP tools page and `docs/code/llm-mocking.md`.
- Added two MCP tools for **agent-run analysis and tool-call assertions**, both backed by a new deterministic `org.mockserver.llm.analysis.AgentRunAnalyzer` that reconstructs an agent run by decoding the LLM requests MockServer recorded. `verify_tool_call` asserts that an agent called a named tool a given number of times (`atLeast`/`atMost`, with an optional regex over the tool-call arguments); `explain_agent_run` summarises the run's structure (message and assistant-turn counts, the ordered tool-call sequence, tool results, and the latest message role). Read-only and offline — no LLM call. See the AI/MCP tools page and `docs/code/llm-mocking.md`.
- Added a **runtime-LLM client SPI** (`org.mockserver.llm.client`) that lets MockServer call a real LLM you already run, as the foundation for opt-in features such as drift detection and exploratory semantic matching. Mirrors the existing codec registry: an `LlmClient` per provider (Ollama, OpenAI, OpenAI Responses, Azure OpenAI, Anthropic, Gemini, Bedrock) registered in `LlmClientRegistry`, an immutable `LlmBackend` config (with the API key redacted in logs), and a three-layer `LlmBackendResolver` (provider env vars → `mockserver.llmProvider`/`llmApiKey`/`llmModel`/`llmBaseUrl` → named-backends JSON via `mockserver.llmBackendsConfig`). All runtime-LLM use goes through `LlmCompletionService`, which is **off unless a backend is configured**, **fails closed** on any timeout/error/non-2xx (never flipping a deterministic result), and caches per normalised prompt for reproducibility. Ollama is the reference backend (no key, local); Bedrock builds the Anthropic-on-Bedrock request and relies on the `headers` escape hatch pending automatic SigV4 signing. See the configuration properties page and `docs/code/llm-mocking.md`.
- LLM conversation mocks can now opt into deterministic **prompt normalisation** before the `latestMessageContains` / `latestMessageMatches` predicates are evaluated, so a match is not blocked by cosmetic differences in dynamically-assembled agent prompts. A new `normalization` block on `conversationPredicates` (also exposed per-turn in the `create_llm_conversation` MCP tool and the dashboard conversation wizard) supports collapsing whitespace, lowercasing, sorting JSON object keys, dropping built-in volatile values (ISO-8601 timestamps, UUIDs, `req_`/`msg_`/`call_` ids), and dropping named JSON fields. Normalisation is pure and idempotent — it never makes a test flaky — and has no effect unless a text predicate is set. See the AI/MCP tools page and `docs/code/llm-mocking.md`.
- DataFaker (`net.datafaker:datafaker:2.5.4`) is now bundled as a template helper. A single shared `Faker` instance is exposed as `faker` in all three response-template engines (Velocity, Mustache, JavaScript) via `TemplateFunctions.BUILT_IN_HELPERS`, giving templates access to 250+ realistic-fake-data providers (`faker.name().firstName()`, `faker.internet().emailAddress()`, `faker.address().city()`, etc.). The instance is thread-safe and produces fresh random values on each call. See the consumer docs (response templates page) for the full provider list and per-engine syntax. Java 17 unlocked this — DataFaker 2.x requires Java 17; the previous Java 11 floor pinned us to the abandoned 1.9.0 line.
- Documented ZGC (`-XX:+UseZGC`) as a recommended GC for deployments with large heaps (≥ 4 GB) or deep `maxLogEntries` ring buffers. Java 17 ships production-ready ZGC; for matcher-path latency this can reduce p99 pauses from tens or hundreds of milliseconds (G1 under sustained allocation) into single-digit milliseconds. ZGC is not the default because typical MockServer fixtures run small heaps where Parallel/G1 are fine and ZGC's fixed memory overhead hurts sub-2 GB scenarios. Includes container-memory headroom guidance (size container limit at ~1.5× heap when using ZGC). See the performance tuning page on the website.

### Changed
- Reworked the dashboard **Export** page: choose the scope (Active expectations / Recorded requests) with a radio and the file format with a dropdown, instead of one long combined list. Added **JAVA** (expectations), **log-entries** (requests) and **cURL** (requests) formats, filtered by the chosen scope, and the best-effort caveat is now shown only when it applies. Export is now the first Library tab. The **run comparison** tool moved out of Library into a new **Compare** tab under **Sessions** (where it belongs, since it diffs sessions).
- Upgraded `com.networknt:json-schema-validator` from 1.5.9 to 3.0.3. The 3.x line uses the `tools.jackson` (Jackson 3.x) namespace internally and `snakeyaml-engine` for YAML schemas. MockServer's external Jackson usage stays on 2.21.3; the two Jackson namespaces coexist because they are in different Java packages. `JsonSchemaValidator` is rewritten against the new `Schema` / `SchemaRegistry` / `SpecificationVersion` API and uses the string-based `getSchema(String, InputFormat.JSON)` and `validate(String, InputFormat.JSON)` entry points to avoid passing Jackson 2.x `JsonNode` objects into Jackson 3.x APIs. `PathType.JSON_PATH` is configured so validation messages keep the existing `$.property` format and no test fixture had to change. The shaded uber-JAR adds two new relocations (`tools.jackson` and `org.snakeyaml`).
- BREAKING: minimum supported Java runtime raised from **Java 11** to **Java 17**. `mockserver/pom.xml` `maven.compiler.source` and `maven.compiler.target` are now `17`, so published artifacts are Java 17 bytecode and will not run on a Java 11 JVM. The CodeQL workflow, Buildkite build agent image, and local dev scripts have all been aligned to JDK 17.
- BREAKING: coordinated upgrade to the Jakarta EE 10 / Servlet 6 stack and the upstream dependencies that required it. The full `javax.*` → `jakarta.*` namespace migration (servlet, ws.rs, annotation, inject, persistence) is now complete. Library bumps: Spring Framework 5.3 → 7.0, Spring Boot 2.7 → 4.0, Tomcat embed 9 → 11, Jetty 9.4 → 12, Jersey 3.1 → 4 (`jersey-apache-connector` → `jersey-apache5-connector` with Apache HttpClient 5), `jakarta.xml.bind-api` 3 → 4, `jakarta.servlet-api` 4 → 6, `jakarta.ws.rs-api` 2.1 → 4, `jakarta.annotation-api` 1.3 → 3, JUnit Jupiter 5.14 → 6.1, json-unit 2 → 5, json-path 2 → 3, Netty 4.1 → 4.2.14.Final (introduced via `netty-bom` so the new `netty-codec-base` / `netty-codec-compression` / `netty-codec-http3` sub-modules stay aligned).
  - Runtime deployment in a servlet container now requires a Servlet 6 / Jakarta EE 10 host: Tomcat 11+, Jetty 12+, WildFly 32+, or equivalent. Servlet 5 / Jakarta EE 9 containers are no longer supported.
  - `MockServerServlet` and `ProxyServlet` runtime contract is unchanged for consumers using `jakarta.servlet.*`. Consumers still importing `javax.servlet.*` must update their imports.
  - WAR test scaffolding that configured TLS via the removed `Connector.setAttribute("keystoreFile"/"keystorePass"/…)` API must migrate to the Tomcat 11 `SSLHostConfig` + `SSLHostConfigCertificate` pattern. The four WAR/proxy-war integration test classes in this repo show the working shape.
  - Servlet 6 preserves RFC 6265 surrounding double quotes on cookie values returned by `Cookie.getValue()`. MockServer's request decoder now strips them so cookie semantics are unchanged for clients.
  - Spring 7 requires the `-parameters` javac flag for `@PathVariable` / `@RequestParam` name resolution; this is now enabled project-wide in `maven-compiler-plugin`.
  - Spring 7's `MappingJackson2HttpMessageConverter` is deprecated for removal in favour of `JacksonJsonHttpMessageConverter`. MockServer keeps Jackson at 2.21.3 (and `json-schema-validator` at 1.5.9) for now because `swagger-parser` is still locked to Jackson 2; Jackson 3 upgrade will land once `swagger-parser` ships a Jackson 3 line (see #1970).
- BREAKING: Nashorn (`org.openjdk.nashorn:nashorn-core:15.7`) removed as a managed dependency. `JavaScriptTemplateEngine` now uses the GraalVM Polyglot API directly (`org.graalvm.polyglot.Context` with `HostAccess.ALL` + `allowHostClassLookup` for the existing class-deny-list security policy). GraalJS 25.x dropped the JSR-223 `javax.script` bridge, so the previous Nashorn-or-GraalJS-via-JSR-223 fallback would have silently returned a null engine and broken every JavaScript template at runtime. Downstream consumers that previously relied on Nashorn arriving transitively must add `org.openjdk.nashorn:nashorn-core` to their own dependencies, or migrate to GraalVM polyglot directly.
- Drop the `--add-exports=java.base/sun.security.{x509,util}=ALL-UNNAMED` javac flags inherited from the Java 11 era. Repo-wide audit found zero `sun.security.*` references after the Java 17 / jakarta migration, so the flags were dead weight.

### Fixed
- Dashboard **Log Messages** panel: a non-breaking space is now rendered after each expandable JSON block, so the text that follows (e.g. `} matched expectation:`) no longer butts directly against the closing brace.
- **CORS for the dashboard served cross-origin.** When `mockserver.corsAllowOrigin` is blank (the default) MockServer now reflects the request's `Origin` in `Access-Control-Allow-Origin` instead of emitting an empty (invalid) header, and falls back to sensible `Access-Control-Allow-Methods` / `Access-Control-Allow-Headers` when those are blank (reflecting the requested headers on preflight). The MCP endpoint (`/mockserver/mcp`) now answers the CORS preflight and exposes `Mcp-Session-Id` via `Access-Control-Expose-Headers`. Together these let the dashboard (and any browser client) call the control-plane API and MCP endpoint from a different port or domain. An explicit `corsAllowOrigin` is still honoured as an allow-list, and `*` is never combined with `Access-Control-Allow-Credentials: true`.
- Helm chart downloads for older versions: every chart listed in `index.yaml` now returns a valid `.tgz` from `https://www.mock-server.com/`. Previously, releases that created a new versioned site could leave older chart archives missing from the live bucket while `index.yaml` still referenced them, so `helm pull` / `helm install` failed for any version other than the latest. The release pipeline now syncs the full set of charts on every run, making the bucket self-healing (fixes #2282).

## [6.1.0] - 2026-05-27

### Security
- SSRF protection for forward and forward-template actions: new `mockserver.forwardProxyBlockPrivateNetworks` property (default `false` for backwards compatibility) rejects forward targets that resolve to loopback, link-local, RFC 1918 private, or cloud metadata addresses (e.g. `169.254.169.254`). Enable in hardened or multi-tenant deployments where untrusted callers can register expectations. A future major release is expected to flip the default to `true`.
- ReDoS protection in regex matchers: regex evaluation now runs on a shared cached daemon-thread pool with a configurable timeout `mockserver.regexMatchingTimeoutMillis` (default `5000`ms). Patterns that exceed the budget are treated as non-matches and a WARN log entry is written, so a pathological pattern cannot wedge a Netty worker.
- XPath DoS protection: XPath evaluation in body matching now uses the same shared timeout executor with `mockserver.xpathMatchingTimeoutMillis` (default `5000`ms).
- Cryptographically secure randomness: `UUIDService` and `TemplateFunctions` now use `SecureRandom` instead of `java.util.Random` for UUID generation, `rand_int`/`rand_int_10`/`rand_int_100`, and `rand_bytes` template helpers.
- Loud insecure-mode warning logs at startup / SSL-context init: a WARN is emitted when (a) the forward proxy trusts all TLS certificates (`forwardProxyTLSX509CertificatesTrustManagerType=ANY`), (b) Velocity class loading is enabled (`velocityDisallowClassLoading=false`), (c) JavaScript templates have no class restrictions (`javascriptDisallowedClasses` empty), or (d) `tlsProtocols` includes the deprecated TLSv1 / TLSv1.1.
- `mockserver.tlsAllowInsecureProtocols` configuration property (default `true` for backwards compatibility): when set to `false`, any `TLSv1` or `TLSv1.1` entries in `mockserver.tlsProtocols` are filtered out before the SSL context is built, giving users an opt-in hardened TLS profile without having to rewrite their existing `tlsProtocols` value. A future major release is expected to flip this default to `false`.

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

## [6.0.0] - 2026-05-21

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




