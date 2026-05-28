# Build Optimisation Plan

> **Status (2026-05-28):** 2.1 (shade → parent POM) and 2.2 (`.mvn` config + script audit) are **DONE and verified** (all six shaded JARs byte-identical to baseline). 2.3 (Test Analytics) is **blocked on a one-time Buildkite-admin step** — see that section. Original note below.
>
> **Status (2026-05-26):** Plan trimmed after adversarial review. Several items from the original plan have been dropped because their complexity-to-payoff ratio was poor or because they would widen local-vs-CI divergence. See [Dropped Items](#dropped-items-and-why) for what was considered and rejected.

## Guiding Principles

1. **Local and CI must stay reproducible from each other.** Today, `scripts/local_buildkite_build.sh` runs the CI script verbatim inside the CI Docker image. That parity is the single most valuable debugging asset we have. Any optimisation that splits the build into shapes that exist only in CI (parallel pipeline steps, artifact hand-offs, S3 caches) needs to clear a high bar.
2. **Prefer Maven-native and shell-native solutions over custom Java extensions.** A 3-line sed filter beats a 200-line `EventSpy`. A `-q` flag beats a custom JUnit `RunListener`.
3. **Don't pay for caching at the cost of correctness.** Stale artifacts that silently look fresh are worse than slow clean builds. The shade plugin in particular has a poor track record with Maven build-cache reconciliation.
4. **Cut wall-clock where the wall-clock actually is.** With `-T 1C` already shipped, most remaining wall-clock is integration tests that genuinely can't run in parallel due to port allocation and shared static state. Don't optimise compile/package when test execution dominates.

## Current State

| Metric | Local Build | Buildkite CI |
|--------|------------|--------------|
| Parallelism | `-T 3C` | `-T 1C` (matches t3.large vCPU count) |
| Test parallelism | None | None |
| Surefire/Failsafe version | 3.5.5 | 3.5.5 |
| Build caching | None | None (deps pre-warmed in Docker image) |
| JVM heap | `-Xmx8192m` | `-Xmx6144m` |
| Test output | verbose | `mockserver.testOutput=quiet` + `redirectTestOutputToFile=true` |
| Maven version | 3.9.0 (wrapper) | 3.9.16 (Docker image) |
| Shade plugin | Runs on 6 modules (~230 LOC duplicated config each) | Same |
| Assembly plugin | Fat JAR + brew tar on `mockserver-netty` | Same |
| Invoker plugin | 3 Maven sub-builds + Gradle tests | Same |

### Local vs CI Divergence (audit)

| Flag | Local (`local_quick_build.sh`) | CI (`buildkite_quick_build.sh`) | Justified? |
|------|-------------------------------|---------------------------------|------------|
| Parallelism | `-T 3C` | `-T 1C` | Yes — agent vCPU count |
| Heap | `-Xmx8192m` | `-Xmx6144m` | Yes — agent RAM ceiling |
| Test output mode | verbose | quiet | Yes — log volume |
| XML reports | default | `disableXmlReport=false` | Yes — Buildkite junit-annotate |
| Test stdout | console | `redirectTestOutputToFile=true` | Yes — log volume |
| Test log level | default | `mockserver.testLogLevel=INFO` | Yes — failure diagnostics |
| Test arg line | default | custom `maxLogEntries`/`maxExpectations` | Yes — CI-specific load |
| JDK | Java 17 (forced) | container default | Acceptable |

**Rule:** any new CI-only flag added by this plan must be reproducible locally via `scripts/local_buildkite_build.sh`. Anything beyond the table above requires a written justification.

---

## Phase 1: Already Done

These items are complete and have been validated in production.

### 1.1 Parallel module builds in CI (`-T 1C`) — DONE

Added to `scripts/buildkite_quick_build.sh`. Modules without inter-dependencies (e.g., `mockserver-war` + `mockserver-proxy-war`, the three JUnit/Spring modules) build in parallel. ~30-40% faster overall build. `-T 1C` (not `-T 3C`) matches the t3.large's 2 vCPUs.

### 1.2 Skip shade/assembly for local development — DONE

`<skipShade>` and `<skipAssembly>` properties added to root `pom.xml`. `skipShade` is wired into the 6 `*-no-dependencies` modules (`mockserver-netty-no-dependencies`, `mockserver-client-java-no-dependencies`, `mockserver-junit-jupiter-no-dependencies`, `mockserver-junit-rule-no-dependencies`, `mockserver-spring-test-listener-no-dependencies`, `mockserver-integration-testing-no-dependencies`). `skipAssembly` is wired into `mockserver-netty` (the only module running the assembly plugin). Used as `./mvnw clean install -DskipShade=true -DskipAssembly=true` for local iteration. **Cannot be used in CI** — `mockserver-netty` invoker tests depend on shaded artifacts.

### 1.3 Quiet test output in CI — DONE

`mockserver.testOutput=quiet` system property + `redirectTestOutputToFile=true` route per-test STARTED/FINISHED lines to per-test files (uploaded as artifacts) rather than the Buildkite log. ~90% log volume reduction without writing a custom listener.

### 1.4 Surefire/Failsafe 3.5.5 — DONE

Upgraded from 2.x. Better JUnit 5 support, improved fork management, deprecation warnings resolved.

### 1.5 Test XML reports + junit-annotate plugin — DONE

`**/target/surefire-reports/TEST-*.xml` and `**/target/failsafe-reports/TEST-*.xml` collected as Buildkite artifacts; `junit-annotate#v2.4.1` posts annotations on failures.

### 1.6 Step and fork timeouts — DONE

Buildkite `timeout_in_minutes` on each step; Surefire `forkedProcessTimeoutInSeconds=1800`.

---

## Phase 2: Active Work

The remaining items, in priority order. All are low-complexity and either zero-divergence or already-justified divergence.

### 2.1 Extract shade configuration to parent POM (was 3.3) — DONE

Shipped: the shared shade execution (`id=shade-no-dependencies`) lives in `mockserver/pom.xml` `<build><pluginManagement>`; all six `*-no-dependencies` modules carry a bare `<plugin>` reference. `mockserver-netty-no-dependencies` overrides `<transformers>` (to set `Main-Class: org.mockserver.cli.Main`) and `<filters>` — both with `combine.self="override"` because, in the pluginManagement→plugin merge, a child list element **replaces** rather than appends.

**Key finding (caught by the JAR diff):** the `io.netty:netty-tcnative-boringssl-static` → `META-INF/native/**` filter is **netty-only**, not shared. The other five modules genuinely bundle those native libraries and shipped them in the baseline; putting that filter in the shared config silently stripped them. It now lives solely in netty's override.

**Verification:** `jar tf | sort` of all six shaded JARs matches the pre-change baseline exactly; netty's `Main-Class` and each module's `Automatic-Module-Name` are preserved.

**Why first:** pure deduplication. Roughly 1,400 LOC of duplicated XML across the 6 `*-no-dependencies` modules collapses to ~250 LOC in `<pluginManagement>` (LOC figures are estimates pending implementation). Zero runtime impact, zero local-vs-CI divergence, makes the existing `skipShade` property easier to maintain.

**Affected modules:** `mockserver-netty-no-dependencies`, `mockserver-client-java-no-dependencies`, `mockserver-junit-jupiter-no-dependencies`, `mockserver-junit-rule-no-dependencies`, `mockserver-spring-test-listener-no-dependencies`, `mockserver-integration-testing-no-dependencies`.

**Approach:**
1. Move the full shade plugin config (relocations, transformers, filters, manifest entries) into the root `mockserver/pom.xml`'s `<pluginManagement>` block.
2. Each `*-no-dependencies` module replaces its inline config with a bare `<plugin><artifactId>maven-shade-plugin</artifactId></plugin>` reference.
3. `mockserver-netty-no-dependencies` overrides only its `ManifestResourceTransformer` mainClass attribute (the others either inherit a default or have no main class).
4. Verify by running `./mvnw clean install` and comparing `jar tf` output of each produced shaded JAR against the pre-change baseline.

**Acceptance criterion:** for every `*-no-dependencies` module, `jar tf` on the new shaded JAR matches the baseline JAR's entry list exactly. JAR size may differ by trivial amounts (manifest line ordering); entry contents must not.

**Risk:** low, but the JAR diff is the gating check — relocations are subtle and a missing rule produces a JAR that compiles fine but breaks at runtime when consumers shade us.

### 2.2 `.mvn/maven.config` + `.mvn/jvm.config` (was 1.2) — DONE

Shipped: `mockserver/.mvn/maven.config` already carried `-Djava.security.egd=file:/dev/./urandom` (the CI double-slash form); `mockserver/.mvn/jvm.config` set to `-Xms2048m -Xmx6144m`. The redundant explicit `-Djava.security.egd` flag was dropped from `local_quick_build.sh` (was single-slash `file:/dev/urandom`) and `buildkite_quick_build.sh`. The files live in `mockserver/.mvn` (alongside the wrapper and the reactor-root pom), not the repo root, since that is where `find_maven_basedir` resolves the project base directory.

> **Heap note:** `mvnw` prepends `jvm.config` then existing `MAVEN_OPTS`, so `MAVEN_OPTS` wins on duplicate `-Xmx`. CI (`buildkite_quick_build.sh`) sets `MAVEN_OPTS=-Xmx6144m` explicitly and `local_quick_build.sh` sets `-Xmx8192m`, so `jvm.config` only governs **bare `./mvnw`** (IDE / ad-hoc) builds. `-Xmx6144m` sizes only the Maven controller JVM — forked test JVMs get their heap from Surefire's argLine — so it is safe; local scripts still override upward to 8 G.

Centralise Maven and JVM defaults so `./mvnw clean install` works without wrapper scripts.

**File:** `.mvn/maven.config`
```
-Djava.security.egd=file:/dev/./urandom
```

> **Note:** `local_quick_build.sh` currently passes `file:/dev/urandom` (single slash) while `buildkite_quick_build.sh` passes `file:/dev/./urandom` (double slash). The `.mvn/maven.config` value above adopts the CI form. Once shipped, local builds via bare `./mvnw` will normalise to the CI form. Both forms are functionally equivalent on Linux; the double-slash form is the historically recommended one for forcing the JVM to use `/dev/urandom` as the non-blocking entropy source. Drop the explicit flag from `local_quick_build.sh` once `.mvn/maven.config` is in place.

**File:** `.mvn/jvm.config`
```
-Xms2048m
-Xmx6144m
```

**Audit before shipping:** every wrapper script under `scripts/` that currently sets `MAVEN_OPTS` or `-Xmx` must be reviewed. If `.mvn/jvm.config` sets `-Xmx6144m` and `local_quick_build.sh` also sets `-Xmx8192m`, the wrapper wins, but double-flag situations can surprise on flag changes. Either:
- Drop the heap flag from `local_quick_build.sh` and let `.mvn/jvm.config` own it (but local has more RAM, so a local-only override may still be wanted), or
- Make `.mvn/jvm.config` set the CI value (6G) and have local scripts override upward.

**Impact:** consistent behaviour for bare `./mvnw` invocations (IDE, ad-hoc command line) without needing a wrapper script.

### 2.3 Buildkite Test Analytics token (was 3.4, partial) — BLOCKED on external setup

XML reports are already collected. Flowing them into the Test Analytics tab (historical durations, flaky test detection) requires a one-time, Buildkite-admin action that cannot be done from the repo:

1. In Buildkite → Analytics, create a test suite for the Java build and copy its **API token**.
2. Store the token as a pipeline secret named `BUILDKITE_ANALYTICS_TOKEN` (Buildkite secrets / the agent environment hook — same mechanism as other build secrets).
3. Only then add the `test-collector` Buildkite plugin to the `:maven: build` step in `.buildkite/pipeline-java.yml`, e.g.:
   ```yaml
   plugins:
     - test-collector#v1.11.0:
         files: "**/target/*-reports/TEST-*.xml"
         format: "junit"
   ```

The plugin **fails the step if `BUILDKITE_ANALYTICS_TOKEN` is unset**, so the pipeline YAML must not be changed until step 2 is complete — otherwise CI breaks for every build. No pipeline edit has been made for this reason.

---

## Dropped Items and Why

The following items from earlier versions of this plan were considered and rejected. Recorded here so future planners don't redo the analysis.

### ❌ Custom `BuildkiteTestRunListener` (~190 LOC of new Java)

`scripts/buildkite_quick_build.sh` already sets `redirectTestOutputToFile=true`, which sends STARTED/FINISHED output to per-test files attached as artifacts. The "90% log volume reduction" the listener was designed for is already achieved by that flag plus `mockserver.testOutput=quiet`. Writing a stateful listener with `ConcurrentHashMap`, atomic counters, and three output modes — to format log output — is complexity that pays back nothing maintainable.

### ❌ Custom `BuildkiteEventSpy` for `--- module-name` group markers

Same shape: ~30 LOC of Java to emit text the shell could emit. If group markers are wanted, a sed pre-filter on Maven output (`sed 's/^\[INFO\] Building \(mockserver-[^ ]*\).*/--- \1/'`) does the job in one line. Avoid baking a Maven extension into the build for log formatting.

### ❌ Parallel Surefire (`parallel=classes`, `threadCount=4`)

Attempted previously; caused **48 test failures** in `mockserver-core` due to shared static state in `ConfigurationProperties` and `MockServerLogger`. The fix path is "refactor 400+ tests to avoid shared mutable statics" — a multi-week rework with significant flake risk. The wall-clock saving is small compared to what `-T 1C` already delivered, and integration tests (where most wall-clock lives) can't be parallelised anyway because they bind ports.

### ❌ Maven Build Cache Extension (`maven-build-cache-extension`)

Three problems compound:
1. Known reliability issues interacting with the shade plugin, which we use on 6 modules.
2. Cache is local; in CI each Docker container starts fresh, so the cache rarely hits.
3. When the cache fails, it fails by silently shipping stale artifacts. That class of bug is much worse than slow clean builds.

For 11 modules, not worth the operational risk.

### ❌ Split Buildkite pipeline into parallel compile/test/package steps

Directly conflicts with Guiding Principle #1. Today `./mvnw clean install` works the same locally and in CI; `local_buildkite_build.sh` runs the CI script verbatim. Splitting CI into multiple steps with artifact hand-offs:
- Reimplements Maven reactor in YAML.
- Pays upload/download overhead per step that often exceeds the parallelism gain for small modules.
- Pays Docker pull + JVM warmup per step.
- Breaks "reproduce CI locally with one command."
- For 11 modules where the reactor already parallelises module builds via `-T`, the realistic win is small.

### ❌ S3-backed Maven dependency cache

`mockserver/mockserver:maven` Docker image already pre-warms `~/.m2`. Adding an S3 cache layer on top adds a bucket, IAM, plugin version churn, and a cross-account dependency for marginal benefit. If dependency cache freshness becomes a problem, rebuild the Docker base image more frequently — same outcome, less operational surface area.

### ❌ Incremental compilation (`install` without `clean`) for non-master CI

Inevitably leads to "works in CI, doesn't reproduce locally" debugging sessions when stale `.class` files mask real failures. Clean builds in CI are cheap insurance.

### ❌ GraalVM native-image as shade plugin alternative

Speculative; would require a major build-system rework for unclear benefit. Out of scope.

### ❌ Maven Wrapper upgrade (3.9.0 → 3.9.15)

Worth doing as routine hygiene, not as part of a build-optimisation plan. The current wrapper works; CI uses 3.9.16 from the Docker image; the wrapper version mismatch has not caused observable problems.

---

## Expected Cumulative Impact

| Item | Local Build | CI Build | Risk |
|------|------------|---------|------|
| 1.1–1.6 (DONE) | ~10% | ~35% | Validated in production |
| 2.1 Shade extract to parent POM (DONE) | 0% | 0% | Verified — JAR diff clean (1,284 LOC removed, 249 added) |
| 2.2 `.mvn/maven.config` + `jvm.config` (DONE) | 0% | 0% | Verified — scripts audited |
| 2.3 Test Analytics token (blocked) | 0% | 0% | None — needs Buildkite suite + secret |

The remaining work is **maintenance and visibility**, not raw speed. The big speed wins were the items shipped in Phase 1.

## Summary of Files Touched by Remaining Work

| File | Change |
|------|--------|
| `mockserver/pom.xml` | Move shade plugin config to `<pluginManagement>` |
| `mockserver/mockserver-netty-no-dependencies/pom.xml` | Strip inline shade config except `ManifestResourceTransformer` override |
| `mockserver/mockserver-client-java-no-dependencies/pom.xml` | Strip inline shade config entirely |
| `mockserver/mockserver-junit-jupiter-no-dependencies/pom.xml` | Strip inline shade config entirely |
| `mockserver/mockserver-junit-rule-no-dependencies/pom.xml` | Strip inline shade config entirely |
| `mockserver/mockserver-spring-test-listener-no-dependencies/pom.xml` | Strip inline shade config entirely |
| `mockserver/mockserver-integration-testing-no-dependencies/pom.xml` | Strip inline shade config entirely |
| `.mvn/maven.config` | New file (Maven flags) |
| `.mvn/jvm.config` | New file (JVM heap defaults) |
| `scripts/*.sh` | Audit `MAVEN_OPTS`/`-Xmx` overlap with `.mvn/jvm.config`; drop explicit `-Djava.security.egd` flags |
| `.buildkite/pipeline-java.yml` | Add `BUILDKITE_ANALYTICS_TOKEN` env (via Buildkite secret) |
