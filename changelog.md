# Changelog
All notable and significant changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Security

- **SCIM bearer-token enforcement now fails closed** (`mockserver-core`). When SCIM bearer-token
  enforcement is enabled but no expected token is configured, requests are now rejected instead of
  accepting any presented token, and the token comparison is constant-time.
- **JWT control-plane validation no longer accepts HMAC algorithms** (`mockserver-core`). `JWTValidator`
  verifies tokens against a public-key JWK set, so it now accepts only asymmetric signature algorithms
  (`RS*`, `ES*`, `PS*`, `EdDSA`) and rejects HMAC (`HS256/384/512`), closing an algorithm-confusion
  forgery vector and matching the OIDC validator. If you were intentionally validating HMAC-signed
  tokens through `JWTValidator`, switch to an asymmetric key.
- **Coarse role-based authorization of the control plane** (`mockserver-core`). New configuration
  properties `mockserver.controlPlaneAuthorizationEnabled` (env `MOCKSERVER_CONTROL_PLANE_AUTHORIZATION_ENABLED`,
  default `false`) and `mockserver.controlPlaneScopeMapping` (env `MOCKSERVER_CONTROL_PLANE_SCOPE_MAPPING`,
  default empty). When enabled, after a control-plane request is authenticated its verified principal's
  scopes/groups are mapped — via a comma-separated `value=role` mapping such as
  `platform-admins=admin,qa-team=mutate,viewers=read` — to one of three hierarchical roles
  (`admin` ⊇ `mutate` ⊇ `read`). Reads (retrieve/verify/diff and all GETs) require `read`; every other
  (mutating) operation requires `mutate`. A principal whose granted roles do not satisfy the required role
  is rejected with `403 Forbidden` (generic body; the granted-vs-required detail is logged server-side
  only) and the denial is recorded in the control-plane audit log with `outcome=FORBIDDEN`. Fail-closed:
  a principal with no mapped scope is denied every mutation, so authorization should be used together with
  control-plane OIDC authentication (it requires a verified principal). Off by default — with authorization
  disabled, authenticated requests behave byte-for-byte as before.
  **Coverage:** the check covers every operation dispatched through `HttpState.handle` (all expectation
  CRUD, clear/reset, retrieve/verify, mode, drift/chaos/SLO, replay, contract-test, …) **and** the
  Netty-serviced control-plane reads/writes `PUT/GET /mockserver/configuration`,
  `GET /mockserver/openapi.yaml` and `GET /mockserver/llm/optimisationReport`, which now route through the
  same authn+authz+audit gate (so `PUT /configuration` is `MUTATE` and a read-only principal is `403`'d,
  while the three reads classify `READ` and a verified principal with no mapped role is `403`'d). **Not**
  covered: the lifecycle endpoints `PUT /mockserver/bind`, `PUT /mockserver/stop` and `PUT /mockserver/status`
  are neither authenticated nor authorized (pre-existing — anyone who can reach the port can bind/stop the
  server regardless of roles); and the MCP control plane (`/mockserver/mcp`, all transports) is authenticated
  but its per-tool read/mutate authorization is **not yet enforced**, so a verified read-only principal can
  still invoke a mutating MCP tool — treat MCP access as mutate-capable when authorization is enabled. See
  `docs/code/tls-and-security.md` for the full coverage table.
- **Opt-in secret redaction in the event log and dashboard** (`mockserver-core`). New configuration
  property `mockserver.redactSecretsInLog` (env `MOCKSERVER_REDACT_SECRETS_IN_LOG`, default `false`).
  When enabled, sensitive request/response header values (`Authorization`, `Proxy-Authorization`,
  `Cookie`, `Set-Cookie`, `x-api-key`, `api-key` — covering bearer/token credentials) are masked with
  `***REDACTED***` in the requests/responses returned by `retrieveLogMessages`, `retrieveRecordedRequests`
  and `retrieveRecordedRequestsAndResponses` (and the JSON/HAR/cURL/OpenAPI/Postman export formats derived
  from them) and in the dashboard event view; JSON body fields named in `mockserver.fixtureBodyRedactFields`
  are masked too. Redaction applies only to the displayed/retrieved copies — request matching and
  verification continue to see the original values, so enabling it does not change matching behaviour.
  Reuses the existing `FixtureRedactor` helper. Off by default so the log is byte-for-byte unchanged.

- **Dashboard UI (`mockserver-ui`): forced the transitive `dompurify` dependency to `3.4.11`** via an npm
  `overrides` entry, resolving all 16 open Dependabot advisories. The `monaco-editor` body-matcher editor
  (added recently) bundles a pinned `dompurify@3.2.7`; the override hoists every copy to `3.4.11`. Advisories
  cleared (mostly mXSS / DoM-clobbering / prototype-pollution in DOMPurify's HTML sanitiser): GHSA-cmwh-pvxp-8882,
  GHSA-vxr8-fq34-vvx9, GHSA-gvmj-g25r-r7wr, GHSA-rp9w-3fw7-7cwq, GHSA-x4vx-rjvf-j5p4, GHSA-76mc-f452-cxcm,
  GHSA-hpcv-96wg-7vj8, GHSA-r47g-fvhr-h676, GHSA-h7mw-gpvr-xq4m, GHSA-crv5-9vww-q3g8, GHSA-v9jr-rg53-9pgp,
  GHSA-39q2-94rc-95cp, GHSA-cjmm-f4jc-qw8r, GHSA-cj63-jhhr-wcxv, GHSA-h8r8-wccr-v5f2, GHSA-v2wj-7wpq-c8vv.


### Added

- **Callbacks across the clients.** **Class callbacks** (`httpResponseClassCallback` /
  `httpForwardClassCallback` — reference a server-side callback class) are now available in the Go,
  .NET, Rust, PHP, Node and Ruby clients (Python gained the missing builder). **Object/closure
  callbacks** (write the response in your own language — the client opens MockServer's callback
  WebSocket, the server streams each matched request to your closure, your closure returns the
  response) are now available in the Go, .NET and Rust clients via `mockWithCallback(...)`, reusing
  each client's existing breakpoint WebSocket transport; Python's object-callback path was completed
  and fixed. The PHP client, being REST-only (no WebSocket), supports class callbacks only — object
  callbacks are documented as unavailable there. Additive and backward-compatible; each client's
  object-callback example is validated end-to-end against a live MockServer.
- **Fixed: Python object callbacks and breakpoints were broken on `websockets` 16** (`mockserver-client-python`).
  The callback/breakpoint WebSocket client used the deprecated `websockets.client.connect` together with
  the modern `additional_headers` kwarg, which the current `websockets` release rejects — breaking every
  object callback and breakpoint. It now uses `websockets.asyncio.client` with a legacy fallback.

- **Control-plane auth and TLS/mTLS across every client.** The Go, .NET, Rust, PHP, Node and Python
  clients now let you connect to a secured MockServer: a **control-plane bearer token** (static or a
  per-request supplier) attached as `Authorization: Bearer <token>` to every control-plane request; a
  **CA certificate** to trust the server's TLS over HTTPS; and a **client certificate + key** for mutual
  TLS. All additive and backward-compatible (default behaviour unchanged); the underlying HTTP layer of
  each client (net/http, HttpClient, reqwest, Guzzle, https, urllib+ssl) is configured accordingly.
- **Editor JSON Schema regenerated and in sync** (`mockserver-vscode`, `mockserver-jetbrains`). The
  bundled `*.mockserver.json` validation schema was stale; it now includes `crossProtocolScenarios`,
  `responseWeights`, `switchAfter`, `rateLimit`, and the full `responseMode` enum (`SEQUENTIAL`/`RANDOM`/
  `WEIGHTED`/`SWITCH`), so the IDEs no longer flag valid expectations as errors. The generator
  (`scripts/generate-editor-expectation-schema.mjs`) had stopped resolving newer schema refs — its
  reference-file list now covers `rateLimit`, `conditionalRequestDefinition`, and `recoverAfter`.

<!-- Load scenario registry: named load/trigger, concurrent runs, start delay, preload -->
- **Load scenario registry — load by name, trigger one or many to run concurrently, with start delays and
  startup preloading** (`mockserver-core`). Load scenarios are now a registry of NAMED scenarios you *load*
  (register) separately from *running* them (breaking change to the unreleased control plane):
  - `PUT /mockserver/loadScenario` **loads/registers** a scenario by its `name` (does not run it; allowed even
    when load generation is disabled). The registry is persisted in the `StateBackend`, so it survives a reset
    and replicates across a cluster.
  - `PUT /mockserver/loadScenario/start` with `{"names":[...]}` (or `{"name":"..."}`) **triggers** one or many
    registered scenarios to run **concurrently**; each honours its own new `startDelayMillis`, so a single
    trigger can start several scenarios where some begin firing immediately and others wait — enabling realistic
    combined workloads. Requires load generation enabled (else `403`).
  - `GET /mockserver/loadScenario` lists all registered scenarios with their lifecycle state
    (`LOADED`/`PENDING`/`RUNNING`/`COMPLETED`/`STOPPED`) and live status; `GET`/`DELETE /mockserver/loadScenario/{name}`
    operate on one; `PUT /mockserver/loadScenario/stop` stops by name (or all).
  - **Preload at startup**: `mockserver.loadScenarioInitializationJsonPath` loads a JSON array of scenarios into
    the registry as `LOADED` on boot, so MockServer can start with scenarios staged and be triggered later by the
    UI, a client, or curl.
  - New cap `mockserver.loadGenerationMaxConcurrentScenarios` (default 10). The `mock_server_load_*` metrics keep
    one series per `(scenario, run_id)`, so concurrent runs stay distinguishable; per-run series are evicted on
    re-trigger/removal.
- **Stateful scenarios: typed support across every client, runnable examples, and a dedicated docs page.**
  All eight client libraries (Java, JavaScript/Node, Python, Ruby, Go, .NET, Rust, PHP) now expose
  **typed** stateful-scenario APIs at parity: the expectation fields `scenarioName` / `scenarioState` /
  `newScenarioState`, sequential / cycling responses (`httpResponses` + `responseMode` of
  `SEQUENTIAL` / `RANDOM` / `WEIGHTED` + `responseWeights` / `SWITCH` + `switchAfter`),
  `crossProtocolScenarios`, and a typed `scenario(name)` helper wrapping the scenario REST endpoints
  (`state` / `set` / timed `set` / `trigger`, plus a `scenarios()` listing). The Java fluent builder
  gained the previously-missing `withHttpResponses` / `withResponseWeights` / `withSwitchAfter` /
  `withCrossProtocolScenario(s)` setters. New runnable, self-asserting **examples** live under
  `examples/<curl|json|node|python|ruby|go|dotnet|rust|php>/scenario/` (plus the Java examples module),
  covering the login state machine, sequential cycling, timed auto-transition, external trigger, and
  cross-protocol correlation. A new consumer docs page — **Stateful Scenarios** — presents every
  feature in a jump-link feature table with per-language accordion examples linking to the runnable code.
- **Docker example-validation harness** (`examples/validate/run.sh`). Builds a MockServer image from the
  current checkout, starts it on a private Docker network, and runs each client's scenario example
  inside the matching toolchain container wired to the local client source, asserting it passes — so
  client example code is validated against a real server even for toolchains not installed on the host.
  Mounts the host CA bundle when present so dependency fetches work behind a TLS-inspecting proxy.

<!-- Load Profile v2: stages, arrival-rate (iterations/sec), ramp curves -->
- **Load scenarios: multi-stage profiles, open-model arrival rate, and ramp curves** (`mockserver-core`). A load
  scenario's `profile` is now an ordered list of **stages** run in sequence, replacing the single CONSTANT/LINEAR
  ramp. Each stage is one of: a **VU** stage (closed model — hold `vus`, or ramp `startVus`→`endVus`), a **RATE**
  stage (open model — hold `rate`, or ramp `startRate`→`endRate`, in **iterations per second**; the engine
  auto-scales virtual users up to a cap to sustain the arrival rate), or a **PAUSE**. Ramp stages take a `curve`
  of `LINEAR`, `EXPONENTIAL`, or `QUADRATIC`. This composes step/spike/soak/stress shapes (e.g. ramp → hold →
  spike → recover) in one scenario. The GET status now reports the current `stageIndex`, `stageType`, and
  `currentTarget` (target VUs or rate). New caps `mockserver.loadGenerationMaxRate` (default 5000 it/s) and
  `mockserver.loadGenerationMaxStages` (default 20). The arrival-rate scheduler uses bounded deficit accounting so
  it hits the target rate independent of tick granularity and never bursts across a stage boundary. (Breaking
  change to the unreleased load-scenario profile shape.)

<!-- Load scenario + SRE control plane across all client libraries -->
- **Load-scenario (load injection) support in all client libraries, and SRE control-plane parity for the
  remaining clients.** Every client (Java, Node, Python, Ruby, Go, .NET, Rust, PHP) gains the full load-scenario
  **registry** API — register (`loadScenario`), list (`loadScenarios`), get/delete by name, clear, start one or
  many by name (`startLoadScenarios`), stop (`stopLoadScenarios`, names or all), and a `runLoadScenario`
  convenience that registers then starts — mirroring `PUT/GET/DELETE /mockserver/loadScenario`,
  `/loadScenario/start` and `/loadScenario/stop`. Registration is allowed when load generation is disabled;
  starting surfaces the off-by-default `403` as a clear, catchable error. Scenario definitions carry the new
  `startDelayMillis`. The four clients that previously had no SRE control plane (Go, .NET, Rust, PHP) additionally
  gain idiomatic methods for service chaos (`/serviceChaos`), SLO verdicts (`/verifySLO`, with the 200 PASS /
  406 FAIL / 400 status semantics preserved so the verdict stays readable), preemption (`/preemption`), and
  chaos experiments (`/chaosExperiment`). All request bodies are camelCase-on-the-wire and were verified against
  the OpenAPI contract; the PHP `HttpRequest` builder gains a `socketAddress(host, port, scheme)` setter so a
  load step can target an upstream the typed way, matching the other clients.
- **Load-scenario registry examples** (`examples/`) for curl, raw JSON, and all client languages (Node, Python,
  Ruby, PHP, Java, Go, Rust, .NET). Each registers a realistic multi-stage scenario (a RATE ramp → hold → pause
  with `startDelayMillis`), starts it, lists it as `RUNNING`, then stops — using each client's registry methods.
  Every example was validated end-to-end against a live server started with `loadGenerationEnabled`.
- **`scripts/verify-php-client.sh`** — a Dockerised PHP lint + Composer + PHPUnit runner so the PHP client can be
  verified locally with no PHP/Composer on the host. Auto-detects a host CA bundle (`SSL_CERT_FILE` /
  `REQUESTS_CA_BUNDLE` / `CURL_CA_BUNDLE`) and mounts it so it works behind a corporate TLS-inspection proxy.

<!-- Dashboard Performance (load scenario) panel -->
- **Dashboard "Performance" panel for load scenarios** (`mockserver-ui`, `mockserver-core`). A new dashboard tab
  to author, run, monitor, stop and edit load scenarios (load injection) without leaving the UI. Authoring covers
  the scenario name, custom labels, `CONSTANT`/`LINEAR` ramp profile, and steps (method, path, target
  `socketAddress`, request headers, think-time, per-step name/labels; template type limited to the supported
  `VELOCITY`/`MUSTACHE`). A running test is polled live (state, VUs, throughput, latency percentiles, errors) and
  can be stopped or edited — editing reloads the running scenario's definition (now echoed by
  `GET /mockserver/loadScenario` as a `definition` field, so any running test can be edited, not only one started
  in the same browser) and re-submitting replaces/restarts it. When a run stops, key metrics are summarised, and a
  single combined chart plots RPS / active VUs / in-flight / p50 / p95 / p99 / error-rate with per-series toggles
  to fit the available space. Cross-links the Metrics tab (`mock_server_load_*`). The dashboard is bundled into the
  `mockserver-netty` jar at build time, so a running server shows the new tab after rebuilding from this change.
- **Performance panel: load-scenario registry UX and a Code view** (`mockserver-ui`). The panel now works with the
  named-scenario registry: a list of all registered scenarios with their lifecycle-state badge, multi-select to
  start several at once, and a "Running now" view that shows every concurrently-running scenario with its own live
  metrics and an individual Stop. The author form gains a "Start delay (ms)" field and split **Load** (register
  only) / **Load & Run** actions, plus per-scenario delete and a Clear-all. A new **Code** tab — matching the Mocks
  and Verification panels — generates register-and-start snippets for the current scenario across Java, Node,
  Python, Go, C#, Ruby, Rust, JSON and curl.

<!-- First-class load-injection metrics (Prometheus + OTEL) -->
- **First-class metrics for load injection (load scenarios)** (`mockserver-core`). A load run now exposes a
  dedicated `mock_server_load_*` metric family over **both Prometheus and OTEL** (previously a load run was
  indistinguishable from real proxy traffic on `mock_server_forward_*` and emitted nothing to OTEL), so you can
  chart the load injector alongside your system-under-test. Metrics: `mock_server_load_request_duration_seconds`
  (histogram, with a `trace_id` exemplar for metric→trace pivot), `..._requests`, `..._request_bytes`,
  `..._response_bytes`, `..._iterations`, `..._throttled{reason}` (`inflight_cap`/`rate_limit`),
  `..._errors{kind}` (`timeout`/`connection`/`render`/`http_5xx`/`null_response`), and `..._active_vus` /
  `..._inflight_requests` (live gauges). All carry structured labels `scenario, run_id, step, route, method,
  status_class`, where `route` is an auto-templatized low-cardinality path (numeric/UUID/long-hex segments →
  `{id}`) and `run_id` is a stable per-run UUID correlating the metrics with the `GET /mockserver/loadScenario`
  status. **Custom labels:** a `LoadScenario` and each `LoadStep` may carry `labels` (and a step `name`) —
  attached as arbitrary OTEL attributes, and as Prometheus labels for any key named in the new
  `mockserver.loadGenerationMetricLabels` allowlist (env `MOCKSERVER_LOAD_GENERATION_METRIC_LABELS`). Status-DTO
  percentiles now derive from the histogram buckets (bounded memory; any percentile is queryable via
  `histogram_quantile()`), replacing a sample reservoir. Completed-run series stay scrapeable until the next run
  starts, then are evicted (at most one completed run retained — bounded). Off-path and zero-cost when
  `metricsEnabled=false` or no run is active; the existing `mock_server_forward_*` behaviour is unchanged.

<!-- Portable example code: remove stale URLs / hardcoded developer paths repo-wide -->
- **Portable example code across the repo** (`jekyll-www.mock-server.com`, `mockserver-client-node`,
  `mockserver-node`, dev scripts). Removed a stale OpenAPI spec URL (`mock-server/mockserver` → `404`; now the
  `mockserver-monorepo` URL) and hardcoded `/Users/...` developer file paths that made example snippets fail
  on any other machine. The `using_openapi.html` and OpenAPI request-matcher doc snippets now use portable
  placeholders / classpath forms; the Node client packages' bundled OpenAPI examples now load a co-located
  spec via `file://__dirname` (spec bundled alongside each example) and drop an undeclared `js-string-escape`
  use; developer build/test scripts derive paths from `$HOME` / the git root instead of a hardcoded home.

<!-- Examples index page + more runnable examples + example accuracy fixes -->
- **Website examples index, more runnable examples, and example-accuracy fixes** (`jekyll-www.mock-server.com`,
  `examples`, `mockserver-core`, `mockserver-client-python`, `mockserver-client-ruby`). A new
  `examples.html` page lists every example folder (by language/interface plus the curl/json feature
  categories) with a one-line description and a link to its GitHub folder; the site's "Examples" nav link
  now points to it. New examples: a Java-client LLM mock (`LlmMockExamples`) and curl + json examples for
  OpenAPI contract testing (`/mockserver/contractTest`) and Pact provider-states. Every example in the
  `examples/` tree (curl, json, java, node, python, ruby, go, dotnet, rust) was run against a live MockServer
  built from current source; the bugs that surfaced were fixed:
  - **Cookie serialization bug in the Python and Ruby clients** — request/response cookies were serialized as
    a `[{name, values}]` array (the form used for headers/query parameters) instead of the `{name: value}`
    object map MockServer requires, so the server rejected any client-built cookie matcher with `400`. Both
    clients now serialize cookies as a map (and still tolerate the legacy array form on read); headers and
    query parameters are unchanged. Regression tests added.
  - **`httpWebSocketResponse` JSON Schema** now accepts the `matchers` array (per-incoming-frame response
    rules: `frameType`/`textMatcher`/`responses`) — already supported by the model but missing from the
    schema, so it was wrongly rejected on validation / flagged by IDEs.
  - Example fixes: `verify_sequence` (curl + json) used a bare array but `/verifySequence` requires
    `{"httpRequests": [...]}`; several OpenAPI examples used a stale `mock-server/mockserver` spec URL (now
    the monorepo URL) and hardcoded developer file paths (now a bundled local spec / classpath resource);
    a Node example dropped an undeclared `js-string-escape` dependency.

<!-- Documentation & example coverage for recently-shipped features -->
- **Documentation and example coverage for recently-added features** (`jekyll-www.mock-server.com`, `examples`,
  OpenAPI spec). Closed the gap between shipped features and the consumer docs/examples. New consumer pages
  `load_injection.html` (API-driven load generation / Load Scenarios) and `graphql_mocking.html` (GraphQL spec
  import + schema-driven response synthesis); new sections on `chaos_testing.html` for SLO resilience verdicts
  (`verifySLO`), connection-lifecycle faults, preemption simulation and the saved chaos-profile library; new
  documentation for control-plane authorization (`controlPlaneAuthorizationEnabled` / `controlPlaneScopeMapping`),
  A2A streaming/push notifications, the MCP server's `prompts/*` and `sampling/createMessage` methods, cached/
  reasoning token usage fields, provider-correct LLM chaos error bodies, and the `FuzzyBody`,
  `conditionalRequestDefinition`, `accept:` content-negotiation, and `WEIGHTED` response-selection matchers.
  The OpenAPI spec (`mockserver-openapi.yaml`) now declares the previously-undocumented control-plane endpoints
  `/mockserver/loadScenario`, `/mockserver/verifySLO`, `/mockserver/preemption`,
  `/mockserver/chaosExperiment/profiles` (+ `/apply/{name}`) and `/mockserver/contractTest`, so the generated
  Bruno and Postman example collections now cover them.

<!-- Runnable data-plane example code for recently-shipped features -->
- **Runnable example code for recently-added data-plane features** (`examples`, `mockserver-core`). Added
  worked, server-validated examples under `examples/` for features that previously had none: LLM response
  mocking (`httpLlmResponse` chat completion, embeddings, rerank), MCP server tool mocking, A2A agent mocking,
  GraphQL (schema import and a `GRAPHQL` body matcher), and the newer matchers/response modes — `FuzzyBody`,
  `conditionalRequestDefinition`, `accept:` content-negotiation, `WEIGHTED`/`SWITCH` response selection,
  `generateFromSchema` response synthesis, soft `verifyAll`, and response verification. Covered in the canonical
  `examples/curl/` and `examples/json/` sets plus idiomatic LLM/MCP client examples for `examples/node/` and
  `examples/python/`. All new payloads were validated against a running MockServer. As part of this, the
  published expectation JSON Schema (`org/mockserver/model/schema/body.json`) now accepts the `schema`,
  `selectionSetMatchType` and `fields` keys on a `GRAPHQL` body — they were already supported by the model,
  serializer and matcher (and used for schema-driven response synthesis) but were missing from the schema, so an
  IDE validating `*.mockserver.json` against the bundled schema would wrongly flag them.

<!-- Verified OIDC control-plane authentication (Tier 1.5-A) -->
- **Verified OIDC bearer authentication for the control plane** (`mockserver-core`, `mockserver-netty`). Setting
  `controlPlaneOidcAuthenticationRequired=true` makes MockServer verify control-plane requests against an external
  OpenID Connect identity provider: it checks the `Authorization: Bearer` access-token signature against the IdP's
  JWK set (set directly via `controlPlaneOidcJwksUri`, or discovered from `controlPlaneOidcIssuer`'s
  `/.well-known/openid-configuration`), and asserts issuer, audience (`controlPlaneOidcAudience`), `exp`/`nbf`, and
  that the token's granted scopes contain every `controlPlaneOidcRequiredScopes` entry (scopes read from
  `controlPlaneOidcScopeClaim`, default `scope`; `scp`/`roles`/`groups` array claims also supported). The verified
  `sub` is now recorded as the control-plane audit principal with source `verified-oidc` (replacing the unverified
  best-effort extraction when a verified principal is available). OIDC can be combined with existing mTLS and/or JWT
  control-plane authentication (all enabled handlers must pass). The `AuthenticationHandler` SPI gains a richer,
  default-adapted `authenticate(...) -> AuthenticationResult` method; existing and third-party boolean handlers keep
  working unchanged. Off by default — with no `controlPlaneOidc*` configuration the control plane is byte-for-byte
  unchanged. See [Control Plane Authentication](/mock_server/control_plane_authorisation.html).

<!-- SCIM provider mocking (Wave 4) -->
- **Mock a SCIM 2.0 identity provider** (`mockserver-core`, `mockserver-client-java`). A single control-plane
  call — `PUT /mockserver/scim` (empty body or a `ScimProviderConfiguration`), or
  `mockServerClient.mockScimProvider(...)` from Java — generates a fully functional in-memory SCIM provider.
  It serves CRUD over `Users` and `Groups` (`GET`/`POST` collections and `GET`/`PUT`/`PATCH`/`DELETE` by id)
  plus the SCIM discovery documents (`ServiceProviderConfig`, `ResourceTypes`, `Schemas`). Responses use the
  `application/scim+json` media type and SCIM shapes: every resource carries `schemas`/`id`/`meta`, lists are
  wrapped in a `ListResponse` envelope, and errors use the SCIM `Error` envelope. Supports a single-attribute
  `filter` (`eq`/`co`/`sw`/`pr`), SCIM `PatchOp` (`add`/`replace`/`remove` over top-level and one-level
  sub-attributes), 1-based `startIndex`/`count` pagination, an optional bearer-token gate (discovery stays
  open), and a configurable base path (default `/scim/v2`), id strategy, and seed data. Re-running the call
  upserts in place. See [Mock a SCIM Provider](/mock_server/mocking_scim.html).

#### AI, LLM & agent protocols (LLM / MCP / A2A)
- **Approximate LLM token-count utility and opt-in usage inference** (`mockserver-core`). New pure helper
  `org.mockserver.llm.TokenCounter` estimates token counts for text using a blended character/word heuristic
  (documented explicitly as an **estimate**, not a real BPE tokenizer). A new opt-in switch
  `mockserver.llmInferUsageEnabled` (default `false`) makes a mocked completion that omits `usage` populate
  approximate `prompt_tokens` / `completion_tokens` — derived from the decoded request conversation and the
  response text/tool-calls — before the response is encoded. Off by default, so existing responses are
  unchanged (an absent `usage` still encodes as zeros) and a completion that already declares `usage` is never
  overwritten.

