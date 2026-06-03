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

**When raising the Java floor:** remove the corresponding ceiling here and the matching ignore entries in `.github/dependabot.yml`, then let the dependency upgrade. The Dependabot ignore does not block **manual** version bumps in `pom.xml` — keep this table in mind when hand-editing dependency versions.

### Native / Platform Dependencies

Dependencies that interact with the OS kernel or native libraries, added for specific platform features. These are safe on all platforms — callers detect availability at runtime and fall through when unavailable.

| Dependency | Version | Module | Purpose | Java 17 compatible |
|------------|---------|--------|---------|:---:|
| `net.java.dev.jna:jna` | `${jna.version}` (5.17.0) | `mockserver-netty` | JNA-based `getsockopt(SO_ORIGINAL_DST)` for transparent proxy original-destination resolution (`SoOriginalDstResolver`). O(1) socket option read, tried before the O(n) conntrack table scan. | Yes (supports Java 8+) |
| `io.netty:netty-transport-classes-epoll` | `${netty.version}` | `mockserver-netty` | Pure-Java API classes for `EpollSocketChannel` and `Epoll.isAvailable()` — needed at compile time by `SoOriginalDstResolver` to extract the file descriptor from epoll channels. No native classifier (the `.so` is only needed at runtime on Linux). | Yes (follows Netty BOM) |

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
