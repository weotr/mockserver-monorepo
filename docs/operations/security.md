# Security

MockServer's security scanning, vulnerability management, and the security posture of released and pre-release artifacts.

## Overview

MockServer is a **development and testing tool** -- it is not designed for production deployment. Its security posture reflects this: the project invests heavily in automated scanning and dependency management to keep the supply chain clean, while deliberately retaining certain capabilities (like SSRF forwarding and trust-all TLS) that are essential for testing but would be vulnerabilities in a production service.

Users who need to lock down a MockServer deployment can harden these capabilities through configuration. The consumer-facing [**API Security: Configuration Hardening**](https://www.mock-server.com/mock_server/control_plane_authorisation.html#configuration_hardening) guide ([source](../../jekyll-www.mock-server.com/mock_server/control_plane_authorisation.html)) documents the recommended property values -- loopback binding, SSRF blocking, upstream TLS validation, the TLS protocol floor, response-template class restrictions, request-parsing limits, and control-plane authentication (mTLS/JWT).

See [SECURITY.md](../../SECURITY.md) for the full security policy, including intentional security behaviours and vulnerability reporting.

## Static Analysis: CodeQL

GitHub's CodeQL semantic analysis runs automatically on:
- Every push to `master`
- Every pull request targeting `master`
- Weekly (Tuesdays at 22:00 UTC)

CodeQL scans **four languages** in the monorepo:

| Language | Scope |
|----------|-------|
| Java | `mockserver/` (server, core, clients, integrations) |
| JavaScript | `mockserver-ui/`, `mockserver-client-node/`, `mockserver-node/` |
| Python | `mockserver-client-python/` |
| Ruby | `mockserver-client-ruby/` |

Results appear in the [GitHub Security tab](https://github.com/mock-server/mockserver-monorepo/security/code-scanning). CodeQL detects issues including SQL injection, path traversal, insecure deserialization, cross-site scripting, and other OWASP Top 10 categories.

**Workflow:** [`.github/workflows/codeql-analysis.yml`](../../.github/workflows/codeql-analysis.yml)

## Dependency Scanning: Dependabot

Dependabot monitors **8 package ecosystems** across the monorepo for outdated and vulnerable dependencies:

| Ecosystem | Directory(ies) | PR Limit |
|-----------|----------------|:--------:|
| Maven | `/mockserver` | 20 |
| Maven | `/mockserver/mockserver-maven-plugin` | 10 |
| npm | `/mockserver-ui`, `/mockserver-client-node`, `/mockserver-node`, `/.opencode` | 10 |
| pip | `/mockserver-client-python` | 10 |
| Bundler | `/mockserver-client-ruby`, `/jekyll-www.mock-server.com` | 10 |
| GitHub Actions | `/` | 10 |
| Docker | `/docker` + subdirs, `/docker_build/*` | 10 |
| Terraform | `/terraform/*` | 10 |

The Docker and Terraform directory columns are summarised; Dependabot has no glob support, so each directory is listed explicitly in [`.github/dependabot.yml`](../../.github/dependabot.yml) (8 Docker dirs, 4 Terraform dirs). When you add a new Docker/Terraform directory, add it there too or it will not be scanned.

Dependabot runs **daily** and opens pull requests for version updates and security patches. Minor and patch updates are **grouped per ecosystem** (e.g. `maven-minor-and-patch`) so related bumps land in a single PR instead of many.

### Namespace Migration Status

The `javax` → `jakarta` namespace migration is **complete** (Spring 7, Spring Boot 4, Tomcat 11, Jetty 12, Jersey 4, jakarta.* artifacts at EE 10+). The Dependabot ignore list no longer carries jakarta-related blocks. JDK-namespace `javax.*` (e.g. `javax.net.ssl`, `javax.xml.*`, `javax.script.*`, `javax.annotation.Nullable` JSR-305) remains unchanged — those classes ship with the JDK and stay `javax`.

See the Java compatibility policy in [AGENTS.md](../../AGENTS.md#java-compatibility-policy).

### Version Ceilings (Java 17 Floor)

MockServer targets **Java 17** as the minimum supported runtime. Some dependencies drop Java 17 support in newer major lines, so they are pinned below the version that requires a newer JDK. These ceilings are enforced in `.github/dependabot.yml` with explicit `versions: [">=X.0.0"]` ignore entries (stricter than ignoring only `version-update:semver-major`, and applied in **every** Maven block that references the dependency).

| Dependency | Ceiling | Reason |
|------------|---------|--------|
| `com.puppycrawl.tools:checkstyle` | `< 13.0.0` (stay on 12.x) | checkstyle 13.x is compiled for Java 21 (class file version 65.0) and fails to load under Java 17 — see the CodeQL `Analyze (java)` build, which runs on Java 17 |
| `org.infinispan:infinispan-core` | `< 15.0.0` (stay on 14.0.x) | Infinispan 15.x requires Java 21+. The 14.0.x line is the last to support Java 17. Used only by `mockserver-state-infinispan`. |

**When raising the Java floor:** remove the corresponding ceiling here and the matching ignore entries in `.github/dependabot.yml`, then let the dependency upgrade. The Dependabot ignore does not block **manual** version bumps in `pom.xml` — keep this table in mind when hand-editing dependency versions.

### Native / Platform Dependencies

Dependencies that interact with the OS kernel or native libraries, added for specific platform features. These are safe on all platforms — callers detect availability at runtime and fall through when unavailable.

| Dependency | Version | Module | Purpose | Java 17 compatible |
|------------|---------|--------|---------|:---:|
| `net.java.dev.jna:jna` | `${jna.version}` (5.17.0) | `mockserver-netty` | JNA-based `getsockopt(SO_ORIGINAL_DST)` for transparent proxy original-destination resolution (`SoOriginalDstResolver`). O(1) socket option read, tried before the O(n) conntrack table scan. | Yes (supports Java 8+) |
| `io.netty:netty-transport-classes-epoll` | `${netty.version}` | `mockserver-core`, `mockserver-netty` | Pure-Java API classes for `EpollSocketChannel`, `EpollEventLoopGroup`, `EpollServerSocketChannel`, and `Epoll.isAvailable()` — needed at compile time by `NettyTransport` (transport selection) and `SoOriginalDstResolver` (fd extraction). No native classifier (the `.so` is only needed at runtime on Linux). | Yes (follows Netty BOM) |
| `io.netty:netty-transport-native-epoll` (classifier: `linux-x86_64`) | `${netty.version}` | `mockserver-netty` (runtime) | Native JNI library that activates `Epoll.isAvailable()` on Linux x86_64. Bundled in the distribution jar-with-dependencies and Docker images. Inert on non-Linux platforms. | Yes (follows Netty BOM) |
| `io.netty:netty-transport-native-epoll` (classifier: `linux-aarch_64`) | `${netty.version}` | `mockserver-netty` (runtime) | Native JNI library that activates `Epoll.isAvailable()` on Linux aarch64 (ARM64). Bundled in the distribution jar-with-dependencies and Docker images. Inert on non-Linux/non-ARM platforms. | Yes (follows Netty BOM) |
| `io.netty.incubator:netty-incubator-codec-http3` | `0.0.30.Final` | `mockserver-netty` (compile) | HTTP/3 codec for experimental QUIC support. Transitively pulls `netty-incubator-codec-native-quic` (0.0.73.Final) with native classifiers for linux-x86_64, linux-aarch_64, osx-x86_64, osx-aarch_64, windows-x86_64. The native artifact contains a BoringSSL JNI binding. Fail-soft at runtime: if the native cannot be loaded, `Quic.isAvailable()` returns false and the HTTP/3 server is not started. | Yes (incubator, pre-release API) |

### Embedded Data Grid Dependencies (Optional Module)

Dependencies introduced by `mockserver-state-infinispan`, which provides the Infinispan-backed `StateBackend` for clustered MockServer state. This module is **optional** -- it is not pulled into `mockserver-core` or any other module. Its transitive dependencies enter CodeQL/Dependabot scan scope only when the module is included in the reactor build.

| Dependency | Version | Module | Purpose | Java 17 compatible |
|------------|---------|--------|---------|:---:|
| `org.infinispan:infinispan-core` | 14.0.35.Final | `mockserver-state-infinispan` | Embedded (non-server) Infinispan cache manager for LOCAL and clustered KV stores. Provides the `StateBackend` implementation when `stateBackend=infinispan` is configured. | Yes (14.0.x line targets Java 11+; 15.x raises to Java 21) |
| `org.jgroups:jgroups` | (transitive of infinispan-core) | `mockserver-state-infinispan` | Cluster transport for Infinispan. In LOCAL mode (default, `clusterEnabled=false`), no JGroups transport is started. In clustered mode (`clusterEnabled=true`), JGroups provides the SHARED_LOOPBACK in-JVM transport (for testing) or TCP transport for multi-host clustering. The default built-in JGroups stack (`jgroups-loopback.xml`) uses SHARED_LOOPBACK, which does not open network sockets; custom multi-host stacks use TCP bound to loopback by default. | Yes |
| `org.infinispan.protostream:protostream` | (transitive of infinispan-core) | `mockserver-state-infinispan` | Protocol Buffers serialization framework used internally by Infinispan. Not used directly by MockServer's clustered wire format (which uses `JavaSerializationMarshaller` with an explicit allow-list). | Yes |

**JGroups network security note:** In LOCAL mode (default, `clusterEnabled=false`), JGroups does not open any network listeners. In clustered mode (`clusterEnabled=true`), the default built-in JGroups stack uses `SHARED_LOOPBACK` transport (in-process, no network I/O), suitable for embedded testing. For multi-host clustering, users must provide a custom JGroups stack via the `clusterTransportConfig` property pointing to a JGroups XML file with a real transport (TCP/UDP) and appropriate discovery protocol (TCPPING, DNS_PING, etc.). The TCP transport should be configured with explicit bind addresses and firewall rules appropriate to the deployment environment.

**Infinispan serialization allow-list (P0 security gate -- RESOLVED in Phase 2c):** The Phase 2b LOCAL-mode backend used `global.serialization().allowList().addRegexp(".*")` -- a wildcard that permits deserialization of any class. This was safe in LOCAL mode because caches are heap-only with no network marshalling. Phase 2c **resolves this P0 gate** by configuring the clustered path with:

1. `JavaSerializationMarshaller` as the explicit marshaller (instead of ProtoStream, to handle the generic `VersionedWrapper<V>` types without per-type proto schema definitions)
2. An **explicit package allow-list** restricted to exactly the types that cross the wire:
   - `org.mockserver.state.infinispan.*` (VersionedWrapper)
   - `org.mockserver.state.*` (ExpectationEntry, Blob)
   - `org.mockserver.mock.*`, `org.mockserver.model.*`, `org.mockserver.matchers.*` (domain model)
   - `com.fasterxml.jackson.*` (ObjectNode for CRUD entities)
   - `java.lang.*`, `java.util.*`, `java.time.*`, `[B` (JDK types, byte arrays)

The `ExpectationEntry` uses custom `writeObject`/`readObject` to serialize the `Expectation` as its JSON string (via `ExpectationDTO`), avoiding the need for the entire domain model to implement `Serializable`. The LOCAL-mode path retains the `".*"` wildcard because heap-only storage never deserializes untrusted bytes.

**Clustering limitation -- Scenario state transitions are not yet clustered.** While the `scenarioStates` KV store in `StateBackend` is replicated across cluster nodes (REPL_SYNC), the `ScenarioManager` that drives `matchesAndTransition()` / `transitionState()` still uses a node-local in-memory map. Expectations using scenario sequencing (`scenarioName` + `scenarioState` / `newScenarioState`) should not rely on cross-node state consistency. This is a planned follow-up to the Phase 2c clustering work.

### Test Dependencies (Docker-Gated)

Test-scoped dependencies used for Docker-gated integration tests. These are never bundled in released artifacts.

| Dependency | Version | Module | Purpose |
|------------|---------|--------|---------|
| `org.testcontainers:testcontainers` | 1.21.4 | `mockserver-async` (test) | Core Testcontainers API for Docker-gated integration tests |
| `org.testcontainers:kafka` | 1.21.4 | `mockserver-async` (test) | Kafka container module for live-broker integration tests |

The Testcontainers version (1.21.4) is aligned with the existing `mockserver-testcontainers` module. Note that `mockserver-testcontainers` depends on `org.testcontainers:testcontainers` (and its transitive `docker-java-*` 3.4.2 artifacts) at **compile scope** — not test scope — because its public `MockServerContainer` extends Testcontainers' `GenericContainer`; consumers of `mockserver-testcontainers` therefore resolve Testcontainers 1.21.4 transitively (overridable via their own dependency management), and these artifacts are in CodeQL/Dependabot scan scope for that module. The 1.20.6 to 1.21.4 bump was required to fix `DockerClientFactory.isDockerAvailable()` returning false on Docker Desktop 4.67+ / Engine 29.x / API 1.54 — the bundled docker-java 3.4.1 in 1.20.6 got a 400 on the info endpoint; 1.21.4 bundles docker-java 3.4.2 and includes explicit fixes for recent Docker Engine API changes. MQTT integration tests use a `GenericContainer` with `eclipse-mosquitto:2.0` (no additional Testcontainers module needed). Transparent-proxy end-to-end tests (`SoOriginalDstEndToEndIT`, `TproxyEndToEndIT`) use the Docker CLI directly (via `ProcessBuilder`) to build and run privileged containers with NET_ADMIN for iptables REDIRECT/TPROXY rule setup — they do not use Testcontainers.

### Maven Dependency Graph Submission

GitHub's built-in dependency graph automatically indexes all manifest files (`pom.xml`, `package.json`, `Gemfile`, `requirements.txt`) and their transitive dependencies. This enables Dependabot vulnerability alerts for the full dependency tree -- currently tracking 2000+ packages including 347 Maven dependencies.

## Vulnerability Scanning: Snyk

Snyk provides a second layer of vulnerability scanning, independent of Dependabot:

- **PR status checks:** Two Snyk integrations (`security/snyk (mockserver)` and `security/snyk (jamesdbloom)`) run on every pull request
- **Dashboard:** [app.snyk.io/org/mockserver/projects](https://app.snyk.io/org/mockserver/projects)
- **Policy file:** [`.snyk`](../../.snyk) documents any vulnerability IDs that are explicitly ignored, along with the rationale and a review date

The `.snyk` policy file excludes `mockserver-examples` (sample code, not shipped). As of the Java 17 / Jakarta EE 10 modernisation the ignore list is **empty** — the Java-11-era ignores (which suppressed ~20 Spring/Jetty/Boot/OkHttp/Reactor CVEs whose only fix required Java 17+) were removed once those vulnerable versions left the dependency tree. Vulnerabilities are now resolved through normal upgrades; add a new, dated ignore only when a deliberate constraint genuinely blocks a fix.

### Renewing Snyk ignores

When an ignore **is** added, give it a dated `expires:` (convention: 3 months out) so it cannot silently outlive its rationale. Renewal is a manual checkpoint: before the expiry date, re-run the Snyk scan, confirm the constraint still holds, and either remove the ignore (if the fix is now available) or refresh the `expires:` date with an updated reason. An empty ignore list (the current state) needs no renewal.

See [Snyk Security](snyk-security.md) for the full triage workflow, CLI commands, and vulnerability status by module.

## AI Security Review

In addition to automated scanning, every code change receives a security-focused review as part of the [AI-assisted development process](ai-assisted-development.md):

### Dedicated Security Auditor Agent

A specialist `security-auditor` AI agent performs targeted security reviews with a checklist covering:

- **Secrets & credentials** -- hardcoded tokens, API keys, connection strings, leaked PEM/JKS content, `.env` files
- **Input validation** -- untrusted data paths, missing bounds checks, charset assumptions
- **Injection prevention** -- command injection, LDAP injection, XSS, XXE, SSRF
- **Network security** -- TLS defaults, certificate validation, cipher suite selection, hostname verification
- **Java-specific** -- unsafe deserialization, `Runtime.exec()` usage, weak random, information leakage
- **Netty-specific** -- malformed request handling, ByteBuf release, pipeline state, WebSocket validation
- **Dependencies** -- known CVEs, version pinning, transitive dependency risk

### Security Lens in Every Code Review

The Review Constitution (applied to every commit, not just security-flagged ones) includes an **Insecurity lens** based on STRIDE threat modelling with 13 security principles, including MockServer-specific rules:

- TLS certificate validation must be explicit
- Control plane must be protectable
- Template injection must be prevented
- CORS headers must not weaken security

## GitHub Security Features

The repository uses several GitHub security features:

| Feature | Status | Purpose |
|---------|--------|---------|
| [Code scanning (CodeQL)](https://github.com/mock-server/mockserver-monorepo/security/code-scanning) | Active | Static analysis for vulnerabilities |
| [Dependabot alerts](https://github.com/mock-server/mockserver-monorepo/security/dependabot) | Active | Vulnerable dependency detection |
| [Dependabot security updates](https://github.com/mock-server/mockserver-monorepo/security/dependabot) | Active | Automatic PRs for security fixes |
| [Dependency graph](https://github.com/mock-server/mockserver-monorepo/network/dependencies) | Active | Transitive dependency visibility |
| [Security advisories](https://github.com/mock-server/mockserver-monorepo/security/advisories) | Active | Private vulnerability reporting |
| Secret scanning | Active (GitHub default) | Prevents accidental secret commits |

## SNAPSHOT and Pre-Release Versions

### What are SNAPSHOT versions?

In the Maven ecosystem, a `-SNAPSHOT` suffix (e.g., `5.16.0-SNAPSHOT`) indicates the **in-development** version of the next release. SNAPSHOT artifacts are published to the Sonatype snapshots repository and represent the latest state of the `master` branch.

### Security status of pre-release artifacts

**SNAPSHOT and pre-release versions may contain unresolved security advisories.** This applies to:

| Artifact | Pre-Release Identifier | Registry |
|----------|----------------------|----------|
| Java JARs | `-SNAPSHOT` suffix (e.g., `5.16.0-SNAPSHOT`) | Maven Central snapshots |
| Docker images | `latest` and `SNAPSHOT` tags | Docker Hub |
| Node.js packages | Published only at release time | npm |
| Python package | Published only at release time | PyPI |
| Ruby gem | Published only at release time | RubyGems |

**At formal release time**, all known security issues are resolved to the extent technically possible. This means:

1. All Dependabot and Snyk alerts with available patches are addressed
2. Dependencies are updated to their latest compatible versions
3. Any new CodeQL findings are reviewed and resolved
4. The Snyk policy file's ignore expiry dates are reviewed and renewed only when a deliberate constraint still prevents a fix

**Between releases**, the `master` branch and SNAPSHOT artifacts may temporarily carry unresolved advisories -- for example, when a new CVE is published against a dependency but the fix has not yet been integrated.

### Recommendations for Consumers

- **For maximum security:** Pin to a specific release version (e.g., `6.1.0`), not `latest` or `SNAPSHOT`
- **For Renovate/Dependabot users:** Configure version constraints to only match release versions, not SNAPSHOTs
- **For Docker users:** Use versioned tags (e.g., `mockserver/mockserver:6.1.0`) rather than `latest`
- **Subscribe to releases:** Watch the [GitHub releases page](https://github.com/mock-server/mockserver-monorepo/releases) for new versions with resolved security issues

## Vulnerability Reporting

To report a security vulnerability in MockServer, use:
- **GitHub Security Advisories:** https://github.com/mock-server/mockserver-monorepo/security/advisories/new
- **Email:** Contact the maintainers through GitHub

Do not open public issues for security vulnerabilities. See [SECURITY.md](../../SECURITY.md) for full reporting guidelines.