- **Agent framework recipes** (docs). New page `ai_agent_frameworks.html` in the AI Integration section: minimal accurate recipes for pointing LlamaIndex (`Settings.llm = OpenAI(api_base=...)`) and the OpenAI Agents SDK (`set_default_openai_client(AsyncOpenAI(base_url=...))`) at MockServer to mock LLM provider calls during testing, with env-var alternatives and a proxy fallback section for frameworks without a base-URL override.

- **Opt-in strict structured-output enforcement for LLM completions** (`mockserver-core`). A mocked LLM
  completion that declares an `outputSchema` can now opt in to strict enforcement via a new
  `enforceOutputSchema` flag (`Completion.withEnforceOutputSchema(true)` / `Completion.enforceOutputSchema()`,
  the `enforceOutputSchema` expectation-JSON field, or the `mock_llm_completion` MCP parameter). When enabled
  and the configured response does not conform to the schema, the mock **fails loudly** with a provider-correct
  error (HTTP `502`, plus the `x-mockserver-structured-output-invalid` diagnostic header) instead of returning
  the non-conforming body — modelling a real provider's strict `response_format: json_schema` mode, which
  guarantees schema-valid output. The check runs before dispatch on both the streaming and non-streaming paths,
  so a strict streaming completion with a non-conforming body never begins streaming. Enforcement is opt-in and
  fully back-compatible: the default (unset/`false`) keeps the existing fail-soft validate-and-log behaviour
  (diagnostic header only), and the flag has no effect without an `outputSchema`. A blank, absent-text, or
  malformed schema remains a no-op and never produces an enforcement error.

- **A2A mock builder: streaming and push notifications** (`A2aMockBuilder`, `mockserver-client-java`). The builder
  previously hard-coded the agent card's `capabilities.streaming` and `capabilities.pushNotifications` to `false`.
  Two opt-in features now make those advertise `true` when configured (default off = unchanged behaviour):
  - `withStreaming()` (and `withStreamingMethod(String)`) generates an SSE expectation for the A2A streaming
    JSON-RPC method (default `message/stream`, legacy `tasks/sendSubscribe`) that returns an `httpSseResponse`
    stream of `TaskStatusUpdateEvent` (`working` → `completed`, the last with `"final": true`) and
    `TaskArtifactUpdateEvent` chunks, each wrapped in a JSON-RPC 2.0 response envelope.
  - `withPushNotifications(webhookUrl)` makes `tasks/pushNotificationConfig/set` echo the registered config and
    makes each `tasks/send` additionally POST the completed task to the configured webhook URL (via an
    override-forwarded-request) while still returning the JSON-RPC task response to the caller.

- **Fluent LLM and MCP mock builders for the Ruby, Go, Rust, .NET and PHP clients**: the idiomatic
  LLM-mocking and MCP-server-mocking builders that previously shipped only in the Node and Python
  clients are now available in all five remaining client libraries. Each client gains an LLM builder
  (`llm_mock`/conversation/failover, provider + model, completions, tool calls, streaming physics,
  usage and embeddings) and an MCP mock builder (tools, resources and prompts over JSON-RPC 2.0 on the
  Streamable HTTP transport), producing the same expectation wire JSON as the Java/Node/Python builders
  so a mock scripted from any client behaves identically. Each port follows its language's conventions
  (Ruby `MockServer::LLM`/`MockServer::MCP` modules; Go `llm.go`/`mcp.go` builders with an extended
  `Expectation` struct; Rust `llm`/`mcp` modules returning `serde_json::Value`; .NET `LlmMockBuilder`/
  `McpMockBuilder` under `MockServer.Client`; PHP `MockServer\Llm`/`MockServer\Mcp` builders) and ships
  with unit tests asserting the generated wire JSON.

- **LLM embeddings for Gemini/Ollama/Bedrock, plus rerank mocking (Cohere/Voyage)**: `httpLlmResponse` embeddings
  now work for three more providers (previously only OpenAI/Azure-OpenAI returned a real embedding response):
  - **Gemini** — emits the `embedContent` shape (`{"embedding":{"values":[...]}}`, default 768 dimensions).
  - **Ollama** — emits the `/api/embed` batch shape (`{"embeddings":[[...]]}` with `model` and `prompt_eval_count`,
    default 768 dimensions), which also satisfies clients reading the legacy `/api/embeddings` `embedding` array.
  - **Bedrock** — emits the Amazon Titan shape (`{"embedding":[...],"inputTextTokenCount":N}`) by default, or the
    Cohere-on-Bedrock shape (`{"embeddings":[[...]]}`) when the model id starts with `cohere` (default 1024 dimensions).

  All embedding vectors are deterministic from the input (when `deterministicFromInput` is set) and L2-normalised,
  via a new shared `EmbeddingVectors` helper (the OpenAI codec now uses it too — no behaviour change). A new
  **rerank** action mocks rerank endpoints: set `rerank` on `httpLlmResponse` with `provider` `COHERE` or `VOYAGE`
  (both new rerank-only providers). MockServer returns one result per candidate document from the request's
  `documents` array (each `{"index":N,"relevance_score":F}`), sorted by descending relevance and optionally capped
  to `topN`, in the provider-correct envelope — Cohere `{"results":[...]}`, Voyage
  `{"object":"list","data":[...],"usage":{...}}`. Scores are reproducible when `deterministicFromInput` is set
  (opt-in, matching embeddings).

- **MCP control plane: `prompts/list`, `prompts/get` and `sampling/createMessage`**: MockServer's own MCP server
  (`/mockserver/mcp`, over HTTP/1.1, HTTP/2 and HTTP/3) now advertises the `prompts` and `sampling` capabilities and
  serves three new JSON-RPC methods. `prompts/list` returns the configured prompts (name, description, arguments);
  `prompts/get` returns a prompt's messages with `{{argument}}` placeholders substituted from the supplied arguments
  (unknown prompt → JSON-RPC `INVALID_PARAMS`); `sampling/createMessage` returns a deterministic mocked completion in
  the MCP sampling result shape (`role`, `content`, `model`, `stopReason`), echoing the client's preferred model hint
  and an optional `mockResponse` text override. New `McpPromptRegistry` configures the built-in prompt set.

- **LLM audio content parts and `tool_choice` support (OpenAI codec)**: the OpenAI Chat Completions decoder now
  recognises `input_audio` content parts on the request side (so conversation matchers can assert a message
  contains audio, and in what `format` — e.g. `wav`/`mp3`), mirroring the existing image-part recognition;
  `ParsedMessage` exposes `hasAudio()`, `audioCount()`, and `getAudio()`. A new `toolChoice` field on the
  `completion` response model (`auto` | `none` | `required` | a named tool) is wired through the OpenAI codec so
  that when `toolChoice` is `required` and a tool call is configured, the mocked response's `finish_reason` is
  `tool_calls` (non-streaming and streaming). Absent `toolChoice` leaves the existing finish-reason behaviour
  unchanged.

- **LLM optimisation export**: proxy your agent's LLM calls through MockServer, then export a one-click
  **optimisation brief** (Markdown, pre-framed for any LLM) or structured **JSON bundle**
  (`LlmOptimisationReport`) from captured traffic. Six deterministic optimisation signals detect
  repeated system prompts, large static context resent every turn, deterministic tool calls,
  oversized tool results, output token bloat, and duplicate consecutive calls — each with token
  counts, estimated USD saving, and a concrete recommended lever (prompt caching, retrieval tool,
  direct HTTP/MCP endpoint, `max_tokens` constraint). New **LLM Optimise** dashboard screen
  (in the navigation immediately after **Chaos**), new
  `GET /mockserver/llm/optimisationReport?format=markdown|json` control-plane endpoint, and new
  `export_optimisation_report` MCP tool. Export-only and fully deterministic — MockServer never
  calls an LLM. Secrets (auth headers, API keys, configured body fields) are redacted before export.
  Configured via `mockserver.llmOptimisationMaxCalls` (default 200); body-field redaction reuses
  `mockserver.fixtureBodyRedactFields`. The report analyses both proxied/forwarded and **mocked**
  LLM traffic (recognised by API path shape), so mocked conversations — e.g. `npm run demo` — are
  included, consistent with the dashboard Sessions view. Per-call latency shows the measured
  upstream round-trip (full-stream duration for streaming responses), carried internally and never
  leaked to the client response.

- **Provider-correct LLM chaos error bodies**: LLM chaos error injection now emits the body shape the real
  provider returns — Anthropic `overloaded_error`/`rate_limit_error`, the OpenAI `server_error`/
  `rate_limit_exceeded` envelope, Gemini's Google-API status, Ollama's plain message — so client SDK
  retry/backoff logic can be tested realistically; falls back to the generic body for an unspecified provider.
  An optional `errorKind` on the LLM chaos profile (`OVERLOAD` | `RATE_LIMIT` | `SERVER_ERROR`) lets you
  declare the error intent directly: the active provider's distinct body **and its natural HTTP status** are
  emitted (e.g. `OVERLOAD` → Anthropic 529 `overloaded_error`, OpenAI 503 `server_error`) without having to
  pick the status yourself. It applies to both probabilistic and quota-breach errors, works even when no
  explicit `errorStatus` is set, and an explicit status still overrides the code while keeping the
  provider-correct body. Case-insensitive; an unrecognised value falls back to the existing behaviour.

- **LLM model/pricing catalog refresh**: `org.mockserver.llm.cost.LlmPricing` now recognises current
  model families — Claude Opus 4.5–4.8, Sonnet 4.5/4.6, Haiku 4.5 and Fable 5; OpenAI gpt-4.1 family
  and o3/o4 reasoning models; Gemini 2.5 flash-lite/flash/pro — in addition to the existing entries.
  More-specific prefixes are matched first so e.g. `claude-opus-4-8` resolves to its own tier rather
  than the generic `claude-opus-4`. `gpt-5*` entries are explicitly approximated placeholders (mapped
  to the nearest known tier, flagged in a code comment) so a recognised model is still priced instead
  of dropped — confirm those figures against the provider price list before relying on them.

- **Cached and reasoning token usage fields**: `org.mockserver.model.Usage` gains three optional,
  back-compatible fields — `cachedInputTokens`, `cacheCreationTokens` and `reasoningTokens`. The
  runtime-LLM provider clients decode them from each provider's usage shape (OpenAI
  `prompt_tokens_details.cached_tokens` / `completion_tokens_details.reasoning_tokens`, OpenAI Responses
  `input_tokens_details` / `output_tokens_details`, Anthropic `cache_read_input_tokens` /
  `cache_creation_input_tokens`, Gemini `cachedContentTokenCount` / `thoughtsTokenCount`), and the GenAI
  telemetry spans emit them as `mockserver.gen_ai.usage.{cached_input,cache_creation,reasoning}_tokens`.
  Absent fields (a provider that doesn't report them) leave behaviour unchanged, so cost dashboards can
  now split cached-input and reasoning spend without mis-billing older recordings.

- **Multimodal image request recognition**: the conversation decoders now recognise image content parts
  so a mocked LLM request can match on image presence — OpenAI `image_url`, Anthropic `image`
  (base64 source), and Gemini `inline_data` / `inlineData`. `ParsedMessage` exposes `hasImage()`,
  `imageCount()` and `getImages()` (each `ImagePart` carries the declared media type where the provider
  shape includes it). Request-side only — MockServer recognises images, it does not store the bytes or
  generate image responses.

- LLM mocking API in the Python and Node clients: build completion/chat mocks, tool-use, token usage,
  streaming physics, embeddings, multi-turn conversations and provider failover — producing the same wire
  format as the Java `Llm` builders, bringing LLM mocking to those clients.

- MCP (Model Context Protocol) server-mocking API in the Python and Node clients: define a mock MCP server's
  tools and resources and their responses, mirroring the Java `McpMockBuilder`.

- AMQP 0.9.1 (RabbitMQ) broker mocking in the AsyncAPI module: AsyncAPI specs with `amqp` channel bindings now
  publish their mock messages to a RabbitMQ broker (exchange/routing-key or queue per the binding), alongside
  the existing Kafka and MQTT support. Configure the broker with `asyncAmqpUri` (env `MOCKSERVER_ASYNC_AMQP_URI`).

#### Dashboard UI
- **Dashboard UI (`mockserver-ui`): before→after preview diff when creating or editing a mock.** The
  "Capture as Mock" dialog now has a **Preview diff** tab that renders the expectation JSON that will be
  created (an empty object on the left, the generated mock on the right), so you can see what mock will be
  created from a captured request before confirming. In the Composer's Review step, editing an existing
  expectation now shows a side-by-side diff of the loaded expectation versus the outgoing JSON, so the
  changes your edits will apply are visible before you submit. Both reuse a new read-only Monaco
  `JsonDiffViewer` component (locally bundled — no runtime CDN), consistent with the existing `JsonEditor`.

- **Dashboard UI (`mockserver-ui`): "Set breakpoint" from a log row.** Each log entry that carries a request now
  shows a pause-circle action that pre-fills a new breakpoint matcher from that request's method and path and jumps
  to the Breakpoints view's structured matcher form (method dropdown, path field, phase checkboxes) — so you can go
  from "I saw this request in the log" to "pause it next time" in one click. The prefill is a one-shot store hand-off
  (mirroring the existing edit-expectation and generate-stub hand-offs); an unrecognised HTTP method falls back to
  "(any)" while still applying the path.

- **Dashboard UI: named-example picker for OpenAPI-backed expectations**. The **Import OpenAPI**
  dialog now detects when a pasted inline JSON spec declares multiple named response examples for an
  operation (under the first media type of a response body) and shows a per-operation dropdown to
  choose which one the generated mock should return. The chosen example is sent to the server as the
  `operationsAndResponses` value `{ statusCode, exampleName }` (the field the existing
  `OpenAPIConverter` already honours), so no server change was needed. Because `operationsAndResponses`
  also acts as an operation filter on the server, picking an example for one operation still imports
  **all** operations — the picked one with its example and every other operation with the spec default
  — rather than silently dropping the rest. Operations that declare no `operationId` are preserved too:
  the dialog replicates the server's synthesized `"<METHOD> <path>"` id (e.g. `GET /pets`) so an id-less
  operation survives the import even when another operation pins an example. Leaving every picker on **Default** sends no override at all,
  preserving MockServer's default example selection. URL and YAML specs are sent untouched (no picker),
  and `$ref`-resolved examples are not surfaced since the dialog inspects the raw pasted JSON.

- **Dashboard: Contract Test and Cluster panels**: two new dashboard tabs. **Contract** runs an OpenAPI spec
  (URL or inline) against a live service via `PUT /mockserver/contractTest` and renders the report as a
  pass/fail-per-operation table with per-operation validation errors and a passed/failed summary. **Cluster**
  reads `GET /mockserver/cluster` and shows the state-backend cluster status (clustered flag, node id,
  coordinator, named cluster, and a member list with coordinator/local markers), auto-refreshing with a manual
  refresh; a standalone server reports a single local node.

- **Dashboard: Monaco code editor with live JSON validation for body matchers**: the Composer's request
  body-matcher field is now a real Monaco code editor with syntax highlighting and per-type language modes
  (JSON, XML, GraphQL, plaintext for path/regex expressions). For JSON and JSON-Schema body types it
  validates live as you type — malformed JSON (and, for the JSON-Schema type, an invalid schema document)
  is flagged inline with red squiggles plus a short error summary beneath the editor, before you submit.
  Monaco and its language workers are bundled and served locally by the dashboard (no runtime CDN dependency).

- **Dashboard: duplicate an expectation + priority column and sort**: the Active Expectations panel now shows a
  "Duplicate" action on each row that opens the Composer pre-populated with a copy of the expectation (its `id`
  stripped, so saving creates a brand-new expectation while preserving `priority` and every other field). Each
  row also shows a `P<n>` chip with the expectation's match `priority` (defaults to `0`; higher wins), and a
  header "Priority" toggle re-orders the list by priority descending so the match order is visible at a glance.

- **Dashboard: scenario state-machine diagram**: the Scenario State Machine panel now renders the selected
  scenario's states and transitions as a live Mermaid `stateDiagram-v2`, with the current state highlighted.
  The graph is built from what the panel observes — states it has seen and transitions it has watched happen
  (selecting a scenario, setting a state, scheduling a timed `current → next` transition, or triggering one) —
  so the state machine takes shape as you drive it. State names are sanitised so they cannot break the diagram
  (or inject markup), and if Mermaid fails to render the panel falls back to showing the diagram source.

- **Dashboard UI — usability, responsiveness and new surfaces**: a broad pass over the dashboard from
  an adversarial review.
  - **Delete and edit a single mock** from the dashboard's Active Expectations panel (previously the
    only removal was "clear all expectations"): per-row Delete (confirmed) and Edit (loads the mock
    into the Composer).
  - **Auto-refreshing live panels**: Drift, Breakpoints, AsyncAPI and MCP panels now refresh
    automatically on an interval instead of only on a manual button (the manual Refresh is kept as a
    force-refresh), so they stay live like the Metrics and Chaos panels.
  - **Quick mock mode** in the Composer: a Quick/Advanced toggle defaults to a minimal
    method + path + status + body form for the common case, with Advanced revealing the full
    matcher/action machinery; plus plain-language tooltips for Times/TTL/Priority/JSON-match-type/`!`
    negation and a "View on dashboard / Add another" next step after registering.
  - **SAML provider mocking** in the dashboard (the OIDC parallel that was missing): a "Mock SAML
    provider…" tool backed by `PUT /mockserver/saml`.
  - **Responsive layout**: the dashboard now works on tablet and mobile — the 2×2 grid collapses to a
    single scrollable column, the navigation collapses to a menu when it doesn't fit, the
    master/detail traffic view stacks, and large dialogs go full-screen on small screens.
  - **Keyboard-shortcuts help dialog** so the ⌘K / ⌘L / Esc shortcuts are discoverable.
  - The agent-run graph (LLM/agent debugging) now renders as an **actual diagram** instead of showing
    raw Mermaid source text (Mermaid is lazily loaded so it stays out of the initial bundle).
  - **gRPC services view**: a new dashboard tab lists the loaded gRPC services and their methods
    (input/output types, streaming kind) with per-service health, auto-refreshing live.
  - **Compare against baseline**: a new tool runs `PUT /mockserver/baseline/compare` and shows the
    added / removed / changed expectations against a baseline.
  - **Adaptive navigation**: the top navigation now shows as many view tabs inline as fit and moves the
    rest into a "More" menu only when space runs out — all tabs are inline at full width, tabs flow into
    "More" as the window narrows, and on very narrow screens it collapses to the active page plus a menu.
  - **Resizable panels**: the four Dashboard panels can be resized by dragging the column and row
    dividers, and the Traffic inspector's request-detail pane width is drag-adjustable; the chosen sizes
    are remembered. The Mocks form now fills the full width like every other tab.
  - **Clearer controls**: the Mocks "Quick mock / Advanced" switch is now an obvious cyan toggle, and
    the Service Chaos switch toggles no longer hug the left edge.
  - **Inspect and re-run a chaos experiment**: while an experiment is running, the Chaos → Experiments
    panel now lists its stages (duration, host, faults) with the active stage highlighted, and an
    "Edit & restart" button loads the running definition into the editor so it can be tweaked and
    re-started (or restarted after stopping).
  - **Agent-run graph fix**: the Sessions agent-run diagram no longer falls back to "showing the Mermaid
    source" when a tool name or message contains special characters (e.g. parentheses) — tool-call
    labels are now quoted so Mermaid always renders. Also fixed cramped spacing above filled experiment
    stage fields.
  - **Clearer unscoped Sessions lane**: the `<unscoped>` lane (a catch-all of unrelated LLM requests
    across providers) no longer shows a single correlated agent-run graph that couldn't represent it,
    and its Conversation is now labelled "Conversation (latest of N)" with a note that it groups N
    requests across M providers and only the most recent is shown (expand a request to see the others).
  - **Per-session agent-run graph**: the Sessions agent-run diagram is now built from only the selected
    session's requests instead of every request on that path, so two sessions hitting the same endpoint
    no longer show the same graph. The `explain_agent_run` MCP tool gains optional `isolationType` /
    `isolationKey` / `isolationValue` parameters to scope a run to one conversation (backward
    compatible — omitting them keeps the previous behaviour).
  - **Consistency pass**: control-plane error messages are humanised everywhere (a short message with the
    raw detail behind a "Details" toggle), form fields no longer overflow on small screens, type sizes
    come from a single typography scale, and "Capture as mock" can hand a captured request straight to
    the Composer to refine.

- **Timestamps on dashboard log entries**: each entry in the dashboard's Log Messages list now shows the
  time it was logged — a compact local time inline, with the full `yyyy-MM-dd HH:mm:ss.SSS` timestamp on
  hover — so events can be correlated and ordering/latency seen at a glance. The timestamp was already
  captured server-side; the dashboard WebSocket serializer now sends it and the UI renders it (rows without
  a timestamp are unchanged).

- Dashboard request-log filtering gains regex matching on method/path and named, saved filter presets
  (persisted in the browser) for quickly switching between common filters.

- Dashboard "Why didn't this match?" now offers a side-by-side visual diff (request vs the closest
  expectation's matcher) alongside the existing text reasons.

- Dashboard matcher test playground: enter a sample request and see whether a candidate expectation would
  match it (a browser-side preview) before registering — reached from a new toolbar button.

- Dashboard composer can now author `capture` rules on an expectation (extract a request value via
  jsonPath/xpath/header/query/cookie/pathParameter into scenario state).

#### IDE extensions (VS Code & JetBrains)
- **VS Code & JetBrains extensions: trace-correlation view — open a logged exchange's OpenTelemetry trace in
  your trace backend.** Complementing the existing *Find Requests by Trace* (trace id → received requests), a new
  **View Trace in Backend** command/action goes the other way: enter a W3C trace id (32 hex) or full `traceparent`
  value and it opens the correlated trace in your trace backend (Jaeger / Tempo / Grafana) in the browser. The
  backend URL is a configurable template with a `{traceId}` placeholder — `mockserver.traceUrlTemplate` in VS Code
  (e.g. `http://localhost:16686/trace/{traceId}`), a **Trace backend URL** field under *Settings | Tools |
  MockServer* in JetBrains. When no template is configured it degrades gracefully: the trace id is copied to the
  clipboard and the user is pointed at the setting. Both implementations share a pure, unit-tested `buildTraceUrl`
  helper (placeholder substitution with URL-encoding, blank-template → null).

- **JetBrains plugin (`mockserver-jetbrains`): LLM authoring + agent-run call graph (parity with the VS Code
  extension).** A new **LLM** tool window adds an *httpLlmResponse* expectation builder form
  (provider/model/path/completion/usage/stream) that opens the result in an editor or loads it straight into the
  running server, plus an **agent-run call graph** view that fetches the graph through the MCP `explain_agent_run`
  tool (`POST /mockserver/mcp`) and renders it as Mermaid in the bundled JCEF (Chromium), falling back to the raw
  Mermaid source when JCEF or the CDN is unavailable. A completion contributor offers curated provider/model/field
  suggestions inside `httpLlmResponse` blocks of `*.mockserver.json` files (augmenting, not replacing, the bundled
  JSON Schema completion).

- **JetBrains plugin (`mockserver-jetbrains`): contract/resiliency test runner.** A new **Run Contract Test**
  action — in the Tools menu and the editor / project-view context menus on a spec file — runs an OpenAPI
  contract test of a live service against the active OpenAPI/Swagger spec via `PUT /mockserver/contractTest` and
  opens a per-operation pass/fail report (with validation errors) in a new editor tab, mirroring the VS Code
  extension's `mockserver.contractTest` command.

- **JetBrains plugin (`mockserver-jetbrains`): stream-frame breakpoint editing.** The in-IDE HTTP Debugger now
  handles paused stream frames (`PausedStreamFrameDTO`, RESPONSE_STREAM / INBOUND_STREAM): they appear in a new
  **Live Streams** list where each can be Continued, Modified (with a new Base64 payload), or Dropped, replying
  over the frozen callback WebSocket with a `StreamFrameDecisionDTO` — completing parity with the dashboard and
  the VS Code extension's stream-frame contract.

- **Editor extensions: in-IDE breakpoint debugger and power-user panels**: the VS Code and JetBrains extensions
  add a debugger over the breakpoint callback WebSocket (pause/inspect/Modify/Continue/Abort on requests and
  responses flowing through MockServer), drift quick-fixes, code-aware run gutters, and chaos/contract panels.

- **Editor extensions: author/verify/record against a running server**: the VS Code and JetBrains extensions add
  scratch-request match analysis (shows whether a request matched and the nearest-miss diff), Verify/Delete
  actions on `*.mockserver.json` expectations, generate-stubs-from-OpenAPI, and record-to-code (write recorded
  expectations into a workspace file as JSON or DSL).

- **VS Code extension: in-IDE HTTP debugger + code-aware integration + power-user panels**: the VS Code extension
  adds an in-IDE breakpoint debugger over the MockServer callback WebSocket — register a request matcher, receive
  paused exchanges, and **Continue / Modify / Abort** (Abort is REQUEST-phase only; RESPONSE phase is
  Continue/Modify), plus per-frame stream editing (Continue/Modify/Drop/Inject/Close). Breakpoints fire only on
  traffic flowing **through** MockServer (proxied/forwarded/matched-mock). Also: a drift quick-fix lightbulb
  ("update stub to match upstream") that swaps the stub's value for the live upstream value; run/inspect gutter
  CodeLens on `new MockServerClient(...)`, `@MockServerSettings`, and Testcontainers `MockServerContainer`
  usages; `httpLlmResponse` authoring completion; an agent-run call-graph rendered as Mermaid; a chaos status /
  stop panel; and an OpenAPI contract-test runner with a per-operation pass/fail report. Everything talks only to
  the configured local MockServer — nothing phones home.

- VS Code extension adds **MockServer: Find Requests by Trace** — enter a W3C trace id (32 hex) or a full
  `traceparent` header value and the extension opens every request the server received that belongs to that
  distributed trace, so you can see every hop of one trace in a new JSON editor tab.

- VS Code and JetBrains extensions now validate MockServer expectation files. Name a file `*.mockserver.json`
  (or `*.mockserver.jsonc`) and the editor gives inline schema validation, autocompletion, and hover
  documentation for expectations — driven by the same schema MockServer itself validates against, generated
  from `mockserver-core` (`scripts/generate-editor-expectation-schema.mjs`). A single expectation or an array
  of expectations (initialization JSON) is accepted.

- JetBrains plugin adds a **Settings | Tools | MockServer** panel to configure the Docker image, container
  name, and port.

- VS Code extension turns expectation files into a live control surface: CodeLens actions at the top of any
  `*.mockserver.json` / `*.mockserver.jsonc` file **Load into running MockServer** (`PUT /mockserver/expectation`,
  comments and trailing commas tolerated) and **Diff against live** (side-by-side diff against the server's
  active expectations), both reachable from the Command Palette.

- VS Code extension can record real traffic into code: **MockServer: Save Recorded Expectations** opens the
  expectations the server recorded from proxied/forwarded traffic
  (`PUT /mockserver/retrieve?type=recorded_expectations`) as JSON or Java DSL in a new editor tab.

- VS Code extension can generate expectations from an OpenAPI/Swagger spec: **MockServer: Generate
  Expectations From OpenAPI Spec** sends the active editor's spec (JSON or YAML) to the running server
  (`PUT /mockserver/openapi`) and opens the generated expectations in a new tab.

- VS Code extension can show a mock-drift report: **MockServer: Show Drift Report** fetches the latest
  drift records (`GET /mockserver/drift`) — how real upstream responses have drifted from your stub
  expectations — and opens a readable text summary in a new tab (one line per drift: type, field,
  expected vs actual value, confidence, and the affected expectation).

- JetBrains plugin brings the same mock-drift report to the **Tools > MockServer** menu: **Show Drift
  Report** fetches the latest drift records (`GET /mockserver/drift`) and opens a readable text summary in
  a new tab (one line per drift: type, field, expected vs actual value, confidence, and the affected
  expectation). The HTTP call runs off the UI thread.

- JetBrains plugin adds distributed-trace correlation: **Find Requests by Trace** (menu and tool window)
  prompts for a W3C trace id (32 hex) or a full `traceparent` header value, retrieves the requests
  MockServer has received (`PUT /mockserver/retrieve?type=requests`), filters them down to those carrying a
  `traceparent` header with that trace id, and opens the matching requests as JSON in a new tab. The HTTP
  call runs off the UI thread.

- JetBrains plugin can author WASM custom-rule modules: **Upload WASM Module** picks a compiled `.wasm`
  file with the IDE file chooser, confirms a module name, and uploads its raw bytes
  (`PUT /mockserver/wasm/modules?name=<name>`, `application/octet-stream`) to the running server so it can
  be referenced by name as a WASM body matcher; **List WASM Modules** fetches the registered modules
  (`GET /mockserver/wasm/modules`) and opens the JSON list of names in a new tab. The upload reports
  clearly when the server has WASM support disabled, and both HTTP calls run off the UI thread.

- VS Code extension can surface mock drift as **inline diagnostics** on the open expectation file:
  **MockServer: Show Drift as Diagnostics** fetches the latest drift records (`GET /mockserver/drift`),
  matches each to its expectation by `id`, and shows it as a diagnostic on that expectation's line
  (unmatched drift attaches to the first line) — so "the real upstream differs from this stub" appears
  right in the `*.mockserver.json` file. Status-code drift, a removed schema field, or a fully-confident
  drift shows as an error; a newly added schema field shows as a warning; everything else as information.
  Re-running refreshes the diagnostics and a clean result clears them.

- VS Code extension can author WASM custom-rule modules: **MockServer: Upload WASM Module** picks a
  compiled `.wasm` file and uploads it to the running server (`PUT /mockserver/wasm/modules?name=<name>`,
  raw bytes) so it can be referenced by name in an expectation body matcher
  (`{ "type": "WASM", "moduleName": "<name>" }`), and **MockServer: List WASM Modules** opens the registered
  module names (`GET /mockserver/wasm/modules`) in a new JSON tab. When WASM support is disabled on the
  server the "WASM support is disabled" message is surfaced verbatim.

- JetBrains plugin brings the same server-interaction actions to the **Tools > MockServer** menu:
  **Load Expectations Into Running Server** (`PUT /mockserver/expectation`, a single expectation or an array),
  **Save Recorded Expectations** (`PUT /mockserver/retrieve?type=recorded_expectations`, opens the
  recorded expectations in a new JSON tab), and **Generate Expectations From OpenAPI Spec**
  (`PUT /mockserver/openapi`, sends the active editor's JSON or YAML spec and opens the generated
  expectations in a new JSON tab). All HTTP calls run off the UI thread.

- JetBrains plugin can show the live MockServer dashboard **inside the IDE** via **Open MockServer Dashboard
  in IDE** (and a dedicated right-hand tool window), embedding it with the bundled JCEF (Chromium) engine and
  Reload / Open-in-Browser controls. When JCEF is unavailable in the IDE or runtime it falls back gracefully
  to opening the external browser.

- VS Code extension can show the live dashboard inside VS Code: **MockServer: Open Dashboard** reveals the
  running server's dashboard docked in the bottom panel (a webview that frames
  `http://localhost:<port>/mockserver/dashboard`) on the configured port, alongside an external-browser option.

