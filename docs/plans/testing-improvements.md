# Testing Improvements Plan

> **Status (2026-05-26):** Plan trimmed after adversarial review. Several items have been dropped because they would add flakiness or maintenance churn for limited coverage gain; some "DONE" markers from earlier revisions have been corrected. See [Dropped Items](#dropped-items-and-why) for the items rejected and why.

Maximise test coverage within reason, without adding build flakiness or per-build minutes that aren't paid back by real signal.

See [docs/testing.md](../testing.md) for documentation of the current testing approach.

## Guiding Principles

1. **Determinism beats coverage.** A test that flakes 1% of the time on CI poisons the pipeline far more than the marginal coverage it adds. Prefer JSON-round-trip and pure-logic tests to integration tests that wait for Traefik or a Netty server to come up. K3d/Traefik ingress tests, LoadBalancer assignment tests, and multi-replica tests are explicitly excluded for this reason.
2. **Don't pay overhead for artifacts nobody consumes.** JaCoCo costs ~5–10% of every build. Worth it only if a CI step publishes the report or a quality gate reads it. If no one looks at the coverage number, drop the overhead.
3. **One TODO, one owner.** When two plans both list the same item (e.g., Buildkite Test Analytics token), consolidate to one plan. Avoids drift.
4. **Tests should outlive the code they cover.** Splitting a 2,000-line test method that already passes risks dropping edge-case coverage. Do that work opportunistically when the file is being touched anyway, not as a planned phase.

## Status Snapshot (2026-05-28)

All M1-M6 milestones from the [Execution Plan](#execution-plan) shipped on the `worktree-testing-improvements` branch. Production thresholds: `mockserver-core` LINE ≥0.65 (measured floor 0.6858), `mockserver-netty` LINE ≥0.55 (measured floor 0.5931).

| Phase | Status |
|-------|--------|
| 1 Measure | 1.1 JaCoCo plugin DONE + `jacoco:check` thresholds wired and ratcheted in M6 (Option A+). 1.2 XML report default flipped DONE. 1.3 partial, duplicates [build-optimisation 2.3](./build-optimisation.md#23-buildkite-test-analytics-token-was-34-partial). |
| 2 Quick Wins | 2.1 schema serializer tests DONE (16 classes, 70 methods). 2.2 ClientConfiguration tests DONE (29 methods). 2.3/2.4 WebSocket tests DROPPED. |
| 3 Structural | 3.4 mocking-reduction work DONE. 3.5 `@Ignore` removal DONE. 3.1/3.2/3.3 DROPPED or de-prioritised. |
| 4 Coverage | 4.1 TLS error paths DONE (5 files, 72 methods). 4.2 validator tests DONE (3 ITs, 38 methods). 4.3 mapper tests DONE (5 files, 89 methods). 4.4 listeners DONE (4 files, 22 methods). 4.5 JWT DROPPED. 4.6 file persistence DONE (3 files, 31 methods). |
| 5a Container CI | 5a.1-5a.3 DONE. |
| 5b K3d | 5b.1-5b.3 DONE. |
| 5c Docker Coverage | 5c.1 graceful shutdown DONE, 5c.2 JVM options DONE, 5c.3 libs classpath DONE. 5c.4 variant smoke test deferred to a follow-up (material CI cost for three additional Docker builds). |
| 5d Helm Coverage | 5d.2 ConfigMap injection DONE, 5d.4 `mockserver-config` chart DONE. 5d.1 ingress, 5d.3 LoadBalancer, 5d.5 multi-replica DROPPED. |
| 6 Ratchet | DONE. `jacoco:check` thresholds raised from M1's safe-low placeholders to ~3-4pt below measured floors. |

---

## Phase 1: Measure

### 1.1 JaCoCo coverage plugin — DONE, but consumer decision required

Shipped as `jacoco-maven-plugin:0.8.14` in root `mockserver/pom.xml`, bound to `prepare-agent` (test) and `prepare-agent-integration` (verify) plus `report` and `report-integration` goals. Not in `<pluginManagement>`, so it runs on every build with ~5–10% overhead.

**Open decision:** no CI step currently publishes the JaCoCo report or fails on coverage thresholds. Either:
- **Option A (keep):** add a Buildkite artifact upload for `**/target/site/jacoco/**` and document where to find the aggregate report. Optionally wire `jacoco:check` with thresholds (e.g. `mockserver-core` ≥60%, `mockserver-netty` ≥50%) so a regression actually fails the build.
- **Option B (drop):** remove the plugin until there's a consumer. Saves 5–10% of every build for an artifact nobody reads.

Pick one before any further coverage work is done — otherwise the "+5–10%" tax is paid forever for zero feedback signal.

### 1.2 Make XML reports the default — cosmetic cleanup

XML reports already work in CI (`scripts/buildkite_quick_build.sh` passes `-DdisableXmlReport=false`; `pipeline-java.yml` uploads `**/target/{surefire,failsafe}-reports/TEST-*.xml`; `junit-annotate` posts annotations on failures). The root `pom.xml` still defaults to `<disableXmlReport>true</disableXmlReport>`.

**Change:** flip the default to `false` and drop the CI flag. Removes one row from the local-vs-CI divergence table in [build-optimisation.md](./build-optimisation.md#local-vs-ci-divergence-audit). Trivial.

### 1.3 Buildkite Test Analytics token — see build-optimisation plan

Tracked as [build-optimisation 2.3](./build-optimisation.md#23-buildkite-test-analytics-token-was-34-partial). Removed from this plan to avoid duplicate ownership.

---

## Phase 2: Quick Coverage Wins

### 2.1 Unit tests for `serialization/serializers/schema/` — PENDING

18 classes, zero direct test coverage. JSON schema serializers for OpenAPI model types. Test approach: round-trip each schema type (object → JSON → object), assert equality. Deterministic, contained scope, no infrastructure dependencies.

### 2.2 Unit tests for `ClientConfiguration` — PENDING

243-line class, zero tests. Test property reading, default values, builder pattern. Mirror the existing `ConfigurationTest` pattern.

---

## Phase 3: Structural Improvements

### 3.4 Reduce excessive mocking — DONE

`MockServerClientTest` from 130 → 6 `@Mock`/`mock(` references. `HttpActionHandlerTest` from 109 → 23. Both via either replacing mocks with lightweight real collaborators or extracting smaller collaborator interfaces.

### 3.5 Re-enable `@Ignore`d tests — DONE

Zero `@Ignore` annotations remain across `mockserver-core` and `mockserver-netty` test trees.

### 3.3 Split long test methods — opportunistic, not planned

Earlier revisions of this plan claimed `HttpStateTest.java` contained methods of ~1,994 lines. That figure was fabricated — verified maximum is ~162 lines. The actual long-method offenders are the six `validator/jsonschema/*IntegrationTest.java` files, each containing methods of 334–340 lines. Splitting them is correct in principle but the realistic risk is dropping edge-case coverage when one long method exercises behaviour A → B → C in sequence and the split tests only re-assert A and B. **Do this opportunistically when next touching one of those files, not as a planned phase.**

---

## Phase 4: Coverage Expansion

Remaining gaps in critical modules, ordered by risk.

### 4.1 `socket/tls/` — HIGH PRIORITY

~6 of 9 classes lack isolated unit tests. `NettySslContextFactory` and `KeyStoreFactory` have partial transitive coverage but no error-path tests. `PEMToFile`, `SniHandler`, `ForwardProxyTLSX509CertificatesTrustManager` have no coverage at all. Particularly relevant given the recent TLS work on this branch.

### 4.4 `mock/listeners/` — PENDING (was mislabelled DONE)

4 classes: `MockServerEventLogNotifier`, `MockServerLogListener`, `MockServerMatcherListener`, `MockServerMatcherNotifier`. They are referenced transitively from 12 test files (including `MockServerMatcher*Test`, `ExpectationFileWatcherTest`, and `MockServerEventLogCorrelationIdTest`) but no isolated unit tests exist for the listener classes themselves. Earlier revisions of this plan marked this DONE — verified against the test tree, no listener-specific test file exists. Easy unit-level tests: register a listener, fire an event, assert the listener was called.

### 4.2 `validator/jsonschema/` — MEDIUM, narrower than earlier claims

10 source classes; 3 have no test file at all (`JsonSchemaExpectationIdValidator`, `JsonSchemaOpenAPIExpectationValidator`, `JsonSchemaRequestDefinitionValidator`). The other 7 are covered by 6 `*IntegrationTest.java` files plus `JsonSchemaValidatorTest.java`. Earlier revisions of this plan claimed "9 of 10 classes lack direct tests" — that was wrong by a factor of 3. Real gap is the 3 untested classes; integration test depth on the other 7 is debatable but not zero.

### 4.3 `mappers/` — MEDIUM, gated on WAR support decision

7 source classes; 5 lack direct tests (only `HttpServletRequestToMockServerHttpRequestDecoder` and `MockServerHttpResponseToHttpServletResponseEncoder` are tested). Tests are valuable *if* WAR deployment remains a supported configuration. Confirm before investing — if WAR is being phased out, drop.

### 4.6 `file/` — PARTIAL

4 source classes (`FileCreator`, `FilePath`, `FileReader`, `FileStore`); only `FileStoreTest.java` exists. Earlier revisions claimed 3/3 done — actual state is 1/4. Low priority utilities, but a small test pass adding `FileCreatorTest`, `FilePathTest`, and `FileReaderTest` is straightforward.

**Explicitly out of scope:**
- `openapi/examples/models/` — 12 simple model classes, tested transitively through `ExampleBuilder`.
- `memory/` and `metrics/` — 6 low-risk utility classes.
- `mockserver-examples/` — example code, not production.
- `mockserver-integration-testing/`, `echo/http/` — test infrastructure.

---

## Phase 5: Container and Helm Test Coverage

### 5a / 5b — DONE

- Docker Compose tests in CI via `:docker: container integration tests` step in `.buildkite/pipeline-java.yml`.
- `helm lint` runs from `.buildkite/pipeline-infra.yml` → `helm-validate.sh` (lints both `helm/mockserver` and `helm/mockserver-config`).
- `helm test` invoked by `run-helm-test` in `container_integration_tests/helm-deploy.sh`.
- Kind → K3d migration complete: `k3d cluster create/import/delete` throughout `helm-deploy.sh`, k3d install logic in `.buildkite/scripts/steps/helm-integration-test.sh`, `k3d-config.yaml` checked in.

### 5c. Expand Docker Test Coverage — ~1-2 days

| # | Task | Impact | Effort | Details |
|---|------|--------|--------|---------|
| 5c.1 | Test graceful shutdown | Verifies connections drain and expectations persist on `docker stop` | Medium | Start container with persisted expectations, create expectations, send `docker stop`, verify expectations file was written before container exited. |
| 5c.2 | Test `JVM_OPTIONS` env var | Verifies custom JVM flags pass through | Low | Docker Compose test with `JVM_OPTIONS=-Xmx256m`; verify container starts and responds. |
| 5c.3 | Test `/libs/*` classpath extension | Verifies custom JARs are loaded | Medium | Mount a JAR containing an expectation initialiser class into `/libs/`; verify the initialiser ran. |
| 5c.4 | Smoke-test additional image variants | Verifies `root`, `snapshot`, `local` variants build and respond | Low | Per-variant: build + start + HTTP `GET /mockserver/status`. No full test suite per variant. |

Docker tests are deterministic on a single host — no Kubernetes scheduling, no ingress, no service IP allocation. Low flake surface.

### 5d. Expand Helm Test Coverage — narrowed scope

Limited to deterministic chart features. K3d Traefik/ServiceLB tests are excluded because they introduce non-deterministic readiness behaviour that historically becomes the flakiest part of any pipeline.

| # | Task | Impact | Effort | Details |
|---|------|--------|--------|---------|
| 5d.2 | Test ConfigMap injection | Validates `app.config.enabled=true` with properties and initialiser JSON | Low | Deploy with `--set app.config.enabled=true` and properties content; verify config is applied via `/mockserver/status` or expectation list. |
| 5d.4 | Test `mockserver-config` chart | The chart has zero tests today | Low | Deploy `mockserver-config` with custom values, then deploy `mockserver` chart referencing it; verify config is loaded. |

### Phase 5 cost (remaining work only)

| Sub-phase | Already in CI? | Added CI time |
|-----------|---------------|---------------|
| 5a, 5b | Yes | Already counted in baseline |
| 5c (Docker coverage expansion) | No | +2-3 min |
| 5d (narrowed: ConfigMap + mockserver-config chart only) | No | +1-2 min |
| **Remaining work total** | — | **+3-5 min** |

Earlier revisions of this plan projected "+13-20 min" — that double-counted the 5a/5b work that's already shipped.

---

## Dropped Items and Why

Items considered and rejected. Recorded so they don't get re-proposed.

### ❌ Phase 3.1 — Surefire `parallel=classes` + `threadCount=4`

Directly conflicts with [build-optimisation.md → Dropped Items](./build-optimisation.md#-parallel-surefire-parallelclasses-threadcount4). Attempted previously; caused 48 test failures in `mockserver-core` due to shared static state in `ConfigurationProperties` and `MockServerLogger`. The fix path is a multi-week test rework with serious flake risk. Listed in earlier revisions of this plan as "Low effort" — that was wrong.

**Note (2026-05-28):** Despite being recorded as dropped, the parallel config was committed to `mockserver/mockserver-core/pom.xml` on 2026-05-09 (commit `7b2fa05aa5`) with a curated `<excludes>` list for the 12 then-known offenders. The 6.1.0 LLM work added new tests (`MaxLlmConversationBodySizeTest`, `llm/codec/*Test`, `NettyHttpClientTest`) that first-touch `ConfigurationProperties` / `EchoServer` static state without being added to the exclude list, reintroducing the JVM `<clinit>` deadlock and causing a 30-minute Surefire fork timeout. Reverted to sequential execution as part of M1. Do not re-enable.

### ❌ Phase 3.2 — Test categories (`@Category(SlowTest.class)` + `fast-tests` profile)

Adding the annotation across hundreds of test classes is permanent maintenance burden. The payoff (developers running `./mvnw test -Pfast-tests` locally) depends on adoption that isn't guaranteed. Reconsider only if there's an explicit team commitment to use the profile.

### ❌ Phase 2.3 — `WebSocketClient` isolated unit tests
### ❌ Phase 2.4 — `WebSocketClientRegistry` isolated unit tests

These are Netty-heavy callback infrastructure. Isolated unit testing requires either a fake Netty server (mostly tests the fake) or an embedded Netty server (which makes them integration tests, with real network and async timing — flake surface). The reason these are mocked in `ForwardChainExpectationTest` and 9 other test files today is that they're awkward to unit-test honestly. Realistic coverage path: confirm they're exercised by the callback integration tests in `mockserver-netty`; if so, that's the right testing layer.

### ❌ Phase 4.5 — JWT exception classes

Plan's own assessment was "low priority, low risk." `JWTAuthenticationHandler` is directly tested; the generators and validator are tested transitively. Only exception classes lack direct tests. Drop.

### ❌ Phase 5d.1 — Ingress test (Traefik)

K3d bundles Traefik but ingress attachment to a Service is non-deterministic in timing. Tests that wait for the ingress to be "ready" tend to retry-with-sleep, which becomes the flake. Trade-off is worse than the value of validating the 52-line ingress template — `helm lint` already catches syntax errors, and ingress is a thin wrapper users typically customise per environment anyway.

### ❌ Phase 5d.3 — LoadBalancer service type test

K3d ServiceLB allocates external IPs via host-network shenanigans inside the K3d container. Works on a fresh box, intermittent on a CI agent that has hosted other K3d clusters. Same flake-vs-value calculus as ingress.

### ❌ Phase 5d.5 — Multi-replica deployment test

MockServer's expectations are in-memory per JVM. Two replicas serving traffic round-robin will give inconsistent matching results unless the test wires up the `mockserver-config` chart (already covered by 5d.4) and turns off expectation routing — at which point the test is exercising K8s Service round-robin, not MockServer. Low signal, high coordination cost.

---

## Cost/Complexity Budget (remaining work)

| Item | Build Time Impact | Coverage / Value | Complexity |
|------|------------------|------------------|------------|
| 1.1 JaCoCo consumer decision (A or B) | -5–10% (Option B) or 0% (Option A + artifact) | Coverage reports become useful or overhead drops | Trivial |
| 1.2 Flip XML report default | 0% | Parity cleanup | Trivial |
| 2.1 Schema serializer tests | +<1% | +Real gap (18 classes) | Low |
| 2.2 ClientConfiguration tests | +<1% | +Real gap | Low |
| 4.1 TLS error-path tests | +1% | +High value (recent TLS work) | Low |
| 4.4 Listener unit tests | +<1% | +Real gap (was mislabelled DONE) | Low |
| 4.2 jsonschema validator tests | +1% | +Medium value | Low |
| 4.3 Mapper tests (gated on WAR support) | +1% | +Medium-if-WAR-supported | Low |
| 5c Docker coverage (5c.1–5c.4) | +2-3 min CI | +Docker variants and operations | Low |
| 5d.2 + 5d.4 (narrowed) | +1-2 min CI | +Chart features that have zero tests | Low |
| **Net** | **Roughly flat** | **+15% on the modules that matter** | **Low** |

The Phase 3 parallelism gain from earlier revisions is gone (3.1 dropped). The Phase 1 JaCoCo overhead is the main cost, and the consumer decision dictates whether that cost is paid back at all.

## Success Criteria

1. **JaCoCo decision shipped** — either a Buildkite artifact + threshold, or the plugin removed. No "plugin runs but nobody reads the output" steady state.
2. **`<disableXmlReport>` default flipped to `false`** in root `pom.xml`; CI flag override removed.
3. **No new test method added by this plan exceeds 200 lines.** Pre-existing long methods (the validator integration tests at 334–340 lines) are out of scope and tracked under Phase 3.3 as opportunistic work.
4. **CI build time stays under 60 minutes** for Java tests; container/Helm tests run in a separate step.
5. **All Phase 2 and Phase 4 unit tests written and passing on master for 7 consecutive days** (no flakes).
6. **Container integration tests run in CI** — already true; verify the 5c additions don't introduce flakes.
7. **`helm lint` runs for both charts** — already true via `pipeline-infra.yml`.

## Files Touched by Remaining Work

| File | Change |
|------|--------|
| `mockserver/pom.xml` | Flip `disableXmlReport` default (1.2); JaCoCo decision (1.1 — either add `jacoco:check` or remove plugin) |
| `scripts/buildkite_quick_build.sh` | Remove `-DdisableXmlReport=false` flag once pom default flipped |
| `.buildkite/pipeline-java.yml` | Optional: add JaCoCo artifact upload glob if Option A |
| `mockserver/mockserver-core/src/test/java/org/mockserver/serialization/serializers/schema/**` | New: per-class round-trip tests (2.1) |
| `mockserver/mockserver-core/src/test/java/org/mockserver/configuration/ClientConfigurationTest.java` | New, alongside existing `ConfigurationTest` (2.2) |
| `mockserver/mockserver-core/src/test/.../socket/tls/**` | New: error-path tests for `NettySslContextFactory`, `KeyStoreFactory`, `PEMToFile`, `SniHandler`, `ForwardProxyTLSX509CertificatesTrustManager` (4.1) |
| `mockserver/mockserver-core/src/test/.../mock/listeners/**` | New: unit tests for the 4 listener classes (4.4) |
| `mockserver/mockserver-core/src/test/.../validator/jsonschema/**` | New (4.2) |
| `mockserver/mockserver-core/src/test/.../mappers/**` | New, gated on WAR support decision (4.3) |
| `container_integration_tests/docker_compose_graceful_shutdown/**` | New (5c.1) |
| `container_integration_tests/docker_compose_jvm_options/**` | New (5c.2) |
| `container_integration_tests/docker_compose_libs_classpath/**` | New (5c.3) |
| `container_integration_tests/integration_tests.sh` | Extend to smoke-test `root`/`snapshot`/`local` variants (5c.4) |
| `container_integration_tests/helm_configmap_injection/**` | New (5d.2) |
| `container_integration_tests/helm_mockserver_config_chart/**` | New (5d.4) |