- VS Code extension can send an ad-hoc test request without leaving the editor: name a file
  `*.mockserver-request.json` (`{ "method", "path", "headers"?, "body"? }`) and the **MockServer: Send
  Test Request** command (or the **Send to MockServer** CodeLens) fires it at the running server on the
  configured port and opens the response (`HTTP <status>` plus the body, pretty-printed when JSON) in a
  new tab.

- JetBrains plugin brings the same ad-hoc request feature to the **Tools > MockServer** menu:
  **Send Test Request** parses the active editor's JSON request spec
  (`{ "method", "path", "headers"?, "body"? }`), fires it at the running server on the configured port,
  and opens the response (`HTTP <status>` plus the body, pretty-printed when JSON) in a new editor tab.
  The HTTP call runs off the UI thread.

- JetBrains plugin bottom **MockServer** tool window is now a one-click launcher for the full action set
  (previously only Open Dashboard + Start Docker): buttons are grouped into *Server* (Open Dashboard in IDE,
  Open Dashboard in Browser, Start (Docker), Reset) and *Editor actions* (Load Expectations, Save Recorded,
  Generate From OpenAPI, Send Test Request, Show Drift Report), so everything is reachable without opening
  the **Tools > MockServer** menu. The editor actions reuse the registered actions verbatim.

- JetBrains plugin adds **Reset MockServer** (menu and tool window): clears all expectations and recorded
  logs on the running server (`PUT /mockserver/reset`) after a confirmation prompt; the HTTP call runs off
  the UI thread.

- VS Code extension adds two quick utility commands: **MockServer: View Request Log** opens the log of
  requests the server has received (`PUT /mockserver/retrieve?type=requests`) in a new JSON tab (saying so
  rather than opening an empty tab when no requests have been recorded), and **MockServer: Reset (Clear
  Expectations & Logs)** clears all expectations and the request log (`PUT /mockserver/reset`) after a modal
  confirmation.

#### Client libraries
- **Client capability matrix and per-language test fixtures** (docs). New page `client_compatibility.html` in the Getting Started section: an 8x8 matrix of feature coverage across all clients (expectations, verify, OpenAPI, LLM mock builder, MCP mock builder, test fixture/auto-cleanup, retrieveAsCode, Testcontainers) verified against client source, plus idiomatic test-fixture snippets for all eight languages (Java JUnit 5 extension, Node `Symbol.asyncDispose`, Python pytest `conftest.py`, Ruby RSpec shared context, Go `MockServerT`/`t.Cleanup`, .NET `MockServerFixture`/`IAsyncLifetime`, Rust manual reset, PHP `MockServerTestTrait`).

- **Client test-framework fixtures and idiomatic auto-cleanup** across the Go, Node/JS, Ruby, .NET and PHP
  clients. Each now ships an ergonomic helper that resets the server between tests so recorded requests,
  expectations and logs do not leak — wrapping the existing `reset()` call, no server change:
  - **Go** (`mockserver-client-go`): `MockServerT(t, client)` / `NewMockServerT(t, host, port)` register a
    `t.Cleanup` that calls `Reset()` when the test (and its subtests) finish.
  - **Node/JS** (`mockserver-client-node`): the client now supports TC39 explicit resource management via
    `Symbol.asyncDispose` (and `Symbol.dispose`), so `await using client = mockServerClient(...)` resets the
    server on scope exit; symbols are guarded for older runtimes.
  - **Ruby** (`mockserver-client-ruby`): `require 'mockserver/rspec'` registers an RSpec shared context
    (tag `:mockserver`) that provides a fresh, reset `mockserver` client per example.
  - **.NET** (`mockserver-client-dotnet`): `MockServerFixture`, a reusable xUnit `IAsyncLifetime` fixture that
    creates a `MockServerClient` and resets before/after each test (usable as a base class or `IClassFixture`).
  - **PHP** (`mockserver-client-php`): `MockServer\Testing\MockServerTestTrait`, a PHPUnit trait exposing
    `setUpMockServer()` / `tearDownMockServer()` helpers that reset the server around each test.
  - **Rust** (`mockserver-client-rust`): already auto-cleans the *spawned* server via `impl Drop for
    ServerHandle` (kills the launched process on scope exit) — left as-is.

- **Non-Java clients can now retrieve expectations as generated MockServer SDK setup code**: the Go, Node, Python,
  Ruby, .NET, PHP and Rust clients gain `retrieveExpectationsAsCode(format)` / `retrieveRecordedExpectationsAsCode(format)`
  (idiomatic naming per client) that call the existing `PUT /mockserver/retrieve?format=JAVA|JAVASCRIPT|PYTHON|GO|CSHARP|RUBY|RUST|PHP`
  endpoint and return the generated builder code (the `when(...).respond(...)` setup that recreates the active or
  recorded expectations) as a string. Previously only the Java client and the dashboard UI exposed code retrieval;
  the other clients hard-coded `format=JSON`. Server behaviour is unchanged — this only plumbs the existing
  capability into the client libraries.

- **Node client: advanced response builders**: the Node.js client now exposes `respondWithSse`,
  `respondWithWebSocket`, `respondWithDns`, `respondWithBinary`, and `respondWithGrpcStream` (with TypeScript
  declarations) so SSE, WebSocket, DNS, raw-binary, and gRPC-stream expectations can be registered from Node,
  reaching parity with the Java and Python clients (serializer-only additions; the server already accepted these payloads).

- **Rust client: advanced response-builder naming parity**: the Rust client's fluent `when(...)` chain now
  exposes `respond_with_sse`, `respond_with_web_socket`, `respond_with_dns`, `respond_with_binary`, and
  `respond_with_grpc_stream` aliases so SSE, WebSocket, DNS, raw-binary, and gRPC-stream expectations can be
  registered with method names aligned to the other clients (each delegates to the existing idiomatic
  `respond_*` methods; serializer payloads are unchanged).

- **Expectation code generation in every client language**: the retrieve endpoint can now produce
  copy-paste-ready client code from recorded or active expectations in **Java, JavaScript, Python, Go,
  C#, Ruby, Rust and PHP**, alongside the existing Java output. Call
  `PUT /mockserver/retrieve?type=RECORDED_EXPECTATIONS&format=<language>` (and likewise for
  `type=ACTIVE_EXPECTATIONS`) with `format` one of `javascript`, `python`, `go`, `csharp`, `ruby`,
  `rust` or `php` to get one client call per expectation. Unlike the Java DSL (`format=java`), these
  clients accept an expectation as a JSON object, so the generated code embeds the expectation's
  existing JSON serialization (byte-identical to `format=json`, so it round-trips through the real
  clients) in the language's real upsert call rather than reconstructing a typed builder:
  - **JavaScript** — `mockServerClient("localhost", 1080).mockAnyResponse(<JSON>)` (Content-Type `application/javascript`)
  - **Python** — `client.upsert(Expectation.from_dict(json.loads("""<JSON>""")))` (Content-Type `text/x-python`)
  - **Go** — `json.Unmarshal([]byte(`​`<JSON>`​`), &e); client.Upsert(e)` using the `mockserver-client-go` package (Content-Type `text/x-go`)
  - **C#** — `client.Upsert(JsonSerializer.Deserialize<Expectation>(@"<JSON>", jsonOptions))` using `MockServer.Client` (Content-Type `text/x-csharp`)
  - **Ruby** — `client.upsert(MockServer::Expectation.from_hash(JSON.parse(<<JSON ... JSON)))` using the `mockserver-client` gem (Content-Type `text/x-ruby`)
  - **Rust** — `client.upsert(&[serde_json::from_str::<Expectation>(r#"<JSON>"#)?])?` using the `mockserver-client` crate (Content-Type `text/x-rust`)
  - **PHP** — `$client->upsertExpectation(Expectation::fromArray(json_decode(<<<'JSON' ... JSON, true)))` using `mock-server/mockserver-client` (Content-Type `application/x-httpd-php`); a new `Expectation::fromArray()` factory on the PHP client makes the generated code round-trip.

  The embedded JSON is escaped correctly for each language's string literal (Go raw/interpreted
  strings, C# verbatim strings, Ruby/PHP heredoc-nowdoc, Rust hash-bumped raw strings) so even hostile
  values (quotes, backslashes, newlines, the language's own raw-string terminator) copy-paste cleanly.
  The dashboard's Library → Export tab now offers all eight mock languages for the expectations and
  recorded-expectations scopes, plus a **"Verification code"** option for the recorded-requests scope
  in Java, JavaScript, Python, Go, C#, Ruby and Rust: it retrieves the recorded requests and generates
  one `verify(...)` call per request entirely in the browser. Each format has a "Copy as code" button.
  The `Format` enum gains `GO`, `CSHARP`, `RUBY`, `RUST` and `PHP` alongside `JAVASCRIPT`/`PYTHON`;
  `java`, `json`, and `log_entries` are unchanged and existing formats are byte-identical.

- Go client: SSE, WebSocket, DNS, binary, and gRPC-stream response builders, OpenAPI import, and
  `VerifyZeroInteractions`, moving it toward feature parity with the Java/Node/Python/Ruby clients.

- Rust client: SSE, WebSocket, DNS, binary, and gRPC-stream response builders, `openapi()` import, and
  `verify_zero_interactions()`, moving it toward feature parity with the Java/Node/Python/Ruby clients.

- .NET client: SSE, WebSocket, DNS, binary, and gRPC-stream response builders, OpenAPI import, and
  `VerifyZeroInteractions` (sync + async), moving it toward feature parity with the Java/Node/Python/Ruby clients.

- PHP client parity: gRPC descriptor management, SSE/WebSocket/DNS/binary/gRPC-stream response builders,
  OpenAPI import, and `verifyZeroInteractions`.

#### Request matching & response generation
- **Lightweight per-expectation hit-count response branching** (`mockserver-core`). A single expectation can now
  "respond differently after the Nth call" without a full scenario, via a new `SWITCH` response mode plus an
  optional `switchAfter` threshold. With an index-aligned `httpResponses` list, `withResponseMode(ResponseMode.SWITCH)`
  and `withSwitchAfter(N)` (or the `responseMode: "SWITCH"` / `switchAfter` expectation-JSON fields), the expectation
  serves the first response for its first `N` matches, then advances one index for every further block of `N`
  matches, clamping at the last response. The common two-response case therefore serves the first response for
  `N` calls and the second for every call after — ideal for modelling "succeed, then start failing" (or the
  reverse) for a single endpoint. This reuses the existing sequential/weighted response machinery and is fully
  additive: `switchAfter` is ignored outside `SWITCH` mode, an unset threshold defaults to `1` (advance each
  call), and an expectation without `SWITCH` behaves byte-for-byte as before. For complex multi-endpoint flows a
  full scenario (`scenarioName` / `scenarioState`) is still the right tool; `SWITCH` is the minimal option for a
  single expectation.
- **Generate a schema-valid response body from an inline JSON Schema** (`mockserver-core`). An HTTP response
  can now carry a plain inline [JSON Schema](https://json-schema.org) (not a full OpenAPI document) via a new
  `generateFromSchema` response field (`HttpResponse.withGenerateFromSchema(...)` in Java, or the
  `generateFromSchema` expectation-JSON field). At response time MockServer synthesizes a schema-valid JSON body
  from it, reusing the same example-generation engine used for OpenAPI responses, so the generated body honours
  `type`, `required`, `enum`, `default`, arrays and nested objects. This is additive and fully back-compatible:
  it only fires when the response has no explicit body (an explicit `body` always wins), and a schema that cannot
  be parsed leaves the response body unset (logged at WARN) rather than failing the request. Inline-schema
  `$ref` resolution follows the OpenAPI-spec trust model (control-plane, remote refs resolved), not the
  `jsonSchemaAllowRemoteRefs` matching-path model. Lets you return realistic, type-correct mock data from a bare
  schema without hand-authoring response JSON or attaching a whole OpenAPI spec.

- **Regex path capture groups exposed to response/forward templates** (`mockserver-core`). When an
  expectation matches a request path with a regular expression containing capture groups, the captured
  values are now available to Mustache, Velocity and JavaScript templates via two new request-model
  fields: `request.pathGroups` (the numbered groups, 1&#8209;based aligned with `java.util.regex` group
  numbering — index `0` is the whole match, index `1` the first capture group) and
  `request.namedPathGroups` (Java named groups `(?<name>...)`, keyed by name). For example a path matcher
  `"/users/(\\d+)/orders/(\\w+)"` lets a template echo `{{ request.pathGroups.1 }}` (Mustache),
  `$!request.pathGroups[1]` (Velocity) or `request.pathGroups[1]` (JavaScript). The groups are populated
  on a successful data-plane match only and are additive and fully back-compatible: existing template
  fields are unchanged, both new fields are empty when the matched path had no capture groups, and group
  extraction is best-effort (it never throws and never affects which expectation matches).

- **Request-driven (template) response delay**: a `delay` may carry a `template`+`templateType` (`VELOCITY`/
  `MUSTACHE`) rendered against the request to compute the delay in milliseconds (e.g. larger payloads respond
  slower). Static and statistical-distribution delays are unchanged; an invalid/blank render falls back safely.

- **Conditional (if-then-else) request matcher**: a new `conditionalRequestDefinition` (`if`/`then`/`else`
  request matchers) matches as: if the `if` guard matches require `then`, otherwise require `else` (absent
  `else` ⇒ match when the guard is false). Existing AND-only matching is unchanged.

- **Accept-header content-negotiation matching**: a header matcher value may use an opt-in `accept:<media-type>`
  directive that matches when the request's `Accept` header finds the media type acceptable per RFC 7231 —
  honouring q-weights (`q=0` excludes), `type/*` and `*/*` wildcards, and specificity/preference ordering.
  Existing exact/regex header matching is unchanged when the directive is absent.

- **Conditional and chainable response modifiers**: an `httpOverrideForwardedRequest` / forward-template response
  modifier may now carry a `condition` (gate on exact `statusCode`, `statusCodeRange` class range,
  `responseHasHeader`, or `requestHasHeader`) and/or an ordered `modifiers` chain where each modifier sees the
  previous one's output. Legacy single-modifier expectations behave byte-for-byte identically.

- **Deterministic fuzzy / similarity body matcher**: a request body matcher `FuzzyBody`
  (`{"type":"FUZZY","fuzzy":"...","threshold":0.8,"ignoreCase":false}`, DSL `FuzzyBody.fuzzy(...)`) matches when
  the request body is similar enough to an expected string by a deterministic Jaro-Winkler ratio at or above a
  configurable threshold (default `0.8`) — a non-LLM alternative to exact/regex body matching.

- **Response templates: `html`, `csv`, `xpath`, and `yaml` helper functions**: response templates (Velocity /
  JavaScript / Mustache) gain four new built-in helpers — `html.escape`/`html.unescape`, `csv.parse`/`csv.row`,
  `xpath.evaluate` (XXE-hardened), and `yaml.toJson`/`yaml.parse` — for shaping response bodies from request
  data without custom code.

- **Case-sensitive matching opt-in `matchExactCase`** (`MOCKSERVER_MATCH_EXACT_CASE`, default false): when
  enabled, request matching of the method, path and regex string body becomes case-sensitive (exact case)
  instead of the historical case-insensitive behaviour, so an expectation for `/Path` no longer matches a
  request to `/path`. (Exact string bodies were already matched case-sensitively and are unaffected.) Header
  names and values, cookie names and values, and query string parameters always remain case-insensitive
  regardless of this setting. The default (false) is byte-for-byte the existing behaviour.

- **Weighted/probabilistic response selection**: a new `WEIGHTED` `ResponseMode` selects among an
  expectation's multiple `httpResponses` by relative weight (via the index-aligned `responseWeights` list,
  e.g. `[90, 10]` for a 90%/10% split). Missing or non-positive weights default to 1; a non-positive total
  falls back to uniform selection. Weights round-trip in expectation JSON and Java code generation.

- **JSONPath / XPath request-body extraction in Velocity and JavaScript response templates**: the Velocity
  and JavaScript template engines now expose the same `jsonPath` / `xPath` request-body extraction the
  Mustache engine already had, so a field can be pulled out of the request body without hand-parsing it.
  In Velocity use `$jsonPath.find("$.store.book[0].title")` and `$xPath.find("/element/key")`; in
  JavaScript call `jsonPath('$.store.book[0].title')` and `xPath('/element/key')`. Both operate on the
  request body, share the exact same JSONPath/XPath libraries and error handling as Mustache, and a missing
  path resolves to an empty value (logged, never thrown) just as it does for Mustache templates.

- **Default response headers**: a new `defaultResponseHeaders` setting stamps one or more headers onto every
  response MockServer returns (mock, forwarded and proxied), so organisation-wide headers — a `Server`
  banner, a build or trace id, custom org headers — can be set once instead of on every expectation. The
  value is a pipe-separated list of `name=value` pairs, e.g.
  `-Dmockserver.defaultResponseHeaders="Server=MockServer|X-Trace-Id=abc123"` (also
  `MOCKSERVER_DEFAULT_RESPONSE_HEADERS` / `mockserver.defaultResponseHeaders` in a properties file). Values
  may contain commas and `=`; headers are applied **add-if-absent**, so a header already set on the response
  (including CORS headers) is never overwritten. Default empty = no change.

- **Match and verify by negotiated protocol** (HTTP/1.1, HTTP/2, HTTP/3): expectations can now match,
  and recorded requests can be verified, on the protocol a request actually arrived over. Use
  `request().withProtocol("HTTP_2")` (or `Protocol.HTTP_3`, etc.) on an expectation to only match
  requests negotiated over that protocol, and the same on `verify(...)` to assert how a recorded
  request arrived. The `protocol` enum gains a new `HTTP_3` value (experimental) alongside the
  existing `HTTP_1_1` and `HTTP_2`; requests arriving over QUIC/HTTP-3 are now tagged
  `protocol = HTTP_3` (the `h3` ALPN identifier is server-trusted, so the value cannot be spoofed by
  a header). The protocol now also round-trips through retrieved/recorded requests — previously the
  pretty-printed retrieval format omitted it — so `retrieveRecordedRequests(...)` carries the protocol
  for both HTTP/2 and HTTP/3. Fully backward compatible: protocol is optional and an expectation that
  does not specify a protocol still matches a request regardless of the protocol it arrived over.

- **HTTP response trailers** (trailing headers): a response can now carry trailing headers via
  `httpResponse().withTrailers(...)` / `withTrailer(name, values...)` (mirroring the existing header
  builder), serialised in JSON as a `trailers` object alongside `headers`. MockServer emits them as
  protocol-appropriate trailing headers — for HTTP/1.1 the response is sent chunked with an automatic
  `Trailer` header announcing the field names and a trailing-header block after the final chunk (an
  explicit `Content-Length` is dropped, since trailers and a fixed length are mutually exclusive); for
  HTTP/2 and HTTP/3 they become a trailing HEADERS frame. gRPC responses are unaffected: the gRPC layer
  builds its own trailing HEADERS frame from `grpc-status`/`grpc-message` and does not read the general
  `trailers` field, so general trailers are not emitted on gRPC responses. Fully backward compatible — a
  response with no trailers is byte-for-byte identical to before.

- Expectation **namespacing / multi-tenancy**: an optional `namespace` (tenant) field on each expectation lets
  multiple teams or test-suites share one MockServer instance without their expectations colliding. A request
  scopes matching to a namespace via a configurable header (`matchNamespaceHeader`, default
  `X-MockServer-Namespace`; env `MOCKSERVER_MATCH_NAMESPACE_HEADER`): a request in namespace `T` matches
  expectations whose `namespace` is `T` plus all global (no-namespace) expectations, and never another
  tenant's. A request with no namespace header matches only global expectations (true isolation by default).
  `PUT /mockserver/clear?namespace=T` and `PUT /mockserver/retrieve?type=active_expectations&namespace=T` are
  scoped to a single tenant. The Java client adds `clearByNamespace(namespace)` and
  `retrieveActiveExpectations(requestDefinition, namespace)` so a CI job can clear or inspect only its own
  namespace on teardown without resorting to raw HTTP. Fully backward compatible — with no namespace ever set,
  behaviour is unchanged.

- Response-template helpers `crypto` (`md5`/`sha1`/`sha256`/`sha512`/`hmacSha256`, lowercase hex) and `regex`
  (`matches`/`replaceAll`/`group`) for hashing/signing and extracting or rewriting values inside templates.

- `multipart/form-data` request-body matching: a new `MultipartBody` matcher matches on individual parts by
  field name/value, filename, and part content-type (regex and negation supported, like form parameters), via
  both the Java DSL and the JSON/REST API. OpenAPI operations with `multipart/form-data` request bodies now
  build field matchers from the schema's required properties instead of being matched on path and method only.

- Numeric comparison operators (`> 60`, `>= 60`, `< 100`, `<= 30`, `== 5`; not-equal via `!== 5`) for matching
  header, cookie, and query-string parameter values, in addition to exact and regex matching.

- Response templates can read and write scenario state via a `scenario` helper (`scenario.get(name)`,
  `scenario.set(name, state)`, `scenario.matches(name, state)`) in Velocity, JavaScript and Mustache — capture a
  value in one request and use it to drive a later response (stateful "scenario" mocking).

- Declarative `capture` rules on an expectation extract a value from the matched request (via jsonPath, xpath,
  header, queryStringParameter, cookie or pathParameter) and store it into scenario state, so a later request's
  response template can read it — enabling auth→resource→confirm journeys without manual scenario triggers.

- The mock OIDC provider now supports the full OAuth2 authorization-code flow: a new `/authorize` endpoint issues
  a single-use code and redirects back with `code` and `state`, and `/token` exchanges it (with PKCE S256/plain
  support) for tokens — completing the interactive flow alongside the existing client-credentials grant.

- SAML 2.0 mock Identity Provider: `PUT /mockserver/saml` stands up a mock IdP (metadata endpoint + SP-initiated
  Web-Browser-SSO POST profile) that returns an XML-DSig-signed SAML assertion with a configurable subject and
  attributes — letting you test SAML SSO integrations without a real IdP. Self-signed signing cert generated by
  default, or supply your own. Inbound SAMLRequest parsing is XXE-hardened.

#### Proxying, forwarding & recording
- **Upstream forward retry policy and per-upstream circuit breaker** (`mockserver-core`). Matched
  FORWARD-class actions can now retry transient upstream failures and fail fast on a dead upstream, both
  **opt-in and off by default** (existing forward behaviour is unchanged). Retry (`forwardProxyRetryCount`,
  `forwardProxyRetryBackoffMillis`) re-issues the upstream call on a connection error or a 502/503/504, but
  only for idempotent methods (GET/HEAD/OPTIONS/PUT/DELETE/TRACE) so a request is never executed twice, with
  a non-blocking linear back-off. The per-upstream circuit breaker (`forwardProxyCircuitBreakerEnabled`,
  `forwardProxyCircuitBreakerFailureThreshold`, `forwardProxyCircuitBreakerWindowMillis`) trips open (fail-fast
  503) after N consecutive failures to a `host:port`, then half-opens after a window to probe recovery. When
  metrics are enabled the number of currently-open upstreams is exported as the new
  `mock_server_upstream_circuit_open` Prometheus gauge. Healthy upstreams are evicted from the per-upstream
  state on success so the breaker's memory stays bounded to currently-degraded upstreams. The breaker state
  resets on `HttpState.reset()`. All five properties have the usual equivalent system-property /
  environment-variable / property-file forms.

- **JSON Patch / JSON Merge Patch on forwarded responses**: a response modifier may carry a `jsonPatch`
  (RFC 6902) and/or `jsonMergePatch` (RFC 7386) applied to a forwarded/proxied JSON response body, so a single
  field of a real upstream response can be changed without replacing the whole body. Non-JSON or absent ⇒ body
  unchanged.

- **JSON Patch / JSON Merge Patch on forwarded response bodies**: an `httpOverrideForwardedRequest` /
  forward-template response modifier may now carry an inline `jsonPatch` (RFC 6902 array of
  `add`/`remove`/`replace`/`move`/`copy`/`test` operations) and/or `jsonMergePatch` (RFC 7386 object) that is
  applied to a forwarded/proxied upstream response body when that body is valid JSON — letting you change one
  field of a real upstream response instead of replacing the whole body. `jsonPatch` runs before
  `jsonMergePatch`. A non-JSON body, a malformed patch, or a failed `test` operation leaves the body unchanged
  (the forward never errors), and an absent patch is byte-for-byte unchanged.

- **One-command record round-trip**: `GET/PUT /mockserver/retrieve?type=RECORDED_EXPECTATIONS&format=...`
  now accepts an optional `forwardUnmatchedTo=<upstream>` parameter. When supplied, the call arms
  record-and-forward of unmatched requests to that upstream for the session — subsequent traffic that
  matches no expectation is forwarded to the upstream and captured — and the same/next retrieve returns
  the recorded expectations (deduplicated and templatized when `deduplicateRecordedExpectations` is on) in
  the requested format (`java`, `javascript`, `python`, `go`, `csharp`, `ruby`, `rust`, `php`, `json`, …).
  This removes the multi-step setup (configure `proxyRemoteHost`/`proxyRemotePort` and
  `attemptToProxyIfNoMatchingExpectation`, run traffic, then retrieve) for the common "proxy a real
  upstream → get ready-to-use expectations/code" flow. The upstream accepts a bare `host`, `host:port`, or
  full URL (`http://host:port` / `https://host:port`, default ports 80/443) and is SSRF-validated against
  the same `forwardProxyBlockPrivateNetworks` policy as the forward and replay paths **before** any state is
  mutated or any connection is made (a blocked upstream returns `403` and leaves configuration untouched; a
  malformed upstream returns `400`). Recording remains traffic-driven — the call only arms recording, it
  does not synthesise traffic.

- **Upstream connection pooling `forwardConnectionPoolEnabled`** (`MOCKSERVER_FORWARD_CONNECTION_POOL_ENABLED`,
  default true): idle keep-alive HTTP/1.1 upstream connections are pooled (keyed by host, port and
  scheme) and reused for subsequent forwarded/proxied requests to the same upstream, eliminating repeated TCP and
  TLS handshakes for proxy-heavy workloads. The pool degrades gracefully — surplus connections beyond
  `forwardConnectionPoolMaxIdlePerKey` (default 8) are closed rather than blocking, idle connections are evicted
  after `forwardConnectionPoolIdleTimeoutMillis` (default 30000ms), and connections the upstream closed or that
  returned `Connection: close` are never reused. Only plain HTTP/1.1 keep-alive connections are pooled; HTTP/2,
  HTTP/3, binary forwarding, streaming responses and proxy-tunnelled connections are never pooled. Safe on by
  default (see the Forwarding & proxying note below for the disjoint forward event-loop group + clean-channel
  gate); set `forwardConnectionPoolEnabled=false` to restore the historical fresh-connection-per-request behaviour.

- **Cached forward-proxy PEM parsing**: forward-proxy private key and certificate-chain PEM material is now parsed
  once and cached by its configuration value, so an unchanged forward-proxy key/chain is not re-parsed on every
  client TLS context (re)build.

- **Redact secrets in recorded expectations**: a new opt-in setting masks sensitive request headers
  (`Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`, `x-api-key`, `api-key`) when recorded
  expectations are retrieved (as JSON or as generated client code) or persisted, so credentials captured
  while proxying don't leak into shared recordings, generated code, or persisted JSON. Off by default
  (current behaviour is unchanged); enable with `-Dmockserver.redactSecretsInRecordedExpectations=true` or
  `MOCKSERVER_REDACT_SECRETS_IN_RECORDED_EXPECTATIONS=true`. Redaction preserves each expectation's `times`,
  `timeToLive`, `priority` and `id`, so recordings still replay correctly. Note: because credentials are
  replaced with a placeholder, a recording that relies on the original credential to authenticate against an
  upstream must be retrieved with redaction disabled.

- Sensitive data is now redacted by default when importing HAR or Postman collections (`PUT /mockserver/import`):
  sensitive request/response headers (Authorization, API keys, cookies) and common secret JSON body fields are
  masked before expectations are stored. Redaction can be disabled or extended via import options.

- Smart deduplication and templatization of recorded traffic: collapse many recorded requests that differ only
  by an id segment (e.g. `/users/123`, `/users/456`) into one `/users/{id}` expectation, and drop exact
  duplicates — without merging requests that have genuinely different responses.

- **Templatize recorded query, header, and body values, not just path segments** (`mockserver-core`). A new
  opt-in `templatizeRecordedValues` setting extends the recorded-expectation post-processor so that, in addition
  to id-like path segments, volatile-looking **query parameter**, **header** and **JSON body** values are
  generalized into matchers: UUIDs, long numeric ids, ISO-8601 dates / date-times, epoch-millisecond timestamps,
  JWTs and long opaque tokens (base64 / hex) become a regex matcher (`.+` for query/header values, a
  `${json-unit.regex}` placeholder for JSON body leaves), and known-credential header names (`Authorization`,
  `Cookie`, `x-api-key`, correlation-id headers, …) are always generalized when present. This makes recordings
  reusable against future traffic instead of being pinned to one captured value. It is deliberately conservative
  — stable values (short strings, words, booleans, small numbers like a page size or status code, common
  content-types) are kept verbatim. Off by default and only takes effect when `deduplicateRecordedExpectations`
  is also enabled, so recorded output is byte-for-byte unchanged unless you opt in; enable with
  `-Dmockserver.deduplicateRecordedExpectations=true -Dmockserver.templatizeRecordedValues=true` or the
  equivalent `MOCKSERVER_TEMPLATIZE_RECORDED_VALUES=true` environment variable.

- Baseline traffic drift comparison: `PUT /mockserver/baseline/compare` diffs current recorded interactions
  against a saved baseline and returns a structured drift report (added / removed / changed), using
  value-insensitive JSON-shape comparison (a changed field value is not drift, but a new/removed field or a
  type change is) — usable from CI to catch traffic-shape regressions.

#### Verification
- **Timeout-aware eventual and negative-within-timeout verification** (`mockserver-client-java`). The Java
  client gained two additive, back-compatible verification styles for testing **asynchronous** applications
  (fire-and-forget sends, background workers) without an external retry helper:
  - **Eventual verification** — `verify(RequestDefinition, VerificationTimes, Duration timeout)` and
    `verify(Verification, Duration timeout)` poll the event log, re-running the verification with a small
    backoff (100 ms) until it passes or the timeout expires, throwing the last failure on timeout. This
    replaces the single-snapshot semantics of the existing `verify(...)` for cases where the request may not
    have reached MockServer yet.
  - **Negative-within-timeout verification** — `verifyNever(RequestDefinition, Duration window)` and
    `verifyNever(Verification, Duration window)` assert the condition stays **unmet** for the whole window
    (e.g. "no request was made within 2 seconds"), failing the moment a matching request is observed and
    returning normally if the window elapses with no match.

  Both are implemented purely client-side (a poll loop over the standard `PUT /mockserver/verify` endpoint);
  no server change is involved. The existing snapshot `verify(...)` methods are unchanged.

- **`verifyAll(...)` soft/collecting verification and verify-by-disposition filter** (`mockserver-core`,
  Java client). Two additive, back-compatible verification ergonomics:
  - **Soft/collecting verify** — `MockServerClient.verifyAll(Verification...)` runs every supplied
    verification and, instead of throwing on the first failure (as `verify(...)` does), collects all
    failures and throws a single `AssertionError` listing every mismatch, so a test sees all failures at
    once. Passing verifications produce no error.
  - **Verify by disposition** — `Verification.withDisposition(Disposition.FORWARDED | Disposition.MOCKED)`
    narrows a request-count verification to only those requests that were forwarded/proxied to an upstream
    server (`FORWARDED`) or that matched an expectation and received a mocked response (`MOCKED`). When no
    disposition is set, verification counts all received requests exactly as before. The disposition is
    serialized through the verify REST path (`disposition` field on the verification JSON) and ignored for
    response-aware and expectation-id verifications.

- **Field-level closest-match diff for sequence verification failures** (`mockserver-core`). When
  `detailedVerificationFailures` is enabled (on by default), a failed `verify(...)` sequence now appends a
  `closest match diff:` block for the specific sequence step that failed to match — listing which fields
  (method/path/headers/body/...) differ from the closest recorded request — exactly as single-request and
  single-response verification already do. Response-aware sequences (`withResponses(...)`) get the same
  field-level closest-response diff for the failing step. The diff is diagnostic only and never changes the
  pass/fail outcome; the existing failure-message format is otherwise unchanged, and when
  `detailedVerificationFailures` is disabled the message is byte-for-byte the same as before.

- **Response verification: status-code range / operator matching**: a response template may now match a
  status code by class range (`statusCodeRange: "2XX"` matches `200`–`299`, case-insensitive `2xx` is also
  accepted) or numeric operator (`">= 400"`, `"> 200"`, `"< 300"`, `"<= 204"`, `"== 201"`) instead of only
  an exact `statusCode`. The new `statusCodeRange` field is verification-only — it is never written to the
  wire when a response is served — and round-trips in JSON. When `statusCodeRange` is absent the historical
  exact-`statusCode` matching is byte-for-byte unchanged.

- **`detailedVerificationFailures` now applies to response verification**: a failing response verification
  (when `detailedVerificationFailures` is enabled) now appends a field-level "closest match diff" naming the
  response fields that differed (statusCode/reasonPhrase, headers, cookies, body) and the expected-vs-found
  values for the closest recorded response — mirroring the long-standing request-verification diagnostic,
  which previously did nothing for response verification. The diff is diagnostic only and never changes the
  pass/fail result; it is gated identically to the request side and capped by
  `maximumNumberOfRequestToReturnInVerificationFailure`.

- **Response matcher: reason-phrase honours `matchExactCase`**: when `matchExactCase` is enabled, the
  response reason-phrase is now matched case-sensitively (parity with the response body), so a template
  reason-phrase `OK` no longer matches an actual `ok`. The default (false) keeps the historical
  case-insensitive behaviour unchanged.

- **Response matcher: structured cookie matching**: a response template's structured cookies (`Set-Cookie`)
  are now matched using the same sub-set / notted semantics as the request side — extra response cookies are
  allowed, a missing required cookie fails the match, and notted cookie values are honoured. Responses with no
  cookie template are unconstrained (additive; default response matching is unchanged).

#### OpenAPI & contract testing
- **Auth in the generated Postman & Bruno API collections.** The collection generator
  (`scripts/collections/generate_collections.py`) now emits collection-level authentication instead of
  `auth: none`. It reads `components.securitySchemes` from the OpenAPI spec (bearer / API key / basic) and,
  when the spec declares none, falls back to a JWT **bearer token** — the control plane's
  `controlPlaneJWTAuthenticationRequired` scheme. Every request inherits the collection auth, and the
  credential is a placeholder variable (`bearerToken` / `apiKey` / `username`+`password`) left blank, so the
  collections still work unchanged against an unauthenticated MockServer. A new Buildkite infra step
  regenerates the collections and fails the build if the committed `examples/postman/**` and
  `examples/bruno/**` drift from the OpenAPI spec.

- **Opt-in OpenAPI request validation during mock matching** (`mockserver-core`). When a request is matched by
  an expectation created from an OpenAPI spec (`Expectation.when(specUrlOrPayload, operationId)` /
  `openAPI(...)`), the incoming request can now be validated against that spec before the matched action is
  dispatched. Enable it with the new `validateRequestsAgainstOpenApiSpec` flag
  (`Configuration.validateRequestsAgainstOpenApiSpec(true)`, the `mockserver.validateRequestsAgainstOpenApiSpec`
  system property, or the `MOCKSERVER_VALIDATE_REQUESTS_AGAINST_OPENAPI_SPEC` environment variable). When a
  matched request violates the spec (e.g. a malformed or missing request body), MockServer rejects it with a
  **400** describing the violations and logs an `OPENAPI_REQUEST_VALIDATION_FAILED` event, instead of serving
  the mock response. Previously OpenAPI request validation only ran on the proxy/forward path
  (`validateProxyOpenAPISpec`). The flag is **off by default and fully back-compatible**: with it unset, or for
  expectations not backed by an OpenAPI spec, behaviour is exactly as before. The validation runs off the Netty
  event loop, mirroring the validation-proxy request path.

- **Pact provider-state preconditions on import, verify, and export**: a Pact interaction's `providerState`
  (v2) / `providerStates` (v3) — the "given ..." precondition — is now preserved on import and mapped onto a
  MockServer scenario (`scenarioName` = `pact-provider-state`, `scenarioState` = the state name), so an imported
  interaction only matches once that provider state has been activated. Pact verification activates each
  interaction's provider state before matching (the provider-state callback) and restores prior state
  afterwards, and export round-trips the provider state. Stateless interactions are unchanged.

- **OpenAPI contract testing endpoint `PUT /mockserver/contractTest`**: runs an OpenAPI spec as contract
  tests against a live service. For each operation MockServer builds a representative example request, sends
  it to the target `baseUrl` (reusing the wired HTTP client, with the same SSRF protection as the forward
  and replay paths), and validates the response against the spec. Returns a structured pass/fail-per-operation
  JSON report (`totalOperations`, `passed`, `failed`, `allPassed`, and per-operation `validationErrors`). An
  optional `operationId` restricts the run to a single operation.

- **Enforce OpenAPI response validation for mocks `enforceResponseValidationForMocks`**
  (`MOCKSERVER_ENFORCE_RESPONSE_VALIDATION_FOR_MOCKS`, default false): when enabled (alongside
  `openAPIResponseValidation`), a mock response that fails OpenAPI response validation is replaced with a
  `502` error describing the violations, matching the enforcement already available on the validation-proxy
  path via `validateProxyEnforce`. The default (false) keeps the historical advisory-only behaviour where
  violations are logged but the response is still returned.

- OpenAPI example generation accepts an optional reproducibility seed and per-field value overrides (via a
  reserved `__generationOptions__` entry in the import's operations map), so generated example bodies can be
  deterministic per run and pin specific fields.

- Import Pact v3 consumer contracts as expectations: `PUT /mockserver/import?format=pact` (or the dedicated
  `PUT /mockserver/pact/import`) consumes a Pact contract and generates matching expectations, mapping Pact
  matchingRules to MockServer matchers — the inverse of the existing Pact export/verify.

#### gRPC & GraphQL
- **gRPC example synthesis from descriptors**: a matched gRPC expectation with a successful
  (`grpc-status: 0`) response and *no* hand-authored response body now returns a schema-valid
  example message synthesized from the loaded proto descriptor's response type, instead of an
  empty frame. The new `GrpcExampleSynthesizer` walks the message's fields and emits
  deterministic, type-correct placeholders — scalars, enums (first declared value), nested
  messages, repeated fields (single element), map entries, `oneof` (first field only), and the
  protobuf well-known types (`Timestamp`, `Duration`, scalar wrappers, etc.). Recursion is
  bounded so self-referential schemas terminate safely. Explicit response bodies are always
  used as-is and never overwritten — synthesis only fills the "no body provided" gap.

- **GraphQL and AsyncAPI spec import**: `PUT /mockserver/graphql` imports a GraphQL SDL / introspection document
  and generates an expectation per root operation type whose responses are schema-valid (built on the GraphQL
  response synthesizer); `PUT /mockserver/asyncapi/http` turns an AsyncAPI document's channels into GET
  expectations serving schema-aware example payloads over HTTP. (Raw `.proto` import is deferred — compiled gRPC
  descriptors still import via `PUT /mockserver/grpc/descriptors`.)

- **gRPC bidi-stream response templating**: a `grpcBidiResponse` message may set `templateType`
  (`VELOCITY`/`MUSTACHE`) so its `json` is rendered as a response template against the matched inbound message
  (exposing `request.body`, `jsonPath`, and the `scenario` state helper). Static (no `templateType`) responses
  are emitted byte-for-byte unchanged.

- **GraphQL schema-driven response synthesis**: a GraphQL expectation body may now carry a `schema` field
  containing either SDL text (e.g. `type Query { hello: String }`) or an introspection JSON result. When a
  schema is registered, MockServer can synthesize a schema-valid `{"data": {...}}` response for a matched
  query with no hand-authored response JSON — respecting field types, nullability, lists, nested objects,
  scalars (including common custom scalars such as `DateTime`/`JSON`), enums (first declared value), field
  aliases, `__typename`, inline fragments, and named fragment spreads. Only the requested selection set
  appears in the response (a subset of the type is honoured). Backed by a new `com.graphql-java:graphql-java`
  dependency (pinned to the Java-17-compatible 22.x line). The `schema` field round-trips in JSON in both the
  nested `graphQL` form and the flat body form.

- gRPC descriptor management in the Python, Node, Go, Rust, .NET and Ruby clients (`upload_grpc_descriptor` /
  `uploadGrpcDescriptor`, `retrieve_grpc_services` / `retrieveGrpcServices`, `clear_grpc_descriptors` /
  `clearGrpcDescriptors`) — upload a compiled gRPC descriptor set as raw bytes, list registered services, and
  clear them — bringing every client to parity with the Java client.

- gRPC **Connect protocol** (buf.build Connect) unary mocking: Connect unary calls are plain HTTP POSTs, so
  they already match with ordinary `httpRequest`/`httpResponse` expectations — new `ConnectResponse.success(json)`
  / `ConnectResponse.error(code, message)` helpers build the correct `application/json` success body and the
  Connect error envelope (`{code, message, details}`) with the HTTP status mapped from the Connect error code.
  Real gRPC (`application/grpc`) traffic is unaffected. (Connect streaming is not yet supported.)

#### Chaos, resilience & SRE
- **Mock an OIDC / OAuth2 identity provider in one call — consolidation (Wave 1)** (`mockserver-core`,
  `mockserver-client-java`). `PUT /mockserver/oidc` (and a new typed Java client `mockOpenIdProvider()` /
  `mockOpenIdProvider(OidcProviderConfiguration)`) generate a complete mock IdP — discovery, JWKS, token,
  authorize, userinfo, introspection, revocation, and a new end-session (`/logout`) endpoint. Tokens are now
  minted at request time so the `/authorize` **`nonce`** is echoed into the `id_token`, and the `id_token`
  (`aud=clientId`, `nonce`, `at_hash`, scope-gated profile/email claims, issued only with the `openid` scope)
  is correctly split from the `access_token` (`aud=audience`, `scope`, `client_id`); both carry `nbf`, and a
  `refresh_token` is returned for the `authorization_code`/`refresh_token` grants. Signing is configurable
  (`signingAlgorithm` RS/ES 256/384/512, optional supplied key + stable `keyId`; JWKS always publishes the
  public half of the signing key). Discovery now reflects the configured algorithm and advertises only
  implemented grants plus PKCE/auth-method/claims metadata. Authorization codes are single-use and TTL-bounded.
- **Scheduled (delayed / cron) chaos experiment start** (`mockserver-core`). A chaos experiment definition now
  accepts two optional, additive scheduling fields: `startDelayMillis` (a fixed delay before stage 0 is applied,
  capped at 7 days) and `cronSchedule` (a standard 5-field cron expression — `minute hour day-of-month month
  day-of-week` — evaluated against the JVM default time zone at minute granularity, supporting `*`, ranges,
  comma lists, and steps). When either is set, `ChaosExperimentOrchestrator` defers stage 0 to the scheduled
  time: the experiment sits in a new `scheduled` status (no chaos applied), and `GET /mockserver/chaosExperiment`
  reports `startRemainingMillis`. When both are set, the later of the two wins. A pending start is cancelled by
  `DELETE` or a replacing `PUT` before any chaos is applied. No scheduling fields = immediate start (unchanged
  default); the status/definition JSON omit the new fields when unset. The deferred start reuses the existing
  single-thread `chaos-experiment-scheduler`; cron parsing is handled by a self-contained `CronSchedule`
  evaluator (no third-party cron dependency).
- **Mock OIDC / OAuth2 provider — OAuth2 completion (Wave 2)** (`mockserver-core`, `mockserver-client-java`).
  Extends the one-call mock IdP with three OAuth2 capabilities, all additive and off by default:
  (1) **Device authorization grant (RFC 8628)** — a new `POST /device_authorization` endpoint (configurable
  via `deviceAuthorizationPath`, advertised in discovery as `device_authorization_endpoint`) returns
  `device_code`, `user_code`, `verification_uri`, `verification_uri_complete`, `expires_in`, and `interval`;
  polling `/token` with `grant_type=urn:ietf:params:oauth:grant-type:device_code` returns
  `authorization_pending` for the first `deviceCodePendingPolls` polls (default `0` = approve immediately) and
  then mints tokens. Device codes are single-use after approval and TTL-bounded; `device_code` is re-added to
  discovery `grant_types_supported`. (2) **Token-endpoint client authentication** — a new
  `enforceClientAuthentication` flag (default `false`) makes `/token` validate `client_secret_basic`
  (`Authorization: Basic`) and `client_secret_post` (form `client_id`/`client_secret`) against the configured
  `clientId`/`clientSecret`; missing/wrong credentials return RFC 6749 §5.2 `invalid_client` (HTTP 401 +
  `WWW-Authenticate: Basic`). (3) **Opaque access tokens** — an `opaqueAccessToken` flag (default `false`)
  makes the `access_token` a random opaque string (the `id_token` stays a signed JWT); the opaque token and its
  claims are stored so `/introspect` (now a callback, RFC 7662) returns `active:true` with the claims for a
  known token and `active:false` for an unknown/expired one. All token-endpoint errors use the RFC 6749 §5.2
  envelope (`error`, `error_description`, `error_uri`) with correct HTTP status.
- **Response-content conditional breakpoints** (`mockserver-core`). A `RESPONSE`-phase breakpoint matcher now
  accepts optional `responseStatusCodeMin`/`responseStatusCodeMax` (inclusive status-code range) and
  `responseBodyContains` (regex searched within the response body) fields, so a breakpoint can pause only when
  the response looks a certain way — e.g. break only on `5xx` responses or on a body containing a particular
  message. When set, the breakpoint pauses only if the response satisfies all configured conditions; absent
  fields preserve the previous behaviour (pause on every matching response). Conditions are evaluated only at
  the response phase (the request/stream paths are unchanged), via the new
  `BreakpointMatcherRegistry.findResponseMatch`; a matcher whose condition fails falls through to later
  matchers. The `responseBodyContains` regex is compiled at registration; an invalid regex or an inverted
  status range returns `400`. The fields are accepted by `PUT /mockserver/breakpoint/matcher`, echoed by the
  register/list endpoints, and back-compatible (purely additive).

- **General-purpose rate limiting (`rateLimit` expectation clause)** (`mockserver-core`). A protocol-agnostic
  `rateLimit` clause on an expectation (sibling of `chaos`) returns a deterministic `429` with `Retry-After` and
  `X-RateLimit-Limit/Remaining/Reset` headers once a matched expectation exceeds its configured rate — so a test
  can exercise a client's backoff/retry logic without wrapping a chaos profile. Supports `fixed_window` and
  `token_bucket` algorithms, a named shared counter (multiple expectations can share one limit), and an
  overridable error status. Off by default (no clause = no behaviour); the named-counter store is node-local and
  bounded by `rateLimitMaxNamedQuotas`.

- **Retry/backoff recovery primitive — "fail N times then succeed"** (`mockserver-core`). A new opt-in
  `recoverAfter` clause on `httpResponse` returns a failure response (configurable `failResponse`, default
  `503`) for the first `failTimes` matches and then the configured success response — so a test can
  deterministically exercise a client's retry/backoff logic. An optional `idempotencyHeader` scopes the
  attempt counter per request-header value (distinct keys each recover independently); without it the counter
  is per-expectation. Independent of `Times`. A response without `recoverAfter` is unchanged; the keyed counter
  registry is bounded (LRU eviction) to prevent unbounded growth.

- **API-driven load generation via Load Scenarios** (`mockserver-core`, `mockserver-netty`). A new opt-in
  control plane (`PUT/GET/DELETE /mockserver/loadScenario`) drives outbound traffic at a target: a
  `LoadScenario` is an ordered list of request steps (modelled on a verification sequence) with per-step
  think-time (the existing `Delay` model, including jitter distributions) and a ramp `LoadProfile`
  (`CONSTANT` or `LINEAR` virtual-user count over a duration). Request fields are template-rendered per
  iteration with a new `iteration` context object (`iteration.index`/`vuId`/`vuIteration`/`elapsedMillis`)
  alongside the existing `faker`/`uuid`/`scenario` helpers, so data varies across iterations. Runs in-process
  on a non-blocking scheduler that fires through the existing forward HTTP client; results feed the metrics
  histograms and the SLO sample store, so a load run can be asserted with `verifySLO`. Off by default
  (`loadGenerationEnabled`) and bounded by hard caps on virtual users, in-flight requests, request rate,
  duration, and steps to prevent a scenario overloading the instance serving mocks.

- **SLO resilience verdicts (`mockserver-core`)** — assert pass/fail service-level objectives over observed
  forwarded traffic. A new opt-in windowed sample store (`sloTrackingEnabled`, off by default; a true no-op on
  the request path when disabled) records latency + error per forwarded round-trip, and `PUT /mockserver/verifySLO`
  evaluates `SloCriteria` (latency percentile + error-rate objectives over a `LOOKBACK`/`EXPLICIT` window against
  optional upstream hosts) and returns a structured `SloVerdict` — `200` PASS / `406` FAIL / `400` when malformed or
  tracking is disabled. Bounded by `sloWindowMaxSamples` (50000) and `sloWindowRetentionMillis` (600000). Pairs with
  chaos experiments: drive faults, then assert the system stayed within objectives.

- **Connection-lifecycle fault injection + preemption simulation** (`mockserver-core`, `mockserver-netty`).
  Extends the per-host TCP chaos profile with response-path lifecycle faults — mid-response TCP RST
  (`resetMidResponse`), host-scoped jittered slow-close (`slowCloseDelay`), and HTTP/2 GOAWAY (`http2GoAway`) —
  configured through the existing `PUT /mockserver/tcpChaos`. Adds a `PUT/GET/DELETE /mockserver/preemption`
  control plane that simulates a Kubernetes rolling-update / spot-reclaim drain: it cordons new exchanges
  (HTTP/2 GOAWAY or `503` + `Connection: close`), reports the live in-flight count while draining, and
  auto-uncordons after a TTL — without stopping the JVM. Destructive RSTs feed the chaos auto-halt
  circuit-breaker (which now also clears the TCP chaos registry); graceful drain signals do not. Off by
  default; zero cost on the normal response path when inactive.

- **Saved chaos profile library (save/apply/list/delete chaos experiments by name)**: chaos experiments can now be
  stored as reusable, named profiles instead of re-authoring the experiment JSON every time. A profile is simply a
  saved chaos-experiment definition (the same JSON shape `PUT /mockserver/chaosExperiment` accepts) kept under a name.
  - `PUT /mockserver/chaosExperiment/profiles/{name}` — save (or replace) a profile under `{name}`.
  - `POST /mockserver/chaosExperiment/apply/{name}` — apply (start) a saved profile by name.
  - `GET /mockserver/chaosExperiment/profiles` — list saved profile names; `GET /mockserver/chaosExperiment/profiles/{name}`
    returns one profile's definition.
  - `DELETE /mockserver/chaosExperiment/profiles/{name}` — remove a saved profile.

  Profiles are persisted in the `StateBackend` CRUD-entity store, so they **survive a server reset** (unlike active
  chaos, which a reset clears) and **replicate across the fleet** when a clustered backend is configured. The existing
  `/mockserver/chaosExperiment` endpoint is unchanged. The dashboard Chaos panel gains a **Saved Profiles** list with a
  "Save as Profile" button (saves the current experiment editor) and one-click apply / delete chips.

- **Conditional (Nth-hit) breakpoints**: breakpoint matchers accept an optional `skipCount` that skips the
  first N matching hits and only pauses afterward (per-breakpoint, thread-safe counter); exposed via the
  `/mockserver/breakpoint` API and a "Skip count" field in the dashboard Breakpoints panel.

- **Stream-level error injection** (HTTP/2 / HTTP/3): the `httpError` action gained
  `withStreamError(long)` (plus a `withStreamError(StreamErrorCode)` enum overload and a
  `withStreamErrorCodeName("REFUSED_STREAM")` convenience), serialised in JSON as a `streamError`
  integer alongside `dropConnection`/`responseBytes`. When set, a matched request stream is reset
  with the given error code instead of returning a response: over HTTP/2 MockServer sends an
  `RST_STREAM` for that stream (RFC 7540 §7 codes, e.g. `REFUSED_STREAM`=0x7), over HTTP/3 a QUIC
  `RESET_STREAM` (RFC 9114 §8.1 codes, e.g. `H3_REQUEST_CANCELLED`=0x10c); other multiplexed streams
  on the same connection are unaffected. HTTP/1.1 has no stream concept, so a stream error falls back
  to dropping the whole connection (the existing `dropConnection` behaviour). When both `streamError`
  and `dropConnection` are set, `streamError` takes precedence and `dropConnection` is ignored. Also
  exposed on the Node, Python and Ruby clients as `streamError` / `stream_error`. Useful for resilience
  testing that clients handle mid-stream resets. Fully backward compatible — omitted when null, so
  existing `dropConnection`/`responseBytes` behaviour is unchanged.

#### CLI & configuration
- **CLI `--watch` live-reload flag and `mockserver demo` subcommand** (`mockserver-netty`). The `run`
  subcommand (and its bare/legacy forms) gains `--watch`, which enables live-reload of expectations when the
  initializer/expectations file (from `--init` / `--openapi`) changes, without a restart (~5s poll). It is a
  thin CLI surface over the existing `mockserver.watchInitializationJson` property, so the equivalent
  `MOCKSERVER_WATCH_INITIALIZATION_JSON=true` / `-Dmockserver.watchInitializationJson=true` forms still apply.
  A new `mockserver demo` subcommand starts a server pre-loaded with a small set of example expectations
  (`GET /hello`, `GET /users/{id}`) and prints a getting-started URL, the dashboard URL, and a sample `curl`
  for instant onboarding. Both are additive and backward-compatible.

- **`mockserver import <file>` CLI subcommand and client `importExpectations(...)` methods.** A new
  `import` subcommand (`org.mockserver.cli.Main`, `mockserver-netty`) loads a JSON expectations file —
  the same single-object-or-array format produced by `--persist`, the dashboard export, or
  `retrieveActiveExpectations(..., Format.JSON)` — into an already-running MockServer:
  `mockserver import ./expectations.json -p 1080` (`-H/--host` defaults to `localhost`). It connects as a
  client and upserts each expectation; it does **not** start a server and never sends a shutdown to the
  target. A missing/invalid file or unreachable server prints a clean error and exits non-zero. The
  `mockserver-client-java` client gains typed `importExpectations(String json)` and
  `importExpectationsFromFile(String filePath)` wrapping the same upsert.

- **Effective-configuration diagnostic (`--print-config`)**: the command-line launcher now accepts a
  `--print-config` flag that prints the effective configuration and exits, listing each known property as
  `name = value   [source]` where `source` is the tier that supplied the value (`system-property`,
  `properties-file`, `environment-variable`, `default`, or `runtime-set` for a value applied at runtime via a
  programmatic setter). Properties left at their built-in default show `(default)`, and sensitive values
  (passwords, API keys, tokens, secrets, private keys, credentials) are redacted as `***REDACTED***` via the
  existing sensitive-name detection. The same report is available at runtime as JSON from the authenticated
  `GET /mockserver/config` control-plane endpoint. Source reporting is purely observational — it reads the
  in-memory property cache first (exactly as the real resolution does) and then the same tiers in the same
  precedence order, without mutating any state or changing how any value resolves.

- **Readiness endpoint `GET /mockserver/ready`**: returns 503 until expectation initializers and OpenAPI
  seeding complete, then 200 — distinct from the always-200 liveness/status endpoints. The Helm chart now
  points the readiness probe at it and liveness at the existing path, so Kubernetes no longer routes traffic
  to a pod whose expectations aren't loaded yet.

- **`failOnInitializationError`** (`MOCKSERVER_FAIL_ON_INITIALIZATION_ERROR`, default false): when enabled, a
  malformed initialization JSON/OpenAPI file or a broken initialization class fails server startup instead
  of logging a warning and continuing with zero expectations.

- **Warning for unrecognised configuration keys**: MockServer now logs a `WARN` at startup when it sees a
  `mockserver.*` system property, a `MOCKSERVER_*` environment variable, or a properties-file key in the
  `mockserver.` namespace that isn't a known configuration property — so a typo such as
  `-Dmockserver.maxExpectatons=...` or `MOCKSERVER_METRICS_ENABLE=...` is surfaced instead of being silently
  ignored (the previous behaviour, which left the default in effect with no signal). Recognised keys are
  derived from the configuration constants themselves, plus the CLI/launcher keys (`MOCKSERVER_SERVER_PORT`,
  `MOCKSERVER_LAUNCHER`, `MOCKSERVER_JAVA_OPTS`, etc.), so valid configuration never warns and unrelated
  environment variables (`JAVA_HOME`, `PATH`, …) are ignored.

- Graceful shutdown now drains in-flight requests: on stop, MockServer waits up to `stopDrainMillis` (env
  `MOCKSERVER_STOP_DRAIN_MILLIS`, default 15000; 0 disables) for active requests to complete before shutting
  down — avoiding cut connections during Kubernetes rolling restarts.

- More configuration properties are editable at runtime from the dashboard configuration dialog (matching &
  proxying, logging, and CORS toggles that the server applies live).

#### WASM custom rules
- **Richer WASM matcher ABI, authoring SDK, and a `wasm/test` endpoint** (`mockserver-core`). WASM custom-rule
  modules can now read the request **method, path and headers** in addition to the body. A module that exports
  `match_request(ptr, len)` receives a UTF-8 JSON envelope
  (`{"method","path","headers":{name:[values]},"body"}`) instead of just the body; MockServer prefers
  `match_request` when present and falls back to the legacy body-only `match(ptr, len)` export, so existing
  body-only modules keep working unchanged. A new dependency-free Rust authoring crate
  `mockserver-wasm-sdk` (`examples/wasm/sdk-rust/`) gives module authors typed accessors
  (`Request::method/path/header/body`) and an `export_match_request!` macro, with a sample rule in
  `examples/wasm/rust-request/` that matches on method + path + header. A new
  **`POST /mockserver/wasm/test`** control-plane endpoint runs a module (supplied inline as base64 `module`, or
  by `moduleName` for an already-uploaded module) against a sample `request` and returns `{ "matched": true|false }`,
  so IDEs/users can validate a module without creating a live expectation. The endpoint requires `wasmEnabled=true`
  (otherwise 403) and is fail-closed (invalid modules report `matched: false`).

#### Clustering & observability
- **Drift alerting webhook** (`mockserver-core`). When `mockserver.driftAlertWebhookEnabled=true` and
  `mockserver.driftAlertWebhookUrl` is set, MockServer fires a fire-and-forget HTTP `POST` to that URL each
  time a stored drift record meets the configured severity threshold, carrying the drift record as JSON
  (`{"event":"mockserver.drift.alert","epochTimeMs":...,"severity":...,"drift":{...}}`). Off by default and
  fully fail-soft — a failed, slow, unreachable, or misconfigured endpoint can never affect drift analysis
  or the served response. Severity uses the LLM-assigned `semanticSeverity` when available, otherwise a
  structural fallback by drift type, and fires at or above `mockserver.driftAlertSeverityThreshold` (default
  `BREAKING`). A per-signature (`expectationId|driftType|field`) cooldown
  (`mockserver.driftAlertCooldownMillis`, default 60s) de-dups recurring drifts. The outbound sender is injected
  from the Netty HTTP client (core does not depend on it), mirroring the load-scenario orchestrator wiring.

- **Control-plane audit logging** (`mockserver-core`). An opt-in, append-only, bounded, in-memory audit log of
  control-plane *mutations* (who/what/when/where/outcome) so a shared MockServer can run with accountability for
  who changed mock state. Off by default (`controlPlaneAuditEnabled`); when disabled, control-plane operations
  behave byte-for-byte identically. Entries record redacted, structural metadata only — never request headers or
  bodies — with the path's query string dropped; the best-effort principal (unverified JWT `sub` or mTLS CN, else
  `anonymous`) never stores the raw token. Retrieve via `GET /mockserver/audit` (`?limit=<n>`, default 200, cap
  1000), auth-gated like other control-plane endpoints; cleared on reset. Bounded by `controlPlaneAuditMaxEntries`
  (default 1000, fixed at startup); reads are excluded unless `controlPlaneAuditReads=true`. Verified identity and
  true per-operation outcomes are deferred to a later release.

- **Cluster status endpoint and metric**: a new read-only `GET /mockserver/cluster` control-plane endpoint
  reports the node's view of cluster membership and health as JSON — `clustered`, `nodeId`, `coordinator`,
  `clusterName`, `memberCount`, and a `members` list that flags the coordinator and the local node. On a
  single-node / in-memory deployment it returns a sensible degenerate response (`clustered: false`, one local
  member that is its own coordinator), so health checks and tooling can call it unconditionally; with the
  Infinispan backend clustered, it reports the real JGroups fleet membership. A matching Prometheus gauge
  `mock_server_cluster_members` (read live at scrape time, `1` for single-node) exports the member count when
  metrics are enabled. Backed by a new `StateBackend.clusterInfo()` SPI method with a degenerate default
  implementation and a real Infinispan implementation.

- **Dropped-log-event visibility**: when the event-log ring buffer is full under sustained load, dropped
  log events are now counted and observable instead of vanishing silently (previously only WARN/ERROR
  drops were logged, so INFO/DEBUG drops were invisible). A `mock_server_dropped_log_events` Prometheus
  counter is exported when metrics are enabled, the running total is always available regardless of
  metrics, and a single WARN is logged on the first drop pointing at `ringBufferSize` / log verbosity as
  the remedy.

- **Per-upstream forward/proxy observability**: forwarded and proxied requests are now observable by
  the upstream they hit and how it performed. When `metricsEnabled` is set, two new Prometheus metrics
  are exposed: `mock_server_forward_request_duration_seconds` (a histogram of forward latency labelled
  by `upstream_host`, sourced from the existing client-side `Timing` rather than re-measured) and
  `mock_server_forward_requests` (a count labelled by `upstream_host` and `status_class`, e.g. `2xx`/`5xx`).
  When `otelTracesEnabled` is set, the forward/proxy request span additionally carries the OpenTelemetry
  `server.address`/`server.port` attributes for the resolved upstream. Labels are bounded to the upstream
  host (never the full URL/path) to keep cardinality in check, and all recording is a no-op with zero
  overhead when metrics/telemetry are disabled.

- Optional per-expectation Prometheus metrics: enable `perExpectationMetricsEnabled` (env
  `MOCKSERVER_PER_EXPECTATION_METRICS`, default off) to emit a `mock_server_expectation_matched` counter
  labelled by stable expectation id, for per-endpoint match dashboards (cardinality is bounded by expectation
  count; off by default to avoid surprise series).

#### SAML identity provider mocking
- **Mock SAML 2.0 IdP polish** (`mockserver-core`, `mockserver-client-java`). The `PUT /mockserver/saml`
  mock Identity Provider gains:
  - **Configurable signing algorithm** via `signingAlgorithm` (`RS256`/`RS384`/`RS512`/`ES256`/`ES384`/`ES512`);
    the published metadata X.509 certificate always matches the signing key. Defaults to the existing
    self-signed RSA / SHA-256 behaviour when unset.
  - **Single Logout (SLO)**: a `SingleLogoutService` (HTTP-POST binding) is published in the metadata and a
    new `/saml/logout` (configurable via `sloServiceUrl`) endpoint accepts a `LogoutRequest` and returns a
    signed `LogoutResponse` form-POSTed to the SP's SLO URL (`spSingleLogoutServiceUrl`), echoing `RelayState`.
  - **Negative-test flags** `expiredAssertion`, `wrongAudience`, and `tamperedSignature` deliberately mint a
    defective assertion so an SP's rejection paths can be exercised.
  - **Typed Java client API**: `MockServerClient.mockSamlProvider(SamlProviderConfiguration)` and a no-arg
    default stand up the whole IdP in one call and return the upserted expectations.
  - New consumer documentation page "Mocking a SAML Identity Provider".


### Changed

#### Performance
- **WASM custom-rule modules are parsed once and cached** (`mockserver-core`) instead of re-parsing and
  re-validating the module binary on every request, the most expensive step of evaluating a WASM rule.
  A fresh execution instance is still created per call, so thread-safety is unchanged; the cache is
  cleared when a module is removed or the server is reset.
- **Mustache response templates are compiled once and cached** (`mockserver-core`), mirroring the
  Velocity engine, instead of recompiling the template on every render.
- **OpenAPI request/response schema validators are compiled once and cached** (`mockserver-core`). When
  validating requests/responses against an OpenAPI spec, the per-operation JSON-schema validator is now
  reused (keyed by schema content) instead of being recompiled on every request. Validation results and
  error messages are unchanged; measured ~50-66% less time and allocation on the validation path.
- **Reduced per-request object churn in identity and LLM endpoints** (`mockserver-core`). The OIDC
  callbacks reuse a shared JSON writer, the SAML response builder reuses its XML factories (still creating
  per-request parsers/transformers, so thread-safety and XXE settings are unchanged), and the LLM
  structured-output JSON-schema validator is compiled once and cached instead of per response.

#### AI, LLM & agent protocols (LLM / MCP / A2A)
- **Demo now showcases LLM cost optimisation**: `npm run demo` seeds a crafted seven-call
  support-agent run (mocked OpenAI Chat Completions with realistic token usage) designed to fire all
  six optimisation signals (`REPEATED_SYSTEM_PROMPT`, `LARGE_STATIC_CONTEXT_RESENT`,
  `DETERMINISTIC_TOOL_CALL`, `OVERSIZED_TOOL_RESULT`, `OUTPUT_TOKEN_BLOAT`,
  `DUPLICATE_CONSECUTIVE_CALL`), so the dashboard **LLM Optimise** tab (and
  `GET /mockserver/llm/optimisationReport`) is populated out of the box. An optional, documented
  recipe — `mockserver-ui/scripts/demo-opencode-proxy.sh` plus the "Generate real traffic with
  OpenCode" section of the AI optimisation docs — shows how to capture **real** agent traffic by
  proxying a headless OpenCode run (your own API key) through MockServer; it is deliberately not part
  of the offline, deterministic `npm run demo`.

#### Dashboard UI
- **Dashboard navigation**: the **Optimise** tab is renamed **LLM Optimise** and moved to sit
  immediately after **Chaos**, and dashboard tabs now show a one-line description bar beneath the
  navigation explaining what the screen is for (for example, LLM Optimise: "Analyse captured LLM
  traffic to optimise prompts, inference cost, safety, and speed").

- **Dashboard navigation**: the **Sessions** tab is renamed **Trace** — clearer language for grouping
  related requests (including LLM agent runs) to debug multi-step flows — and moved to sit immediately
  after **Traffic**. The **Get Started** page now leads with the same six key features as the navigation:
  the old "LLM / AI Debugging" tile is replaced by an **LLM Optimise** tile and a new **Performance
  Testing** tile is added. The tiles are slightly more compact so all six stay on a single row, still
  collapsing to a bulleted list when the panel is narrow.

- **Stateful scenarios moved from Trace to the Mocks page**: the **Scenarios** state-machine panel is mock
  configuration, not request debugging, so it now lives under a **Scenarios** tab on the **Mocks** page
  (alongside **Compose**) instead of on the **Trace** page. The Trace page now has just **Traces** and
  **Compare**, and the Compare tab's selectors now say "trace" instead of "session" (Trace A / Trace B).

- **Dashboard UI visual refresh**: the dashboard now has a real design system (consistent spacing,
  shadows, typography and a dark-mode-aware log-colour palette). The Metrics view leads with KPI
  "hero" stat cards, its charts have a real time axis and area fill, and panels show skeleton
  placeholders while loading instead of a bare spinner. Server error messages shown in the UI are now
  humanised (a short, actionable message with the raw server detail tucked behind a "Details" toggle)
  instead of dumping raw server output.

- **Dashboard UI: viewport virtualization for long lists** (behaviour-preserving): the Log Messages,
  Active Expectations and Requests panels now window their rows — only the entries in (or near) the
  visible area are mounted in the DOM instead of mounting every row, so panels with tens of thousands
  of entries (e.g. a 50k-entry log) scroll smoothly instead of bogging down the browser. Rows are
  measured dynamically so expand/collapse still works, and filtering, ordering, auto-scroll-to-top,
  selection and live append are unchanged. In a non-layout/headless environment (or a panel laid out at
  zero height) the list falls back to rendering every row so content stays reachable.

- Dashboard is now more usable on small screens (mobile and the IDE-embedded dashboard): the Get Started
  feature tiles collapse to a compact bulleted list when the panel is narrow, and the Log Messages list
  hides the inline message preview when narrow (it wrapped one word per line) — the full message is still
  available by expanding the row. The Get Started collapse is now driven by a **CSS container query** (the
  panel's own width) rather than the viewport width, so it works inside the narrow IDE-embedded dashboard
  (whose CSS viewport stays wide regardless of the tool-window size), not only when a browser window is
  narrowed; feature-tile titles that wrap to two lines also use a tighter line height.

#### IDE extensions (VS Code & JetBrains)
- The in-IDE dashboard now shows the MockServer logo as its icon instead of a generic browser icon — the
  JetBrains "MockServer Dashboard" tool window (light/dark variants) and the VS Code dashboard webview tab.

- VS Code and JetBrains extensions now make the MockServer Docker image, container name, and port configurable
  (VS Code: `mockserver.*` settings). The Docker image tag now defaults to the extension's own version instead
  of a hardcoded constant, so it can no longer drift behind the release (previously pinned to `7.0.0`).

- VS Code and JetBrains extensions now validate the active editor before submitting it to MockServer and show
  a clear warning instead of a raw server error: **Generate From OpenAPI Spec** warns when the file is not an
  OpenAPI/Swagger spec, and **Load Expectations** warns when the file is not valid JSON or is actually an
  OpenAPI spec (redirecting to the Generate action).

- Both editor extensions now ship a proper marketplace icon (the MockServer "M" mark) instead of the generic
  placeholder — a 128×128 icon for the VS Code Marketplace / Open VSX and a `pluginIcon.svg` (light/dark) for
  the JetBrains Marketplace.

- VS Code extension gains a **MockServer side panel** (an Activity Bar view) with grouped one-click actions —
  Server, Author, Inspect, and WASM — plus a status line showing the configured `localhost:<port>`, mirroring
  the JetBrains tool window so the features are discoverable without the command palette. The live dashboard
  now opens **docked in the bottom panel** (its own "MockServer Dashboard" view, full width, not mixed in with
  your editor tabs) instead of as an editor tab.

- VS Code extension is also more discoverable elsewhere: a **status-bar item** (`MockServer :<port>`) opens a
  quick menu (Open Dashboard, Start, Stop, View Request Log), and the file-scoped commands appear in the editor
  title bar and right-click menu only on the files they apply to (`*.mockserver.json(c)` and
  `*.mockserver-request.json`), keeping the command palette uncluttered. The trace-id and WASM-module-name
  prompts validate input inline, and expectation snippets now also fire in `.jsonc`-typed files.

- JetBrains plugin actions now carry icons and are grouped (Server / Editor / WASM) in the **Tools >
  MockServer** menu and tool window; the **MockServer** tool window shows the configured `localhost:<port>`
  target with bold section headers, and the in-IDE dashboard shows a friendly "no MockServer running" panel
  (with a retry link) instead of a raw browser connection error when the server is unreachable.

- JetBrains plugin **Save Recorded Expectations** now offers a JSON / Java DSL format choice (matching the VS
  Code extension), so recorded traffic can be turned into Java code as well as JSON.

- JetBrains plugin **MockServer** tool window now has an inline **Port** field (default 1080) next to the
  status line, bound to the saved setting — change the port in one place and the Start (Docker) action, the
  dashboard URL, and every REST action use it, no need to open Settings.

- Both editor extensions now present a Marketplace-ready landing page that leads with the flagship
  capabilities (schema authoring, record-to-code, in-IDE dashboard), a badge row, and a 30-second quick start
  — the VS Code README and the JetBrains plugin's `<description>` (which is what the JetBrains Marketplace
  actually renders, not the repo README).

#### Client libraries
- **Client connection/error-handling fixes** (code-quality review): the Python client closes each HTTP
  response (was leaking a socket per control-plane call); the Node client rejects with the error message
  instead of `JSON.stringify(error)` (which produced an empty `{}`, losing all diagnostics); the Ruby
  client uses `Net::HTTP.start { ... }` so the connection is always closed.
- **Clearer error when a client's binary launcher finds no release bundle**: the Go, Node, Python, Ruby,
  Rust and PHP clients' auto-download launchers now detect an HTTP 404 on the MockServer release bundle and
  fail with an actionable message — naming the requested version and pointing to a version that ships
  bundles, the Docker image (`mockserver/mockserver:mockserver-<version>`), or the Maven Central jar —
  instead of a raw 404 or a corrupt download. (Some release tags ship no self-contained bundle; previously
  every launcher hard-404'd with no guidance.)

#### Request matching & response generation
- **Efficiency: fewer allocations on hot expectation/log paths** (behaviour-preserving): clearing the
  whole event log (a `clear` with no filter) no longer rebuilds an uncached request matcher per call and
  no longer scans each entry through a matcher — an empty filter now short-circuits to clearing exactly
  the entries that carry at least one request, leaving request-less entries (e.g. `SERVER_CONFIGURATION`)
  in place exactly as the previous matcher-based scan did;
  compiling a request expectation only allocates the JSON-schema body decoder when the body matcher is a
  JSON-type matcher (the control-plane path still always carries it); and `ObjectMapperFactory` now caches
  the custom-serializer export `ObjectMapper`s by serializer-set signature instead of rebuilding one per
  export. No observable behaviour changes.

- **`HttpRequest.withBody((String) null)` now leaves the body unset**: a null string body passed to a
  request builder no longer coerces to an empty `StringBody` — the body field stays `null`, exactly as
  `HttpResponse.withBody((String) null)` has always behaved. `getBodyAsString()` therefore returns `null`
  (not `""`) for such a request, and the request serializes with no `body` field at all. Body matching is
  unchanged: a request expectation built with a null string body still matches any body, just as before.
  Passing an empty string (`withBody("")`) is unaffected and still produces an empty `StringBody`.

- JSON Schema body matching no longer resolves remote `$ref`s (http/https/file/jar/ftp) by default — a
  security hardening against SSRF / unexpected network fetches. Schemas using only internal/inline refs are
  unaffected; set `-Dmockserver.jsonSchemaAllowRemoteRefs=true` to restore remote resolution.

- **Faster request matching with many expectations**: the incoming request's headers, cookies, query
  parameters and path parameters are now converted into their internal matcher form once per request and
  reused across every candidate expectation, instead of being rebuilt for each expectation evaluated. The
  conversion is keyed by data-plane/control-plane and is invalidated whenever the request collection is
  mutated mid-match (e.g. query-parameter splitting), so matching behaviour is unchanged. This reduces
  per-request allocations and CPU when matching against large expectation sets.

#### Forwarding & proxying
- **Upstream forward connection pooling is now safe-by-default (`forwardConnectionPoolEnabled`, default `true`).**
  MockServer pools and reuses idle HTTP/1.1 keep-alive upstream connections (keyed by host, port and
  scheme) for forwarded and proxied requests instead of opening a fresh upstream connection per request,
  which avoids exhausting the operating system's ephemeral local ports under sustained forward load — a k6
  baseline measured 21% request errors at 750 rps and 68% at 1500 rps (212k `BindException`s from local
  port exhaustion), which pooling drives to ~0% errors with no latency regression in a controlled A/B.
  Pooling is on by default because two independent guards make reuse safe: (1) the outbound forward/proxy
  HTTP client now runs on its **own dedicated event-loop group, disjoint from the server worker group**
  (sized by `clientNioEventLoopThreadCount`, default 5), so a pooled channel reused inside a synchronous
  local object-callback — which runs on a server worker thread and makes a blocking loopback call back to
  the same server — is never pinned to the worker thread blocked in that callback (which would otherwise
  self-deadlock the event loop until the forward timeout); and (2) a channel is only returned to the pool
  when its HTTP client codec is **genuinely quiescent** — a valid in-range status (100–599) is necessary
  but not sufficient, so the decoder must additionally have zero leftover undecoded bytes, and any
  uncertainty fails closed (the channel is closed, not pooled). MockServer's `error()` action (HttpError),
  which deliberately returns raw non-HTTP bytes and/or drops the connection, is therefore never pooled and
  can never desynchronise a later reuse. Set `mockserver.forwardConnectionPoolEnabled=false` (env
  `MOCKSERVER_FORWARD_CONNECTION_POOL_ENABLED=false`) to restore the historical fresh-connection-per-request
  behaviour. Only plain HTTP/1.1 keep-alive upstreams are pooled — HTTP/2, HTTP/3, binary forwarding,
  streaming (SSE) responses, proxy-tunnelled connections, and any upstream that closed the connection or
  returned `Connection: close` always use a fresh connection.


### Fixed

#### Correctness & reliability
- **Load-generation cap properties are now serialized through the configuration DTO** (`mockserver-core`). The
  `loadGenerationMaxRate`, `loadGenerationMaxStages`, `loadGenerationMaxConcurrentScenarios` and
  `loadScenarioInitializationJsonPath` properties were read from system properties but missing from
  `ConfigurationDTO`, so they were dropped by `GET`/`PUT /mockserver/configuration` round-trips and by
  `applyTo()`. They are now mirrored like the other `loadGeneration*` properties.
- **Load-injection traffic no longer floods the request log** (`mockserver-core`). A running load scenario
  generated requests that were recorded in the bounded request event log (`maxLogEntries`), so a sustained
  run filled the log and evicted real and LLM traffic — emptying the Traffic, Trace, and LLM Optimise views
  (and the dashboard log) while the load ran. Load-generation requests are now marked and kept out of the
  event log, so other traffic is preserved. Load throughput/latency metrics and SLO samples are unaffected
  (they are recorded independently of the event log).
- **`crossProtocolScenarios` was rejected by the expectation schema** (`mockserver-core`). The
  cross-protocol scenario-correlation field was present in the model and honoured at runtime, but it
  was missing from the expectation validation schema, so any expectation that used it was rejected with
  `400 incorrect expectation json format` — making the documented feature unusable via the JSON / REST
  API (and therefore from every client). Added `crossProtocolScenarios` to both `expectation.json` and
  the embedded OpenAPI model.
- **Rust client rejected expectations with a finite `times` / `timeToLive`** (`mockserver-client-rust`).
  `Times` and `TimeToLive` required an `unlimited` field when deserializing the server's response, so
  creating an expectation with e.g. `times(once())` failed with `missing field 'unlimited'`. The field
  now defaults to `false` when absent.
- **GraalVM Engine leak in the JavaScript template engine** (`mockserver-core`). The recent JS-template-engine
  caching change built a new native GraalVM `Engine` per `JavaScriptTemplateEngine` instance (and a per-instance
  thread-local `Context`) that was never closed; since the engine is constructed per call/handler/test, native
  Engines accumulated (each pinning Truffle/compiler threads and native memory), which exhausted the reused test
  fork and timed out CI. The `Engine` is now a single process-wide instance (shared across all template engines,
  the standard GraalVM pattern), and `JavaScriptTemplateEngine`/`PolyglotRunner` gain a `close()` that disposes
  the thread-local `Context` (used by short-lived render paths). Rendering output and the `Java.type(...)`
  class-lookup security boundary are unchanged (per-instance class filter retained).

#### Documentation site & dashboard
- **Docs left-nav showed "Examples" twice and re-ordered between pages** (`jekyll-www.mock-server.com`). Removed a
  leftover hardcoded "Examples" nav link that duplicated the auto-listed `examples.html` page, and made every
  in-section `pageOrder` unique so `site.pages | sort` is stable (duplicate orders rendered in an unstable order,
  shuffling the nav as you navigated).
- **Dashboard "Mocks" tab description clarified** (`mockserver-ui`) to mention quick mode (common cases) vs advanced
  mode (full control).

#### Correctness & reliability (code-quality review)
- **Log timestamps are now thread-safe** (`mockserver-core`). The event log shared a single mutable
  `SimpleDateFormat` formatted concurrently from the log handler and the retrieve/export threads; under load
  this produced garbled timestamps and occasional `ArrayIndexOutOfBoundsException`. It now uses an immutable
  `DateTimeFormatter` (same output format and local timezone), so timestamps are correct under concurrency.
- **Compiled-regex caching in request matchers is now safely published** (`mockserver-core`). `NottableString`
  memoises its compiled `Pattern`s in fields that were not `volatile`, so concurrent first use could observe a
  partially-published value; the fields are now `volatile` (a rare duplicate compile is harmless).
- **gRPC chaos injection now honours its configured probability** (`mockserver-core`). The decision used
  a fresh seeded `Random` per request, so a probability behaved as all-or-nothing; it now samples through
  the shared chaos-probability helper like the HTTP/TCP paths.
- **Load-scenario virtual-user accounting is now exact** (`mockserver-core`). Active-VU slots are
  decremented exactly once per VU at every loop-exit (a stop mid-iteration previously leaked a slot), and
  virtual-user id allocation is separated from the live-population counter.
- **OIDC device-code poll counter is race-free** (`mockserver-core`). Concurrent device-code polls now
  decrement an atomic counter instead of a plain field, so no decrements are lost.
- **SCIM resource updates are atomic** (`mockserver-core`). Concurrent PATCH/PUT of the same SCIM resource
  no longer lose writes — the read-modify-write is serialized per store.
- **LLM conversation matching is safely published across threads** (`mockserver-core`). Lazily-built
  conversation matchers/patterns on shared expectations are now `volatile`, fixing an unsafe-publication
  data race on the concurrent match path.
- **Configuration round-trip no longer drops properties** (`mockserver-core`). `ConfigurationDTO`
  mirrored only about half of the configuration properties, so many settings (SLO tracking, load
  generation, drift alerting, HTTP/3, gRPC, DNS, WASM, clustering, blob store, transparent proxy,
  async messaging, control-plane OIDC, forward connection-pool/retry/circuit-breaker, body-size limits,
  redaction, OpenTelemetry, audit, and more) were silently lost when configuration was serialized and
  reloaded via `/mockserver/configuration`. All properties are now mirrored, with a reflection-driven
  test that fails if a future property is added without being mapped.
- **Generated curl / Java / HAR output is now correctly escaped** (`mockserver-core`). `curl` snippets
  use the POSIX `'\''` idiom for header names, values and bodies (previously breakable/injectable);
  generated Java escapes the method and path like other literals; HAR query parameters are now actually
  URL-encoded.
- **Expectation persistence writes atomically** (`mockserver-core`). The file blob store now writes to a
  temporary file and atomically renames it into place, so a concurrent reader (including the file watcher
  when the initialization and persistence paths are the same file) can never observe a truncated file
  mid-write. The file watcher also no longer treats a transient read error as a content change.
- **Callback WebSocket registry is now thread-safe** (`mockserver-core`). The response, forward and
  stream-frame callback registries backing `mockWithCallback`/forward callbacks and breakpoints were
  unsynchronized maps shared between request-handling threads and the Netty I/O thread; under concurrent
  load this could drop callbacks or corrupt the registry. They are now synchronized like the client
  registry, and registry iteration is guarded.
- **Reused log entries no longer leak stale data** (`mockserver-core`). Recycled event-log entries (the
  log uses a ring buffer) could retain a previous entry's redacted/templated request or cached hash,
  which could make equal entries compare unequal; the entry is now fully reset on reuse.
- **Path/matrix parameter names containing regex metacharacters** (e.g. `.`, `+`, `$`, `\`) are now
  matched literally during extraction instead of corrupting the match or throwing (`mockserver-core`).
- **Matchers prefixed with only `?` or `!`** (e.g. the string `"?"`) no longer throw
  `IndexOutOfBoundsException` when constructed (`mockserver-core`).
- **`VerificationTimes` rejects negative counts** with a clear error instead of silently matching any
  number of requests (`mockserver-core`).
- **Connection-tunnel buffer leak fixed** (`mockserver-netty`). The CONNECT/SOCKS relay handshake handler
  released its inbound buffer on the handled path, fixing a small pooled-buffer leak per tunnel.
- **One client's `reset()`/`stop()` no longer tears down other clients on the same port**
  (`mockserver-client-java`). The per-port event bus now removes only the subscribers for the published
  event type instead of clearing all subscribers.
- **S3 expectation/recorded-request persistence** no longer throws on an empty/missing prefix listing,
  matching the GCS and Azure stores (`mockserver-blob-s3`).

#### Consistency
- **Client default MockServer version aligned to the released version** across the Node, Rust, Python and
  PHP clients (the Node client now reads it from its own `package.json`), so each client no longer
  defaults to downloading a stale server binary.
- Removed two orphaned duplicate resource files left at the repository root by the monorepo migration.

#### AI / LLM
- The `model` field on an LLM response `completion` now round-trips through expectation
  serialization: it is permitted by the `httpLlmResponse` JSON schema, so a captured completion
  carrying a `model` no longer fails schema validation on `PUT /mockserver/expectation`.

#### Dashboard UI
- **Dashboard UI efficiency & consistency fixes** (code-quality review):
  - The live data store reconciles rows by serializing each item at most once per update (was serializing
    every row twice on every WebSocket push), reducing CPU on busy dashboards while preserving object
    identity so unchanged rows don't re-render.
  - The traffic inspector memoizes its rows and caches per-row search text, so typing in the filter and
    incoming traffic no longer re-render the whole list.
  - The service-chaos panel's one-second countdown timer only runs when a registration actually has a TTL.
  - gRPC-health, cassette and file-store API errors now use the same actionable error shape as the rest of
    the dashboard, and a few call sites now humanize errors consistently.
  - Filter-panel icon buttons have accessible labels/tooltips.
- **Dashboard UI correctness fixes** (from an adversarial review):
  - The dashboard no longer crashes to a blank white screen when a view fails to load (e.g. the Metrics
    chunk after a redeploy) — views are wrapped in an error boundary that offers a reload/retry and
    keeps the navigation usable.
  - The Drift panel no longer hides failures: a failed "Clear" used to report success and a server
    error used to look like "no drift detected". Errors now surface, and a server that doesn't support
    drift correctly shows the "not available" notice.
  - The "Capture as mock" dialog no longer shows a stale error or stale edits when reopened, and a body
    matcher can now be added even when the captured request had no body.
  - The mock import dialog no longer reports a misleading "Imported 0 expectations" on an unusual
    success response.
  - The traffic comparison's "Diff (N/2)" counter and button state no longer disagree after a refresh
    drops a selected request.
  - Non-HTTP (e.g. gRPC) expectations no longer render their id twice in the Active Expectations list.

- Dashboard `favicon.svg` (and any future SVG asset) is now served with a valid `Content-Type: image/svg+xml`
  instead of a null header value. The `svg` extension was missing from the dashboard's MIME-type map, so the
  asset response carried a `Content-Type` header with a `null` value, which crashed Netty's header encoder
  (`NullPointerException: value`) and left the asset failing to load. The MIME map now includes `svg` and falls
  back to `application/octet-stream` for any unmapped extension, and the response→Netty mapper now skips any
  header whose value is null rather than throwing while encoding the response (issue #2358).

#### IDE extensions (VS Code & JetBrains)
- **JetBrains IDE plugin no longer capped to old IDE builds**: the plugin previously declared
  `untilBuild=253.*`, which excluded it from newer IDEs (e.g. build 261+) and triggered JetBrains
  Marketplace "limited `until-build`" warnings. Compatibility is now left open-ended (empty
  `until-build`), so the plugin stays available in current and future JetBrains IDE releases. The
  plugin uses only stable, public platform APIs; the Plugin Verifier (`verifyPlugin`) remains the
  backstop for any real future incompatibility.

- JetBrains plugin no longer risks an `AlreadyDisposedException` when a project (or tool window) is closed
  while an extension HTTP request is still in flight — the result is now delivered on the UI thread through a
  single shared, project-disposal-guarded helper.

- VS Code extension now activates on startup (`onStartupFinished`), so the MockServer status-bar item and the
  expectation-file CodeLens appear immediately on a fresh window instead of only after the first command is
  run from the palette.

- JetBrains plugin **Start (Docker)** now checks the Docker daemon is reachable before launching and reports a
  clear "Docker is not running" error instead of showing a success notification while nothing actually
  started; the Docker check and launch run off the UI thread.

- JetBrains plugin now provides JSON Schema **completion and validation** for `*.mockserver.json(c)` expectation
  files. Three issues blocked it: (1) the JSON-schema provider was registered under the wrong extension point
  (`com.intellij.json` / `jsonSchema.ProviderFactory`) — IntelliJ's real EP is `JavaScript.JsonSchema.ProviderFactory`,
  so the provider was never instantiated (this is why it never worked at all in IntelliJ); (2) the bundled
  schema's root was a `oneOf` (single expectation or array), which IntelliJ's JSON engine cannot navigate; and
  (3) the schema referenced the draft-07 meta-schema by its remote `http://json-schema.org/draft-07/schema#`
  URL for embedded-schema fields, which IntelliJ tries to **fetch over the network** — failing silently offline
  or behind a TLS proxy and making it discard the whole schema. The provider is now registered under the
  correct EP, the root is a concrete object/array union with inline properties, and the embedded-schema fields
  use a permissive inline schema (no network fetch), so completion and error highlighting work in IntelliJ
  (VS Code already tolerated the schema shape). The schema was also regenerated against the current
  `mockserver-core`, picking up previously-missing fields (e.g. the `capture` rule, `namespace`, response `trailers`).

#### Request matching & response generation
- **Response-modifier fidelity in codegen and the Node typedef**: `retrieve?format=JAVA` now emits the response
  modifier's `condition`, `modifiers` chain, `jsonPatch`, and `jsonMergePatch` (the patch fields are emitted via
  new `withJsonPatch(String)` / `withJsonMergePatch(String)` convenience overloads, so the generated DSL
  compiles and round-trips); and the Node client `mockServer.d.ts` `responseModifier` type now declares
  `condition`, `modifiers`, `jsonPatch`, and `jsonMergePatch`.

- **Response body matching now has full parity with request body matching**: matching a proxied/forwarded
  upstream response body previously used a stripped-down copy of the request body dispatch that was missing
  several behaviours — it did not convert an XML or form actual body to JSON before applying a JSON / JSON
  schema / JSON path matcher, ignored an optional template body, mis-routed a multipart matcher to the generic
  string path, and only checked the decompressed (not the original/compressed) bytes for a binary matcher.
  A JSON or XML matcher against a response with no body at all could also trigger an internal NullPointer that
  was swallowed into a silent non-match. Request and response body matching now share a single dispatch
  (`BodyMatching`), so a response body matcher behaves exactly like the equivalent request body matcher,
  including a clean non-match (no swallowed exception) when the response has no body. Request matching is
  unchanged.

- **`not(...)` expectations now match correctly with fail-fast matching enabled (the default)**: a negated
  request matcher (`not(request()...)`, or a request-matcher-level `not`) could wrongly report a non-match
  whenever a field other than the HTTP method matched before the first mismatching field. With the default
  `matchersFailFast=true`, the early-exit short-circuit negated a *partial* "failed so far?" signal through
  the NOT operators, producing a premature wrong verdict for any expectation with an odd number of NOT flags.
  For example `not(request().withMethod("GET").withPath("/test"))` failed to match an incoming `GET /other`
  even though it plainly is not `GET /test`. The fix only short-circuits when no NOT operator is in play
  (preserving the fail-fast optimisation for the common case) and evaluates all fields for NOT expectations,
  so the verdict now always equals a full evaluation. Affected the path, header and body fields.

- **Notted key in `MATCHING_KEY` mode now asserts key-absence**: a notted matcher key (e.g. `!X`) used with
  `KeyMatchStyle.MATCHING_KEY` (for headers/query/cookies) previously aggregated values from every key that
  was not `X` and matched against that bag — a meaningless result. It now means "no key equal to `X` is
  present", consistent with the default `SUB_SET` behaviour.

- **Clearer "closest expectation" diagnostics**: the "closest expectation matched X/Y fields" log no longer
  counts non-HTTP fields (DNS/binary/OpenAPI) in the denominator for an HTTP request, and no longer collapses
  the matched-field count to a misleading near-maximum when fail-fast matching is enabled — the count now
  reflects the fields actually compared. This is a diagnostic-only change (INFO level); matching behaviour is
  unchanged.

- **Control-plane body filter hardening**: a control-plane body filter (used by clear/verify/retrieve) no
  longer treats an absent body via stringification, removing a latent case where a literal `"null"` body
  filter could match a request with no body.

- **Scenario state no longer advances when a matching expectation is skipped**: a scenario expectation
  whose scenario state matched but which was then skipped by a percentage (`withPercentage`) gate
  previously advanced the scenario to its next state even though it was never served (a
  consume-then-skip bug). The scenario now transitions only at the point the expectation is actually
  served, and the transition is applied atomically (compare-and-set) so that on a clustered backend two
  nodes racing the same scenario step serve the response from exactly one node — preserving the
  documented cross-node "exactly one winner" guarantee.

- **Faster expectation registration**: registering large numbers of expectations on the default
  in-memory setup was O(n²) because each add triggered two full backend reconciliation passes. The
  non-clustered path now does a cheap eviction-only trim, restoring linear registration time;
  clustered reconciliation behaviour is unchanged.

#### Verification
- **Response verification no longer counts MockServer's own auto-generated no-match responses**: verifying a
  response (e.g. `verify(response().withStatusCode(404))`) or a response-aware sequence now counts only the
  responses your mocks actually produced (matched expectation responses and forwarded/proxied responses), not
  the default 404 MockServer returns for an unmatched request. Those auto no-match responses are still
  returned by `/retrieve` — they are excluded only from verification.

- **Response-aware verification sequences with mismatched request/response list lengths are now rejected**:
  a sequence that supplies both `httpRequests` and `httpResponses` but of different non-empty lengths
  previously padded the shorter list with nulls (which always matched), silently passing on the unspecified
  steps. Such a sequence now returns a clear error. A request-only or response-only sequence (one list empty)
  remains valid.

- **Response sequence verification no longer throws on a recorded pair with a null request**: a request-
  constrained step that encountered a recorded request/response pair whose request was null previously
  NPE'd and was masked as a generic "exception while processing verification sequence"; it is now correctly
  treated as a non-matching pair.

- **Failing response-sequence verification messages now show the responses**: a response-aware sequence
  failure now serialises the expected response sequence and the recorded responses (it previously reported
  the requests, often an empty `[]` for a response-only sequence).

- **An entirely-empty verification sequence is now rejected**: a `VerificationSequence` with no
  expectationIds, requests or responses previously passed vacuously; it now returns a clear error.

- **Verification no longer hangs if its request filter fails to build**: a verification whose request filter
  could not be turned into a matcher (e.g. an invalid OpenAPI/schema filter) previously left the verify
  result future uncompleted, hanging the caller; the failure is now logged and the verification completes.

- Rust client: `VerificationTimes::at_least(n)` now serializes an explicit `atMost: -1` (unbounded) sentinel.
  Previously `atMost` was omitted, and the server's primitive-`int` field defaulted it to `0`, turning
  `at_least(n)` into an impossible `between(n, 0)` constraint that always failed verification.

#### OpenAPI & contract testing
- **OpenAPI example generation now honours more JSON-Schema constraints**, so generated example data is
  less likely to fail a consumer's own validators:
  - Arrays emit `minItems` items (clamped to a small cap) instead of always a single element, and an
    explicit `maxItems` below 1 yields an empty array rather than a stray default item.
  - String `pattern` (regex) constraints produce a matching value (e.g. SKUs, phone numbers) instead of a
    generic word; an unsupported/invalid regex falls back to the previous behaviour rather than failing.
  - `exclusiveMinimum` / `exclusiveMaximum` (both the OpenAPI 3.0 boolean-flag and 3.1 numeric forms) are
    respected — the generated number sits strictly inside the open bound.
  - The `time` string format now produces a valid `HH:mm:ss` example (previously only `date` and
    `date-time` were format-aware).
  - `minProperties` on a free-form / `additionalProperties` object now emits at least that many entries
    (clamped to a cap of 10).
  - Unconstrained schemas are unchanged — there is no behaviour change when none of these constraints are
    present.

- **OpenAPI follow-ups (further re-review).**
  - **XML response bodies are now real, spec-correct XML.** When an OpenAPI response content type is XML
    (`application/xml`, `text/xml`, or a `+xml` suffix) MockServer now serialises the generated example as
    XML using the schema's `xml` metadata (`name`/`namespace`/`prefix`/`attribute`/`wrapped`) per the
    OpenAPI XML Object rules, instead of the previous JSON-shaped body. Array elements follow the spec
    (unwrapped → repeated elements named after the property; `wrapped: true` → a wrapper element), fixing the
    earlier malformed pluralised output, and a recursive schema (e.g. `Node{left:$ref Node, right:$ref Node}`)
    now renders each property under its own element name rather than dropping one, and array items without an
    explicit `items.xml.name` are named after the array property (e.g. `<tags>`) instead of the literal
    `<array>`, and a recursive `$ref` the parser cannot inline (e.g. `Tree{children:[$ref Tree]}`) now renders
    its items/properties under the property name (`<children>`) rather than the schema component name
    (`<Tree>`), and an object that declares an element-typed property *before* an attribute-typed one
    (`xml.attribute: true`) now renders correctly — attributes are written immediately after the start
    element regardless of declaration order, instead of aborting the write (`Attribute not associated
    with any element`) and returning an empty body. (Behaviour change for XML
    responses; JSON responses are unchanged.)
  - **OAS 3.1 multi-type `type` arrays are preserved** when serialising a schema: `type: ["string","null"]`
    now becomes `type: string` + `nullable: true` (and `["string","integer"]` is kept as a Draft-07 type
    array) instead of being silently dropped.
  - **Negated request matchers re-validate.** The `not` flag (emitted as `"not": true` by the request and
    OpenAPIDefinition serializers) is now declared in the `httpRequest` and `openAPIDefinition` JSON schemas,
    so a serialized negated matcher no longer fails schema validation on re-import.
  - **Validation errors are meaningful and bounded.** Unexpected exceptions during request/response/contract/
    traffic validation are now reported with context (request vs response, operation, aspect) and the
    exception type rather than a raw — sometimes `null` — message, capped to a single bounded line; the full
    throwable is logged.
  - A generated string example with a `null` value now renders as an empty XML element rather than the
    literal text `null` (e.g. `<field></field>` instead of `<field>null</field>`).

- **OpenAPI handling hardened across both directions (audit follow-up to #2357).** A review of the
  OpenAPI subsystem found and fixed a batch of correctness defects:
  - **Range status-code keys** (`1XX`–`5XX`, legal in OpenAPI 3.x) no longer crash a spec import. A
    response key such as `2XX` previously threw `NumberFormatException` and aborted the entire
    `PUT /mockserver/openapi`; it now maps to a representative code (`2XX`→200). The response
    validator likewise now matches a real status against a range key instead of reporting a false
    "status not defined" error (exact code still wins over range, range over `default`).
  - **No more cross-spec data loss on import.** The incremental-sync namespace was derived from
    `info.title` alone, so importing a second spec that shared a title with a first **deleted** the
    first's generated expectations. The namespace now uses a SHA-256 of the spec source (URL/file
    reference, or inline payload), so distinct specs never collide. Note: re-importing a spec **by
    URL/file** still prunes removed operations as before; an **edited inline payload** now lands in a
    new namespace (its old operations are orphaned rather than pruned) — reference a spec by URL/file
    for clean incremental sync. Manual (non-OpenAPI) expectations are never pruned.
  - **Expectations→OpenAPI export** (`/retrieve?format=openapi`) now produces schema-valid, faithful
    documents: `in:path` parameters are templated into the path, negated/schema matchers are no
    longer silently exported as their positive literal form, two expectations on the same path+method
    merge their responses instead of one overwriting the other, paths are normalised to a leading
    `/`, body media types follow the actual `Content-Type` (binary → `format: binary`, not base64
    text), and a serialisation failure returns a valid stub rather than `{}`.
  - **`contextPathPrefix` on an `OpenAPIDefinition` matcher** is now accepted by its JSON schema and
    emitted by the model serializer (it was honoured at runtime but rejected on validation and dropped
    from `toString()`/logs).
  - **No more silent wrong/empty responses.** Pinning a `statusCode`/`exampleName` that a spec does
    not define now logs a warning and falls back deliberately instead of silently returning an empty
    `200` or a different example; an object whose properties only partly carry inline examples still
    generates a COMPLETE example body (explicit examples are honoured and a sample is generated for the
    rest, rather than dropping the sample-only properties); unresolvable example `$ref`s no longer leak literal `{"$ref":…}`
    nodes into response bodies; and synthesised `operationId`s are now globally unique so a
    hand-written id like `GET /pets` can't conflate two operations.
  - **Example generation:** `integer`/`number` schemas without a `format` now honour `default`/`enum`
    (previously emitted `0`/a random value); plus minor precision/locale fixes (double sample value,
    UTC date formatting, large-integer examples preserved). (Generating real XML — rather than the
    long-standing JSON-shaped body — for `application/xml` responses is deferred to a dedicated change;
    the response format is unchanged here.)
  - **Second-round fixes (re-review of the above).** A valid OAS 3.1 **webhooks-only** spec (no `paths:`)
    no longer throws (it previously NPE'd the `PUT /mockserver/openapi` import and the validators); the
    expectations→OpenAPI **export** is schema-valid for more inputs (path templates without a matching
    parameter get a synthetic one, non-OpenAPI HTTP methods like `CONNECT` are skipped, duplicate
    `operationId`s are de-duplicated); request/traffic validation now prefers the most specific
    (concrete-over-templated) path and matches range status keys case-insensitively; and
    `OpenApiTrafficValidator` isolates per-request failures instead of aborting the whole batch.
  - **Re-importing a spec by URL/file now picks up its current content.** The parsed spec is LRU-cached
    (up to 30 minutes) keyed by the reference string, so re-importing a URL/file whose content had
    changed previously returned the stale parse. `PUT /mockserver/openapi` now evicts that cache entry
    before re-importing a URL/file spec, so incremental sync reflects the latest content (inline payloads
    are keyed by content and were already correct).

- **OpenAPI example generation** no longer wraps a scalar property in a single-element array when its schema
  is `allOf: [ $ref to a scalar ]` (e.g. a string). Previously a property such as
  `baz: { allOf: [ $ref to a type:string schema ] }` generated `{ "baz": ["hello"] }` instead of
  `{ "baz": "hello" }`, which broke generated clients typed against the spec
  (`MismatchedInputException: Cannot deserialize value of type 'java.lang.String' from Array value`).
  The `allOf`-flattened example aggregated by the parser is now unwrapped to the scalar value, matching
  JSON-Schema `allOf` semantics (#2357).

#### CLI & configuration
- **mTLS server startup with a supplied leaf+CA certificate chain on Java 17**: when MockServer was
  configured with a TLS X.509 certificate file that already contained the full chain (leaf followed by
  the CA), the CA was appended a second time, producing a `[leaf, CA, CA]` chain. Java 17's PKCS12
  keystore rejects the duplicate with `KeyStoreException: Certificate chain is not valid`, so the server
  failed to start its TLS context (Java 21 tolerated it, masking the bug). The certificate chain now
  de-duplicates the CA, so a supplied full-chain PEM yields `[leaf, CA]` and mTLS starts correctly on
  Java 17.

- Stop leaking the vulnerable `commons-beanutils` (1.9.4 and, via `commons-digester3`, 1.8.3) to downstream
  consumers through `velocity-tools-generic`. These transitive versions are affected by GHSA-wxr5-93ph-8wr9
  (CVE-2025-48734). MockServer's own build already pinned 1.11.0, but that pin lived in
  `dependencyManagement`, which is not transitive, so consumers of `mockserver-core` still resolved the
  vulnerable versions. `commons-beanutils` is now excluded from `velocity-tools-generic` and declared as a
  direct dependency at 1.11.0 so the fixed version propagates to consumers. (#1981)

#### Clustering & observability
- **CPU no longer climbs as the request/event log fills when using `/retrieve` and `clear` under sustained load (issue #2359, a follow-up to #2329).** The #2329 fix made log *insertion* O(1); this fixes the remaining cost on the *read* paths. `retrieveLogEntries` (used by every `/retrieve`) and `retrieveLogEntriesInReverseForUI` ran the expensive request matcher — which clones the request and runs full field-by-field matching — on **every** entry in the log *before* the cheap type/not-deleted filter, so the matcher was evaluated against deleted tombstones and wrong-type entries that were then discarded. As the log fills toward `maxLogEntries` (and clearing *expectations* does not clear the *log*, while `clear` at the default `INFO` level only marks entries deleted rather than removing them), this made each `/retrieve` cost grow with total log size and stay high. The filters are now ordered cheap-predicate-first (matching the existing expectation-id retrieve path), so the matcher only runs for entries that can actually be returned. Additionally, `clear` now skips entries already marked deleted, so a `clear` issued every test cycle no longer re-matches the whole accumulated log of tombstones. No behaviour change — same retrieved results and same clear semantics. Tip for high-throughput users (unchanged from #2329): also clear the log (`PUT /mockserver/clear?type=LOG` or `?type=ALL`, or `PUT /mockserver/reset`), not just expectations, or lower `maxLogEntries`.


## [7.1.0] - 2026-06-16

### Added

### Changed

### Fixed

## [7.1.0] - 2026-06-16

### Added

### Changed

### Fixed

## [7.1.0] - 2026-06-16

### Added

### Changed

### Fixed

## [7.1.0] - 2026-06-16

### Added

### Changed

### Fixed

## [7.1.0] - 2026-06-16

### Added

### Changed

### Fixed

## [7.1.0] - 2026-06-15

### Added

### Changed

### Fixed

## [7.1.0] - 2026-06-15

### Added

### Changed

### Fixed

## [7.1.0] - 2026-06-15

### Added

#### Verification
- **Verify responses received from proxied/forwarded systems** — verification now optionally matches the **response** of a recorded request-response exchange, not just the request. Add an `httpResponse` matcher to a verification (`PUT /mockserver/verify` with `{httpRequest?, httpResponse, times}`) and MockServer counts recorded request-response pairs (proxied/forwarded exchanges) whose response matches — by status code, reason phrase (regex), headers, and body (JSON, JSON schema, JSONPath, XML, XPath, regex, etc., reusing the existing request body matchers). When `httpRequest` is also supplied, both must match. `verifySequence` gains an index-aligned `httpResponses` list so an ordered sequence can assert on responses too. The `verify`/`verifySequence` call shape and `VerificationTimes` are unchanged — the presence of a response matcher is what switches verification from "request received" to "response received". When no response matcher is supplied, behaviour is identical to before.

#### Breakpoints & request replay
- **Matcher-driven breakpoints** — breakpoints are toggled per-request via a matcher rather than by global config flags. You register a **request matcher** (works exactly like an expectation request matcher) together with the phases to break at: `PUT /mockserver/breakpoint/matcher` with `{httpRequest, phases:["REQUEST"|"RESPONSE"|"RESPONSE_STREAM"|"INBOUND_STREAM"], clientId:"..."}`. A forwarded/proxied exchange whose request matches a registered breakpoint pauses at the selected phase(s). Manage matchers via `GET`/`PUT /mockserver/breakpoint/matchers`, `PUT /mockserver/breakpoint/matcher/remove` (`{id}`), and `PUT /mockserver/breakpoint/matcher/clear`; the registry is cleared on `/mockserver/reset`. The `breakpointTimeoutMillis` (30000) and `breakpointMaxHeld` (50) safety rails are retained.
- **`clientId` required for breakpoint registration; callback WebSocket is the resolution transport** — `PUT /mockserver/breakpoint/matcher` requires a `clientId` field (the callback WebSocket client id); omitting it returns 400. Breakpoints are resolved interactively over the callback WebSocket only — all clients (including the dashboard) resolve breakpoints over that channel.
- **Interactive breakpoint resolution over the callback WebSocket** — a matching forwarded REQUEST or RESPONSE exchange is dispatched to the owning callback-WebSocket client (the same `/_mockserver_callback_websocket` channel `forwardObject`/`responseObject` clients use) for interactive resolution: the client replies with a modified request (forward), a response (abort/replace), or the original (continue). Shares the `breakpointTimeoutMillis` auto-continue and `breakpointMaxHeld` cap rails; a client disconnect removes its breakpoints and auto-continues anything it was holding.
- **Per-frame streaming breakpoints over the callback WebSocket** — RESPONSE_STREAM (outbound) and INBOUND_STREAM (client→server) breakpoints resolve interactively over the callback WebSocket across all nine streaming hold points (SSE/chunked, HTTP/3 gRPC, gRPC server-streaming, WebSocket eager/bidi, GraphQL-subscription, and the WebSocket/GraphQL/gRPC-bidi inbound paths). Two WS message types form the frozen per-frame protocol: a server→client `PausedStreamFrameDTO` (`correlationId`, `streamId`, `sequenceNumber`, `direction`, `phase`, base64 `body`, request method/path) and a client→server `StreamFrameDecisionDTO` (`correlationId`, `action` ∈ CONTINUE/MODIFY/DROP/INJECT/CLOSE, optional base64 `body`). Event-loop safe (decisions marshalled onto the channel event loop, frame bytes copied to `byte[]`), with ordering and backpressure preserved and the shared timeout/max-held rails + client-disconnect auto-continue. The per-server WebSocket registry is injected per-channel (no process-global state).
- **Java client breakpoint API (matcher + callback handlers)** — `MockServerClient.addBreakpoint(matcher, phases…, handlers…)` registers a breakpoint matcher and resolves paused exchanges interactively over the callback WebSocket, with typed handlers per phase: `BreakpointRequestHandler` (return a request to forward/modify or a response to abort), `BreakpointResponseHandler` (return the response to write), and `BreakpointStreamFrameHandler` (return a CONTINUE/MODIFY/DROP/INJECT/CLOSE decision). Plus `listBreakpointMatchers()`, `removeBreakpointMatcher(id)`, `clearBreakpointMatchers()`. The client lazily opens one callback-WS connection (reused across breakpoints) and tears it down on stop/reset. **Per-matcher handler routing:** each pushed paused item carries the matched breakpoint's id (a new `X-MockServer-BreakpointId` header for request/response and a `breakpointId` field on the stream-frame message), so each breakpoint routes to its own handler rather than a single shared per-phase handler. This is the reference API the other language clients mirror.
- **Node, Python & Ruby client breakpoint APIs** — the Node, Python, and Ruby clients gain the same matcher-driven breakpoint API as the Java client (`addBreakpoint`/`add_breakpoint` + convenience overloads, `list`/`remove`/`clear` breakpoint matchers), resolving paused request/response/stream-frame exchanges interactively over each client's existing callback WebSocket with per-matcher handler routing (by the `X-MockServer-BreakpointId` header / `breakpointId` frame field). Idiomatic per language (typed objects in Node, dicts in Python, hashes in Ruby); handlers auto-continue on error or missing handler so a buggy handler can't hang the exchange.
- **Go, .NET & Rust client breakpoint APIs (new callback-WebSocket stacks)** — the Go, .NET, and Rust clients gain a full callback-WebSocket stack (Go `gorilla/websocket`, .NET built-in `ClientWebSocket`, Rust `tungstenite`) plus the matcher-driven breakpoint API (`addBreakpoint`/`AddBreakpoint`/`add_breakpoint` + convenience overloads, list/remove/clear breakpoint matchers). Each connects to `/_mockserver_callback_websocket`, registers a `clientId`, and resolves paused request/response/stream-frame exchanges over the callback WebSocket with per-matcher handler routing, auto-continuing on handler error/panic. Concurrency-safe (serialised WS writes + lazy init; Go verified with `-race`) and reconnect-on-dead-connection. PHP is excluded (no WebSocket support). This completes breakpoint support across seven clients (Java, Node, Python, Ruby, Go, .NET, Rust).
- **Stream frame breakpoints (backend)** — per-frame hold/modify/drop/inject/close for all streaming response types: forwarded SSE/HTTP/1.1 chunked, gRPC server-streaming, WebSocket, GraphQL-subscription, and HTTP/3 gRPC. Each frame is intercepted at its hold point, parked in `StreamFrameBreakpointRegistry`, and resolved over the callback WebSocket. Fully non-blocking (event-loop safe), with backpressure, ordered frame resolution, stream-close eviction, timeout auto-continue, and the shared `breakpointMaxHeld` cap. Activated when a matching `RESPONSE_STREAM` breakpoint matcher is registered (zero overhead otherwise).
- **Inbound (client→server) breakpoints for gRPC bidi over HTTP/3 (QUIC)** — extends `INBOUND_STREAM` breakpoints to bidirectional gRPC streaming over HTTP/3, the QUIC analogue of the HTTP/2 gRPC-bidi inbound path (`Http3GrpcBidiStreamHandler`). Each inbound gRPC DATA frame is parked before decoding and resolved over the callback WebSocket (continue/modify/drop/inject/close); default-off (only when an `INBOUND_STREAM` matcher matches the stream). Because the QUIC driver copies each frame to `byte[]` and releases it before handing off, no `ByteBuf` is held and the QUIC flow-control window is never pinned; per-frame ordering is preserved by dispatching one frame at a time and buffering the rest (bounded by `maxRequestBodySize`). This completes interactive breakpoints across HTTP/1.1, HTTP/2, and HTTP/3.
- **Dashboard Breakpoints panel (callback-WebSocket client)** — the dashboard is a real callback client: it connects to `/_mockserver_callback_websocket` (the server assigns it a `clientId`, since a browser WebSocket can't send the registration header) and resolves paused exchanges live over the callback WebSocket — no REST polling. The panel has three tabs: **Matchers** (register a breakpoint matcher with a method/path matcher + phase checkboxes; list/remove/clear), **Live Exchanges** (paused requests/responses arrive in real time — Continue / Modify the JSON / Abort), and **Live Streams** (paused stream frames — Continue / Modify / Drop / Inject / Close; direction badge distinguishes INBOUND from OUTBOUND frames). A connection-state indicator shows the callback-WS status.
- **Request replay from the dashboard** — a new `PUT /mockserver/replay` control-plane endpoint re-issues a previously recorded/proxied request to its original target and returns the upstream response (reuses the existing `NettyHttpClient`/forward client; 10 MB body-size cap; behind control-plane auth). The dashboard Traffic view gains a Replay button on every selected request that opens a dialog to re-issue the request with one click and inspect the live response. The Java client exposes a typed `replay(HttpRequest)` method wrapping the endpoint.
- **Inbound bidirectional frame breakpoints (backend)** — intercepts client-to-server frames on WebSocket, GraphQL-subscription, and gRPC-bidi connections before MockServer processes them. Each inbound frame is copied to byte[], the original ByteBuf/Http2DataFrame is released immediately (refunding the HTTP/2 flow-control window), and the copy is parked in `StreamFrameBreakpointRegistry` with `direction=INBOUND`. Resolved over the callback WebSocket. Fully non-blocking with backpressure (autoRead paused for WebSocket/GraphQL; pull-based ctx.read() withholding for gRPC-bidi), channel-close eviction. Activated when a matching `INBOUND_STREAM` breakpoint matcher is registered (zero overhead otherwise).

#### OpenAPI
- **Full OpenAPI 3.1 support** — MockServer now fully supports OpenAPI 3.1 specifications, including the three constructs previously documented as partially handled: `type` as an array (e.g. `type: [string, "null"]`) now generates correct example values for the primary non-null type; `$ref` siblings (description alongside `$ref`) are resolved by the parser; and the `webhooks` top-level key is parsed and its operations are included when generating expectations, matching requests, and validating responses. No specification changes or version downgrades are required.

#### Chaos engineering
- **Scheduled multi-stage chaos experiments** — a new `PUT /mockserver/chaosExperiment` endpoint starts an ordered sequence of chaos stages, each applying service-scoped chaos profiles for a configurable duration before automatically advancing to the next stage. Supports looping, status polling via `GET /mockserver/chaosExperiment`, graceful stop via `DELETE /mockserver/chaosExperiment`, and integrates with the C1 auto-halt circuit-breaker (an experiment halts if the safety threshold is exceeded mid-stage). Max 50 stages, 24 h per stage, one active experiment at a time.
- **Chaos auto-halt circuit-breaker** — when enabled (`chaosAutoHaltEnabled=true`), MockServer automatically disables all active service-scoped chaos profiles if the number of chaos-injected errors within a sliding window exceeds a configurable threshold, preventing chaos experiments from causing cascading outages. Reflected in the `mock_server_chaos_auto_halt_total` Prometheus counter and a WARN log event.
- **Dashboard Chaos tab — full HTTP fault-type controls** — the HTTP Service Chaos register/edit form now exposes every `HttpChaosProfile` field: Retry-After header, body truncation fraction, malformed body toggle, slow (dribbled) response chunk size/delay, quota rate-limiting (name/limit/window/error status), degradation ramp, and outage time window — so users can configure the complete fault set without writing JSON.

#### LLM observability & cost control
- **LLM proxy/forward observability** — observability that previously fired only for *mocked* LLM responses now also covers LLM traffic **forwarded/proxied** through MockServer. With `otelTracesEnabled`, MockServer emits a GenAI OpenTelemetry span (provider, model, token usage, finish reason) for forwarded LLM responses, using a new provider sniffer that detects the upstream from the target host (with a path-gated fallback to `llmProvider`); all forward paths (matched-forward, unmatched proxy-pass, breakpoint-continuation) now also emit the generic request span consistently. The agent-run analysis tools (`explain_agent_run`, `verify_tool_call`) accept `provider:"AUTO"` for provider auto-detection from recorded request paths, and the dashboard Sessions view renders the call graph for proxy-only sessions, grouping unscoped traffic by upstream host. Off by default; fully fail-soft (telemetry never affects the forwarded response).
- **LLM token/cost Prometheus metrics** — when `llmMetricsEnabled=true` (alongside `metricsEnabled`), three new Prometheus counters track cumulative LLM token usage and estimated cost across all served and forwarded completions: `mock_server_llm_input_tokens`, `mock_server_llm_output_tokens`, `mock_server_llm_cost_usd`, each labeled by `provider` and `model`. The forward-path response parse is gated on metrics OR tracing OR budget, so token tracking works without requiring full OTLP tracing. Default off to avoid parsing forwarded response bodies unless asked.
- **LLM cost-budget circuit-breaker** — `mockserver.llmCostBudgetUsd` sets a cumulative USD ceiling across all LLM completions (mocked + forwarded). When the running cost total exceeds the budget, unmatched LLM proxy forwards are blocked with a 429 response including the cumulative and budget amounts (mocked LLM responses are never blocked). Deterministic and fail-open (a negative, unset, or malformed budget never blocks traffic). Resets on `HttpState.reset()`. Tracked by the `mock_server_llm_cost_budget_tripped` Prometheus counter.
- **Per-session token/cost totals in Sessions view** — the dashboard Sessions view now displays per-session aggregate token usage (total input/output tokens) and estimated USD cost as chips in each session lane header, computed purely client-side from the already-parsed response bodies.
- **First-class LLM failover/retry scenario builder** — `LlmFailoverBuilder` and the `mock_llm_failover` MCP tool generate an ordered set of expectations that simulate a provider returning failures (e.g. 503, 429) for the first N attempts, then succeeding with a provider-correct `httpLlmResponse`. Uses `Times.exactly(n)` on failure expectations so they are consumed in order before falling through to the unlimited success expectation. Consecutive same-status failures are coalesced for efficiency. Point LiteLLM, Envoy AI Gateway, or an SDK's retry config at MockServer and assert failover logic deterministically.
- **Token-based (TPM/TPD) LLM rate-limit simulation** — `LlmChaosProfile` now supports token-based quota enforcement via `tokenQuotaLimit` and `tokenQuotaWindowMillis`, modelling real provider TPM/TPD limits. Each response's token count (from `Usage` or estimated from text length) is charged against an independent fixed-window counter in `LlmQuotaRegistry`; when the cumulative in-window total exceeds the limit, a 429 (`token_quota_exceeded`) is returned. Both request-count and token quotas can coexist on the same profile.
- **Provider-correct LLM rate-limit response headers** — when MockServer returns a rate-limit or quota error on the LLM response path (probabilistic chaos `errorStatus` or stateful quota 429), it now emits the provider-correct rate-limit HTTP headers that real LLM providers send (OpenAI `x-ratelimit-limit-requests`/`x-ratelimit-remaining-requests`/`x-ratelimit-reset-requests`, Anthropic `anthropic-ratelimit-requests-*` with RFC 3339 timestamps, Gemini/Bedrock `retry-after`). Successful responses also carry the headers when a quota is configured, so client SDK retry/backoff logic can be exercised against a mock. Ollama returns no rate-limit headers (local inference). Implemented by the pure helper `LlmRateLimitHeaders` (`org.mockserver.llm`).

#### Mock creation & matching feedback
- **Generalised capture-to-expectation** — the dashboard "Capture as Mock" dialog now works for **any** recorded or proxied request (plain HTTP, gRPC, GraphQL), not just LLM traffic. A three-level **matcher precision toggle** (Exact / Moderate / Loose) controls how tightly the generated `httpRequest` matcher binds: from method+path+query+headers+body down to method+path only. Generic captures register via `PUT /mockserver/expectation` with `httpResponse`; the existing LLM capture path is unchanged.
- **Create expectation from unmatched request** — the "Why Didn't This Match?" mismatch diagnostic dialog now includes a "Create Expectation" button that opens the capture-as-mock dialog pre-filled with the unmatched request, letting users turn a near-miss into a working stub in one click.
- **Client-visible match feedback** — new opt-in config property `attachMismatchDiagnosticToResponse` (default `false`) attaches closest-match diagnostic info (header `x-mockserver-closest-match` + JSON body with per-field diffs) to 404 responses for unmatched requests, so test authors can see why their mock didn't match without checking the dashboard or logs.
- **Opt-in realistic OpenAPI example data** — new config property `generateRealisticExampleValues` (default `false`) makes OpenAPI example generation produce schema/format-aware values via Datafaker (email, UUID, date, date-time, URI, hostname, IPv4/IPv6, byte, password, integers/numbers respecting min/max) instead of static placeholders, with a fixed seed for deterministic output. Existing behaviour is unchanged when the flag is off.

#### Response templates
- **Templates can be loaded from a file** — `httpResponseTemplate` and `httpForwardTemplate` accept a new `templateFile` field (a classpath-or-filesystem path) as an alternative to the inline `template`, keeping large templates out of the expectation JSON. When both are set the inline `template` takes precedence. Works with all three engines (Velocity, Mustache, JavaScript).
- **Templated response body files** — a static `httpResponse` whose body is a `FILE` body can set a `templateType` of `MUSTACHE` or `VELOCITY`, in which case the file contents are rendered as a template against the request before being returned (the status code, headers and content type still come from the static response). This combines externally stored response bodies (issue #2163) with response templating, as requested in discussion #2350. JavaScript is not supported for body files (its templates return a full response object rather than text) — use `httpResponseTemplate` for that.
- **Client-library support for `templateFile` and templated FILE bodies** — the Node, Python, Go, .NET, Ruby and Rust clients gain `templateFile` on their template models and `templateType` on FILE response bodies, so the two features above can be driven from each client (the PHP client, which has no template model, gains a `fileBody()` helper).
- **Velocity templates are parsed once and cached** — the Velocity engine previously re-parsed the template string on every render. It now caches the parsed template (via Velocity's native string-resource cache) and reuses it, so a repeatedly rendered template (response templating, forward templating, and especially load-scenario steps that render every iteration) is rendered without re-parsing. Output is unchanged. Measured with JMH (`-prof gc`): 55–79% faster and 46–74% less allocation per render across simple-to-complex templates, with the biggest wins on complex templates under sustained load.
- **Velocity render allocates less per request** — the stateless built-in template functions and helpers (`$uuid`, `$faker`, `$json`, etc.) are now shared across renders via a single immutable context layer instead of being copied into a fresh context on every render. Request-scoped state (the request, the per-iteration values, and request-scoped tools like `$json`/`$xml`) is still built fresh per render, so output and thread-safety are unchanged. Measured with JMH: a further ~1 KB/op less allocation and 30–67% faster per render on top of the parse-once caching above.

#### Dashboard & UI
- **Editable runtime config in the dashboard** — the Configuration dialog now exposes editable controls for `devMode`, `generateRealisticExampleValues`, `attachMismatchDiagnosticToResponse`, `validateProxyOpenAPISpec`, `validateProxyEnforce`, `chaosAutoHaltEnabled`, `chaosAutoHaltErrorThreshold`, and `chaosAutoHaltWindowMillis` (booleans as switches, strings and numbers as text/number fields), driven by a declarative descriptor list in `configuration.ts`. Existing `logLevel`/`detailedMatchFailures`/`metricsEnabled` controls are unchanged; properties not in the descriptor list remain visible read-only.
- **Dashboard Composer — template snippet palette** — the Response Template and Forward Template panels now include an "Insert snippet" button that opens a categorised palette of curated template snippets (request echoes, dynamic data, structure patterns). The palette is engine-aware, showing the correct Velocity / Mustache / JavaScript syntax for the selected template engine and including a live preview of each snippet's output.
- **Dashboard Composer — multi-language code preview** — the Review step's read-only code preview now generates idiomatic client snippets for **Node.js, Python, Go, C#, Ruby and Rust** alongside Java, with JSON and curl shown last. Each client-library tab hydrates the same expectation JSON through that client's native facility (Node `mockAnyResponse`, Python `Expectation.from_dict`, Go/Rust deserialize-and-`Upsert`, C# `Deserialize<Expectation>`, Ruby `Expectation.from_hash`), so every action type is representable without reimplementing each language's builder API. The Composer also gains a "Load template from file" field on the template panels and a "Body source: from file" option (with an optional template engine) on the static-response panel, surfacing the `templateFile` and templated-FILE-body features.
- **Dashboard Library view — Import tab** — the Library view now opens on an Import tab (alongside Export) that lets users paste, upload, or URL-import specs and collections directly from the dashboard (Expectation JSON, OpenAPI, WSDL, HAR, Postman), wiring to the existing server endpoints without any new backend changes.
- **Dashboard "Get Started" onboarding panel** — new users land on a guided first-run view with action cards to import an OpenAPI spec, set up proxy recording, try docker-compose quick-start recipes, and explore the dashboard docs. The view is the default when no expectations or traffic exist; it auto-transitions to the dashboard once data arrives and remains accessible via the nav bar.
- **Dashboard request diffing from the Traffic view** — a "Compare" toggle in the Traffic inspector lets you pick two recorded or proxied requests and open the field-level diff inline (reusing the existing `PUT /mockserver/diff` endpoint and diff dialog), pre-populated with the two selected requests.
- **LLM streaming-physics controls in the Composer** — the conversation builder now exposes streaming-physics fields (time-to-first-token, tokens-per-second, jitter) when a turn is marked as streaming, so users can shape the timing of mocked streamed completions without hand-writing the `streaming` block.
- **LLM structured-output field in the Composer** — the conversation builder now has an `outputSchema` field so a mocked completion can declare a JSON schema for structured/tool-style output.
- **WASM rule body matcher in the Composer** — the expectation Composer now offers a `wasm` body-matcher option with a module-name dropdown sourced from the uploaded WASM modules, so a custom WASM rule can be wired into an expectation from the dashboard (it previously could only be uploaded, not referenced).
- **Chaos auto-halt controls in the Chaos tab** — the dashboard Chaos tab now surfaces the auto-halt circuit-breaker inline (arm/disarm switch, error threshold, and sliding-window size) so users can see and adjust the safety cut-off where they configure chaos, rather than only in the Configuration dialog.

#### CLI & self-contained binary
- **Redesigned command-line interface** — a `mockserver` CLI (built on picocli) with `run` (default), `proxy`, `openapi`, `version` and `help` subcommands, per-command `--help`, short flags (`-p`/`--port`, `--proxy-to`, `--openapi`, `--init`, `--persist`, `-l`/`--log-level`) and scheme-aware proxy targets (`--proxy-to https://host` infers the port). The `org.mockserver.cli.Main` entry point, all existing flags (`-serverPort`, `-proxyRemotePort`, `-proxyRemoteHost`, `-logLevel`) and the configuration precedence (command line > system property > environment variable > properties file) remain fully supported. Documented in `docs/code/cli.md` and the *Running MockServer* site page.
- **CLI validation-proxy flags** — `--validate-openapi <spec>` and `--validate-enforce` on the `run` and `proxy` subcommands let users launch a validating proxy in one command, wiring directly to the existing `validateProxyOpenAPISpec` / `validateProxyEnforce` configuration properties.
- **Developer-friendly `--dev` mode** — opt-in `--dev` CLI flag (or `MOCKSERVER_DEV_MODE=true` / `-Dmockserver.devMode=true`) applies laptop-appropriate defaults: `maxLogEntries=1000` and `maxExpectations=1000`, reducing memory usage for local development and test suites. Explicit configuration always overrides dev-mode defaults. Default behaviour (without `--dev`) is completely unchanged.
- **`ui` subcommand** — `mockserver ui [-p <port>]` starts MockServer (default port 1080) and opens the dashboard (`/mockserver/dashboard`) in the default browser, printing the URL and degrading gracefully to just the URL on a headless host (server/CI/SSH). To start without opening a browser, use `run`.
- **`-D<key>=<value>` CLI property passthrough** — `run`/`ui`/`proxy`/`openapi` accept repeatable `-D` options (e.g. `mockserver run -p 1080 -Dmockserver.metricsEnabled=true`), applied as JVM system properties before startup, so the launcher and jar can set any configuration property without a JVM `-D` before `-jar`.
- **Clearer CLI errors & help** — starting without a resolvable port (no `-p`/`--port`, `MOCKSERVER_SERVER_PORT`, `mockserver.serverPort`, or properties file) now prints a concise picocli usage plus a one-line actionable error instead of the legacy `java -jar …` block and an empty configuration dump. Usage text reflects how MockServer was launched (`mockserver …` from the binary bundle, `java -jar …` otherwise), and `-help`/`-version` now behave the same as `--help`/`--version` (top-level overview).
- **Self-contained binary distribution (no JVM, no Docker)** — every release now publishes downloadable MockServer bundles (a jlink-trimmed Java runtime + the server + a `mockserver` launcher) for Linux, macOS and Windows (x86_64 + aarch64) as assets on the GitHub Release, each with a SHA-256. Download, extract, and run `bin/mockserver run -p 1080` — no pre-installed JVM or Docker required. Built from one host via `scripts/build-binary-bundle.sh` / `scripts/build-all-bundles.sh`.
- **`mockserver-node` binary launcher** — `npx -p mockserver-node mockserver run -p 1080` downloads the JVM-less binary bundle for the current platform (no Java, no Docker), verifies its SHA-256, caches it per-user, and runs it. Honours `MOCKSERVER_BINARY_BASE_URL` (mirror), `MOCKSERVER_SKIP_BINARY_DOWNLOAD`, `MOCKSERVER_BINARY_CACHE` and `NODE_EXTRA_CA_CERTS`. Reference implementation of the on-demand-binary pattern for the client libraries.

#### Client libraries & integrations
- **Multi-language client libraries** — hand-written idiomatic clients for the MockServer control plane in **Go** (`mockserver-client-go`, pkg.go.dev), **.NET** (`MockServerClient`, NuGet), **Rust** (`mockserver-client`, crates.io) and **PHP** (`mock-server/mockserver-client`, Packagist), covering create-expectation, verify/verifySequence, clear, reset and retrieve. Each ships unit tests plus a skippable integration test.
- **Testcontainers modules** — a `MockServerContainer` for **Node**, **Python**, **.NET**, **Go** and **Rust** (under `mockserver-testcontainers/`) that starts the `mockserver/mockserver` image, waits on `/mockserver/status` and exposes the mapped URL.
- **Editor integrations** — a **VS Code** extension (`mockserver-vscode`: start/stop the Docker container, open the dashboard, expectation snippets) and an initial **JetBrains/IntelliJ Platform** plugin scaffold (`mockserver-jetbrains`).

#### Packaging & distribution channels
- **GHCR image mirror** — every release now mirrors the multi-arch images to `ghcr.io/mock-server/mockserver` (copied from Docker Hub by digest, cosign-signed). Error-isolated: a GHCR failure never affects the Docker Hub / ECR publish.
- **Automated MCP registry publishing** — the release pipeline publishes `server.json` to `registry.modelcontextprotocol.io` under the DNS-verified `com.mock-server/mockserver` namespace (non-interactive auth via an ed25519 key in Secrets Manager + an apex TXT record). Soft-fail — never blocks a release.
- **Release pipeline distribution channels** — soft-fail release components that publish the new clients, Testcontainers modules and editor extensions (NuGet, crates.io, Packagist, pkg.go.dev, npm, PyPI, VS Code Marketplace / Open VSX, JetBrains Marketplace), with post-release liveness checks.
- **`mockserver-bom` (Bill of Materials)** — a new published artifact consumers can import into their `dependencyManagement` to pin every MockServer module **and** every third-party dependency MockServer relies on to a single, mutually consistent version. This makes downstream builds reproducible and satisfies strict version-alignment checks such as the Maven Enforcer `dependencyConvergence` rule, which previously flagged the differing transitive versions MockServer resolves internally (via its parent POM's `dependencyManagement`) but did not export to consumers. Usage: import `org.mock-server:mockserver-bom` with `<type>pom</type>` and `<scope>import</scope>`.

#### Onboarding & guides
- **One-command quick-start recipes** — curated `docker compose up` recipes under `examples/docker-compose/` for the most common use cases (`mock-from-openapi`, `record-replay-proxy`, `validation-proxy`, `chaos-proxy`), each self-contained with a short README and a "Getting started in 60 seconds" path in the repository README.
- **Consolidated "Self-Hosting MockServer" guide** — a single task-oriented site page (`/mock_server/self_hosting_mockserver.html`) that brings together every way to run MockServer yourself with copy-paste commands: Docker and the one-command docker-compose recipes, the `mockserver` CLI and the JVM-less binary bundle, Helm/Kubernetes, the executable JAR, Testcontainers, initializers/persistence, and bootstrapping from a browser HAR. Linked from the repository README.
- **MockServer UI docs — Traffic compare/diff and full Chaos fault set** — the *MockServer UI* site page (`/mock_server/mockserver_ui.html`) now documents the Traffic view's "Compare" toggle for diffing two captured requests (`PUT /mockserver/diff`) and the Chaos tab's complete HTTP service-chaos fault set wired to `PUT /mockserver/serviceChaos` (error/connection faults, body corruption, slow-response chunking, quota/rate limit, count and time windows, gradual degradation, GraphQL error envelope, and TTL).

### Changed

- **CI** — the build pipeline now runs unit tests for the new Go, .NET, Rust and PHP libraries, the five Testcontainers modules and the editor extensions (each in its language toolchain Docker image), triggered by changes under their paths.
- **Slimmer `mockserver-client-java` classpath** — the Java client no longer drags the server-only engines (Velocity/Mustache templating, GraalVM JavaScript, WASM/Chicory, DataFaker, protobuf/gRPC transcoding **and the Swagger/OpenAPI parser**) onto a consumer's classpath when it is the only MockServer artifact depended upon. Those all run inside the server, never in the client JVM, so they are excluded from the client's `mockserver-core` dependency. `mockserver-core`'s object mapper now registers its Swagger-coupled serializers only when swagger-core is present (see Fixed), so the client serialises OpenAPI expectations as plain spec strings without the parser on its classpath. In-process-server usages (e.g. `mockserver-junit-jupiter` → `mockserver-netty`) are unaffected — the engines still arrive via the server module. Verified by the full 155-test client suite, 718 core serialization/OpenAPI tests, and a runtime check that round-trips expectations with swagger genuinely absent.

### Fixed

- **Dashboard rendered a blank page when the server ran on a non-UTF-8 platform** ([#2347](https://github.com/mock-server/mockserver-monorepo/issues/2347)) — the dashboard's static assets (JS/CSS/HTML) are always written to the wire as UTF-8, but the `Content-Length` header was computed with the JVM's default charset. On a platform whose default charset is not UTF-8 (e.g. Windows, where the legacy default is `windows-1252`), any asset containing multi-byte characters got a `Content-Length` shorter than the actual body, so the browser truncated the bundle and the dashboard showed a white page. A JAR built on macOS (UTF-8) therefore worked there but failed on Windows. `Content-Length` is now computed from the UTF-8 byte length, matching the bytes sent.
- **Diagnostic match endpoints flooded the dashboard log with spurious unmatched entries** — the "Why Didn't This Match?" debug-mismatch path and the `explain_unmatched_requests` MCP tool re-ran the live request matchers purely to compute field-level diffs, but that match wrote one `EXPECTATION_NOT_MATCHED` event per expectation into the event log as a side-effect. Those entries had no request correlationId, so the dashboard could not group them, and repeated calls filled the bounded dashboard log window and evicted matched/response/received entries — making the dashboard appear to show only unmatched traffic. Read-only diagnostics now suppress match-result logging (a request-scoped flag on `MatchDifference`), so they no longer mutate the log they inspect.
- **Dashboard Library → Import format radios mis-aligned** — the format radio buttons (Expectation JSON / OpenAPI / WSDL / HAR / Postman) now top-align with their option titles instead of centring on the whole title+description block.
- **Dashboard Composer connection-options row clipping/overlap** — in the response "Connection options (advanced)" row the "Content-Length override" field no longer clips its label and the "Close socket" dropdown arrow no longer overlaps the text; the "Suppress Content-Length"/"Suppress Connection" switches now have clear spacing from the override field instead of crowding it.
- **Build-time guard for global-state-mutating tests missing from sequential Surefire phase** — added `GlobalStateMutationGuardTest` that scans all test classes for high-signal static-state mutation patterns (`ConfigurationProperties` setter calls, `System.setProperty`/`clearProperty`, singleton `.getInstance().reset()`/`.clear()`, `Metrics.resetAdditionalMetricsForTesting`, `PrometheusRegistry.defaultRegistry`) and fails the build if any matched class is not in the sequential phase. Moved 17 test classes that were running in the parallel phase despite mutating global state to sequential (with symmetric exclude/include, validated by `ParallelStaticStateGuardTest`). This closes the gap where `ParallelStaticStateGuardTest` only checked list symmetry but could not detect a new stateful test missing from both lists — the root cause of 4 separate CI flake incidents.
- **LLM config-mutating tests flake under parallel Surefire** — `LlmBackendResolverTest`, `LlmProviderSnifferTest`, and `ForwardPathGenAiSpansTest` mutate JVM-global `ConfigurationProperties.llm*` statics but were not in the sequential Surefire phase, causing intermittent cross-test contamination under `parallel=classes`. Moved all three to the sequential phase (symmetric exclude/include lists, validated by `ParallelStaticStateGuardTest`).
- **Chaos auto-halt unbounded accumulation when threshold is non-positive** — when `chaosAutoHaltEnabled=true` but `chaosAutoHaltErrorThreshold` was 0 or negative, `recordError()` appended timestamps to the sliding window without ever evicting them (the early-return skipped eviction but ran after the `addLast`). The threshold check now runs before recording, so a non-positive threshold is a no-op (no timestamps accumulated, no halt). Also removed dead `Sparkline.tsx` component (zero production imports) and corrected stale consumer docs that said gRPC-bidi inbound breakpoints were "not yet intercepted (future work)" — they shipped in `a8f4bb0e2`.
- **Dashboard Chaos/Composer polish + demo Experiments** — the Chaos → Experiments stage fields (Error status, Error prob, Latency ms, Drop prob) were widened so their labels are no longer truncated; the Composer "Editing … changes update this expectation." info box now vertically centres its text with the (i) icon; the operating-mode (SPY/SIMULATE/CAPTURE) dropdown tooltip suppresses itself while the menu is open so it no longer overlays the menu items; and the demo-data populate script (`npm run demo`) now registers a multi-stage looping chaos experiment so the Chaos → Experiments section shows live data out of the box.
- **Dashboard correctness and UX fixes** — a batch of dashboard fixes: the action-type / LLM-provider filter chips are now labelled "expectations only" so they no longer look like a no-op on the request and traffic panels; request-panel row numbers are correct while a search filter is active (numbered against the filtered list, not the full list); the "Generate Stub" dialog now shows **all** returned suggestions instead of silently keeping only the first; panel count chips show the post-filter count (e.g. `2 / 50`) when a filter or search is active; clearing server logs no longer blanks the local expectations/recorded lists without refetching them; panel search now matches field values rather than serialised JSON keys (so searching `value`/`id`/`type` no longer matches every row); the ⌘L "clear logs" shortcut now asks for confirmation like the menu action; copy-to-clipboard failures surface a "Copy failed" tooltip instead of failing silently; the dashboard honours a `?secure=true|false` query-param override so it can target an HTTPS MockServer when itself served over HTTP; the Traffic "Replay" dialog warns that it makes a real, side-effecting call to the original target (with an extra warning for non-GET methods); and the Drift, Breakpoints and Chaos panels degrade gracefully (an "unavailable on this server" notice) instead of showing a raw error when pointed at an older MockServer that lacks those endpoints. Editing an existing LLM conversation and changing the number of turns no longer leaves a duplicate orphaned scenario on the server — the old turns are now cleared before the replacement is registered, and the action is clearly labelled as a replacement. The dashboard service-chaos form now validates `errorStatus` (100–599) and `errorProbability` (0.0–1.0) inline and blocks submission of out-of-range values rather than failing with a server 400.
- **Dashboard adversarial-review correctness fixes (batch 1)** — five defensive fixes from a full adversarial review of the dashboard UI: (1) the Breakpoints panel held paused exchanges in an **unbounded** list that was never cleared on reconnect, so a broad breakpoint matcher (e.g. path `.*`) could exhaust browser memory — the list is now capped (oldest dropped) and cleared when the callback WebSocket disconnects, since held items reference a clientId the server replaces on reconnect; (2) the SSE parser split only on `\n`, so real CRLF-terminated streams mishandled the `[DONE]` sentinel and leaked stray carriage returns into reassembled text — line endings are now normalised first; (3) the Prometheus metrics parser retained non-finite (`+Inf`/`-Inf`/`NaN`) sample values that poisoned chart auto-scaling and numeric formatting (`toFixed` → `"Infinity"`) — non-finite values are now skipped (histogram `le="+Inf"` is unaffected, as it lives in the label, not the value); (4) the TCP and gRPC service-chaos TTL countdowns decremented against the HTTP poll's timestamp (a different poll loop that kept advancing while those sections were collapsed and their data frozen), making the countdowns drift — each dataset now tracks its own poll timestamp; (5) the Traffic detail pane is wrapped in an error boundary so a parser exception on a malformed captured body shows an inline error instead of unmounting the whole inspector.
- **Dashboard Composer round-trip + validation fixes (batch 2)** — editing an existing expectation in the Mocks composer silently lost some body matchers: a **GraphQL** matcher was read back from the non-existent JSON field `graphql` instead of `query` (the actual wire field), so the query was wiped on every edit; and a **WASM** body matcher had no read-back branch at all, so it fell through to a raw JSON dump. Both now round-trip correctly (covered by a new reader↔writer round-trip test). In addition, the Register button now validates **base64** inline for the binary body matcher, the Error action's response bytes, and the Binary response action — malformed base64 is blocked with a clear reason instead of failing as an opaque server 400 (or throwing in the generated Java `Base64.getDecoder().decode(...)`).
- **Dashboard performance fixes (batch 3)** — three rendering/polling efficiency fixes from the adversarial UI review: (1) the **Log Messages** panel re-ran its grouped-entry text computation for every log group on every ~1/sec WebSocket snapshot because `LogGroup` was not memoised and received a fresh per-row toggle closure — it's now `React.memo`-wrapped and the panel passes a single stable toggle callback, so unchanged groups skip the work; (2) all interval-**polling** views (Metrics, Drift, Chaos, Breakpoints, AsyncAPI) now **pause while the browser tab is hidden** and resume on return, instead of scraping/parsing in the background indefinitely (with an in-flight guard so returning to the tab can't fork a duplicate poll loop); (3) the **Traffic** inspector caches each captured request's parsed summary (SSE reassembly + base64 decode) keyed on the item reference, so it no longer re-parses every row on every snapshot and every search keystroke.
- **Dashboard accessibility fixes (batch 4a)** — keyboard and screen-reader fixes from the adversarial UI review: the expand/collapse chevrons on log entries, request/expectation rows, log groups, and match-failure ("because") sections are now real focusable controls with `aria-label` (Expand/Collapse) and `aria-expanded`, so they are keyboard-operable and announce their state (previously they were unlabelled icons inside mouse-only rows); the AppBar clear/reset button gained an `aria-label`; the connection-error banner and notification toasts are now `role="alert"` live regions; and ten Tools-menu dialogs (Clock, Configuration, OIDC, CRUD, AsyncAPI, OpenAPI/WSDL import, Pact, Explain-unmatched, Generate-stub) now expose an accessible name via `aria-labelledby`.
- **Dashboard destructive-action confirmations + dialog reset (batch 4b)** — bulk/irreversible dashboard actions that previously fired on a single click now route through the existing confirmation dialog: clear-all breakpoint matchers (which orphans paused exchanges), clear-all HTTP/TCP/gRPC service chaos, clear drift records, delete a server-filesystem file, and delete a WASM module / clear gRPC descriptors. Per-item Remove on low-stakes lists is unchanged. Separately, several Tools-menu dialogs (AsyncAPI, OIDC, CRUD, File store, and a stale-error clear on Clock/Configuration) now reset their form fields and success/error banners on close, so reopening no longer shows stale pasted content or outcome messages.
- **Dashboard Composer generated-Java formatting (batch 6)** — the "Forward with override" action produced badly mis-indented Java in the Composer's Java preview (the inner `request()` landed at column 0 with its builder calls jammed far to the right) because the override block was indented once when built and again by the outer re-indent pass. It now emits cleanly nested, consistently-indented Java. Added a compile-time exhaustiveness guard to the action-to-Java generator so a future action type can't silently emit `undefined`.
- **Dashboard text-clipping / truncation fixes (batch 7)** — across the dense data views, values that were silently clipped with no way to read the full text now ellipsis-truncate with a tooltip showing the complete value, via a new reusable `TruncatedText` component. Sites fixed: the Breakpoints panel's stream-frame body (which was double-truncated — cut to 40 chars *and* CSS-clipped) plus its id / clientId / matcher / stream-id cells (full UUIDs now recoverable), the Sessions request chips / lane headers / token-cost chips, the Drift expected/actual value cells, the Traffic master-list host+path, the Conversation model/predicate chips, and the collapsed log-entry summary. Also added `minWidth:0` flex fixes so a long host/FQDN in the service-chaos rows and the filter panel no longer forces controls to wrap.
- **Dashboard Composer feature completeness (batch 8)** — the Mocks composer can now author expectation fields that previously could only be set via JSON/the API (and were silently dropped when editing such an expectation in place): a **static response delay**, **reason phrase**, and **response cookies**; a dedicated **JSON body matcher** with a **STRICT / all-matching-fields** match type; and a **substring** toggle for string body matchers. Each is wired through the form, the Java/JSON/curl preview, and the edit-existing round-trip, with the correct server field names. Editing an existing JSON-body expectation stored in the server's default form (a bare JSON object) now correctly comes back as a JSON matcher instead of an exact string.
- **Dashboard responsive form layouts** — the dense multi-field forms that previously went ragged and clipped on narrow viewports now reflow cleanly: the HTTP/TCP/gRPC service-chaos register & edit forms, and the Composer's chaos and side-effect panels, lay their fields out in a responsive CSS grid (`auto-fit` equal columns) instead of fixed-width flex-wrap rows, so columns stay aligned and fields fill the available width at any size. The AppBar's 12-view toggle strip now scrolls horizontally as a unit on narrow windows instead of wrapping mid-group.
- **Dashboard review polish** — four UI fixes from a full review pass: the "Diff two requests" dialog now shows the **diff result at the top** (above the editable request JSON) so it's the most visible thing, and **runs the diff automatically** when opened from the Traffic inspector's Compare flow (both requests already selected) instead of requiring a second button press; the Mocks composer's **Body type** dropdown is wider so "String (exact / subString)" is no longer truncated; and the **Sessions** view now shows a collapsible **Conversation** transcript per session (reusing the Traffic tab's provider chat-bubble views, rendering the last request in the session which carries the full accumulated message history), with a compact **Show Mermaid** link beneath it that opens the correlated agent-run call graph on demand.
- **ReDoS in the Ruby client binary launcher** (CodeQL `rb/polynomial-redos`, CWE-1333) — the trailing-slash strip in `BinaryLauncher.asset_url` used `base.sub(%r{/+\z}, '')`, whose `/+\z` sub-expression can restart at every `/` and backtrack quadratically on a base URL with a long slash run that doesn't end in `/` (relevant on Ruby < 3.2, which lacks the regex match cache). The base URL is operator-supplied via `MOCKSERVER_BINARY_BASE_URL`, so real-world exploitability is low. The trailing-slash strip is now done with a single linear non-regex scan (the regex is removed entirely), eliminating the ReDoS surface — an earlier attempt that merely anchored the regex with a negative look-behind (`%r{(?<!/)/+\z}`) kept the strip linear but did not clear the CodeQL alert. Behaviour is unchanged; added regression tests for interior-slash preservation and a 100k-slash pathological input.
- **Parallel-test isolation for new singleton tests + post-review polish for streaming breakpoints and chaos experiments** — moved `StreamFrameBreakpointRegistryTest`, `ChaosExperimentOrchestratorTest`, and `BreakpointRegistryTest` into the sequential Surefire phase (they mutate JVM-global singletons and flaked under `parallel=classes`); added a `default` case to the stream-frame decision switch in `NettyResponseWriter` to prevent unrecognised actions from hanging the stream; moved `streamId`/`reqMethod`/`reqPath` allocation inside the `streamBreakpointsActive` guard for zero overhead on the default-off path; added `lastTerminatedStatus` to `ChaosExperimentOrchestrator` so `getStatus()` reports `completed`/`stopped`/`halted_by_auto_halt` after an experiment ends; added stream breakpoint and chaos experiment endpoints to the OpenAPI spec; added consumer-facing docs for chaos experiments; fixed the BreakpointsPanel response "Path / Reason" column to show `'-'` instead of the request path when `reasonPhrase` is absent.
- **Startup crash when a properties file has entries** ([#2338](https://github.com/mock-server/mockserver-monorepo/issues/2338)) — MockServer 7.0.0 failed to start with `NoClassDefFoundError: Could not initialize class org.mockserver.configuration.ConfigurationProperties` (caused by a `NullPointerException` during static initialisation) whenever a `mockserver.properties` file — or the Helm chart's `app.config.properties` — contained any entries. The startup property-dump redaction added in 7.0.0 read its `SENSITIVE_SUBSTRINGS` set from the `PROPERTIES` static initialiser but declared it ~3000 lines later in the class, so it was still `null` when class initialisation ran (a static-init ordering bug). The redaction fields are now initialised before the property file is read, with a regression test that initialises `ConfigurationProperties` afresh against a populated property file.
- **Downstream `dependencyConvergence` failures** — consuming MockServer (e.g. `mockserver-client-java` with `MockServerContainer`) under the Maven Enforcer `dependencyConvergence` rule failed with multiple version-conflict errors, because MockServer's transitive version pins lived in the parent POM's `dependencyManagement`, which Maven does not export to consumers. Three changes address this: a new **`mockserver-bom`** to import (above); the slimmer client classpath (above); and pruning the stale `velocity-engine-core 2.3` that `velocity-tools-generic` dragged in alongside the `2.4.1` the build already uses (all 21 Velocity engine tests still pass). With the BOM imported, a client-only consumer's convergence errors drop from 17 to 0.
- **Latent undefined `${jetty.version}` in the parent POM** — three Jetty HTTP-client `dependencyManagement` entries referenced a `jetty.version` property that was only ever defined in the `examples/java` module, so the managed versions were unresolved for any other consumer of the published parent POM. The dead entries were removed from the parent and the `examples` module now declares its Jetty client versions explicitly.
- **Object mapper Swagger coupling made optional** — `ObjectMapperFactory` registered its Swagger/OpenAPI-coupled serializers (the schema serializers and the OpenAPI-derived `HttpRequestsPropertiesMatcher` serializer) unconditionally, so initialising the object mapper loaded `io.swagger.v3.oas.models.*` even on a client that never produces those objects. They are now isolated in a `SwaggerSerializers` helper and registered only when swagger-core is on the classpath, which is what lets `mockserver-client-java` exclude the Swagger/OpenAPI parser (eliminating the bulk of a client-only consumer's remaining `dependencyConvergence` conflicts). The single `com.github.fge` (json-tools) pretty-print call on the client-reachable path was replaced with a small `JsonPrettyPrinter`, and `jackson-datatype-jsr310` — used directly by the object mapper but previously only arriving transitively via the Swagger parser — is now a direct `mockserver-core` dependency. Server behaviour is unchanged (swagger-core is always present there).
- **Remaining non-Swagger convergence conflicts pruned** — with the Swagger parser excluded from the client, three transitive version splits remained for a client-only consumer: `slf4j-api` (older versions via `java-uuid-generator`, `json-path` and `com.networknt:json-schema-validator`), `jackson-annotations` (2.21 via the validator's Jackson 3 transitive) and `jakarta.xml.bind-api` (2.3.3 via `xmlunit-core`). `mockserver-core` now excludes those stale transitive edges; in every case it already declares the winning version directly (`slf4j-api` 2.0.18, `jackson-annotations` 2.22, `jakarta.xml.bind-api` 4.0.5), so its own resolved classpath is unchanged (255 XML/JSON-schema/JSON-path core tests still pass). A consumer depending only on `mockserver-client-java` now passes the Maven Enforcer `dependencyConvergence` rule with **zero** errors even without importing the BOM.

### Documentation

- **Interactive Breakpoints guide rewritten for the matcher + callback-WebSocket model** — the *Interactive Breakpoints* consumer page now documents the final feature: registering a request matcher with phases, resolving paused request/response/stream-frame exchanges interactively over the callback WebSocket (with the per-frame `PausedStreamFrameDTO`/`StreamFrameDecisionDTO` protocol and the `X-MockServer-BreakpointId` routing), the dashboard Breakpoints panel, the safety rails, and idiomatic examples for all seven supported clients (Java, Node, Python, Ruby, Go, .NET, Rust — PHP is not supported). The OpenAPI spec carries `clientId` on the matcher endpoints, and `docs/code/breakpoints.md` was consolidated (TL;DR + flow diagram, WS-callback-only resolution).
- **New consumer guides for the newest features** — added three site pages: *LLM Response Mocking* (`/mock_server/llm_response_mocking.html`) showing how to mock OpenAI / Anthropic / Gemini / Bedrock / Azure OpenAI / Ollama responses via plain expectations — including conversations, streaming and cost budgets — without needing an AI agent or MCP; *Interactive Breakpoints* (`/mock_server/interactive_breakpoints.html`) walking through pausing, inspecting, modifying and resuming requests/responses; and *Observability* (`/mock_server/observability.html`) covering Prometheus metrics (including LLM token/cost counters) and OpenTelemetry trace export with W3C context propagation. Each is linked into the site navigation.
- **Consumer doc corrections** — corrected the *HTTPS & TLS* page to state the real default TLS protocols (`TLSv1,TLSv1.1,TLSv1.2`, not "TLS 1.2 and 1.3"), matching the configuration-properties page; clarified that `disableLogging` disables **all** logging (not just system-out) on the *Performance* page; fixed the *Running MockServer* meta description ("Grunt", not "Gradle"); noted that the Kubernetes `httpGet` liveness probe example requires `MOCKSERVER_LIVENESS_HTTP_GET_PATH` to be set (the path is off by default); reordered *Getting Started* so the common-path "Next Steps" precede the upgrade notes; and simplified the configuration-property precedence wording. Also corrected the internal `docs/code/configuration-reference.md` precedence order (properties file beats environment variable) to match the code.
- **Internal docs** — added `docs/code/chaos.md` (chaos experiments: ChaosExperimentOrchestrator, ordered stages, looping, auto-halt integration, safety limits, endpoints); documented `PUT /mockserver/replay` (request replay) and `PUT/GET/DELETE /mockserver/chaosExperiment` in `docs/code/request-processing.md`; updated `docs/code/dashboard-ui.md` to reflect twelve views (Breakpoints + Get-Started), the Breakpoints panel (request/response/stream phases), the Get-Started onboarding view, Traffic-view Replay and Compare buttons, and the Composer snippet palette; added `generateRealisticExampleValues`/`SampleDataGenerator` coverage to `docs/code/domain-model.md`; added `chaos.md` and `breakpoints.md` rows to `docs/README.md`; added chaos.md and broadened breakpoints row in `AGENTS.md`.
- **Internal docs corrections** — corrected `docs/code/breakpoints.md`: removed stale "Future work" section (all four items shipped — HTTP/3-gRPC, gRPC-bidi inbound, and both dashboard UI features); added `GrpcBidiStreamHandler.handleData` and `GrpcBidiRouterHandler` to the Inbound frame breakpoints key-classes; updated `docs/README.md` doc counts (code: 21→24, operations: 13→15); replaced "error-class" with "destructive" in `docs/code/metrics.md` to match `ChaosAutoHaltMonitor.DESTRUCTIVE_FAULT_TYPES`; updated `docs/code/dashboard-ui.md` Streams tab description to reflect the shipped direction badge and gRPC-bidi inbound frames; added three missing code-doc rows (`ai-protocol-mocking.md`, `llm-codec-fixtures.md`, `llm-security-audit.md`) to the `AGENTS.md` reference table.

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




