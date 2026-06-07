# MockServer Documentation

Comprehensive internal documentation for the MockServer project covering code architecture, infrastructure, build system, CI/CD, and deployment.

## Documentation Index

### Code Architecture

Deep-dive documentation of MockServer's codebase, from high-level module structure down to individual subsystems.

| Document | Level | Description |
|----------|-------|-------------|
| [Code Overview](code/overview.md) | High | Module hierarchy, dependency graph, package layout |
| [Netty Pipeline](code/netty-pipeline.md) | Medium | Port unification, protocol detection, channel handlers, MCP handler, relay pattern |
| [Request Processing](code/request-processing.md) | Medium | Mock matching, proxy forwarding, action dispatch, WAR bridge |
| [Event System](code/event-system.md) | Medium | LMAX Disruptor ring buffer, verification, persistence, observers |
| [Dashboard UI](code/dashboard-ui.md) | Medium | React SPA, Zustand state, nine top-level views (Dashboard / Traffic / Sessions / Composer / Library / Chaos / Drift / Metrics / MCP Tools), WebSocket communication, data assembly |
| [Domain Model](code/domain-model.md) | Low | Model hierarchy, matchers, codecs, OpenAPI support, configuration (incl. MCP) |
| [TLS & Security](code/tls-and-security.md) | Low | BouncyCastle CA, SNI, mTLS, JWT auth, control plane security |
| [Client & Integrations](code/client-and-integrations.md) | Low | MockServerClient, JUnit 4/5, Spring, WebSocket callbacks |
| [Memory Management](code/memory-management.md) | Medium | Log entry and expectation memory analysis, default limit calculation, tuning guide |
| [Metrics & Monitoring](code/metrics.md) | Low | Prometheus metrics, memory monitoring, CSV export |
| [Telemetry](code/telemetry.md) | Low | OpenTelemetry integration: OTLP export, GenAI spans, W3C trace context propagation |
| [AI & RPC Protocol Mocking](code/ai-protocol-mocking.md) | Medium | SSE streaming, JSON-RPC matching, MCP and A2A mock builders, gRPC mocking |
| [LLM Mocking](code/llm-mocking.md) | Medium | LLM response builder, provider codecs, streaming physics, conversation matchers, isolation, MCP tools, dashboard |
| [LLM Codec Golden Files](code/llm-codec-fixtures.md) | Low | Automated wire-format drift detection for the LLM provider codecs: golden-master fixtures, normalization, refresh process |
| [Configuration Reference](code/configuration-reference.md) | Low | Property mechanism, resolution order, four equivalent forms, how to add a property |
| [Drift Detection](code/drift-detection.md) | Low | Mock drift detection: comparing forwarded responses against stub expectations |
| [WASM Rules](code/wasm-rules.md) | Low | WASM custom rule engine: chicory interpreter, module ABI, REST endpoints, configuration |
| [Async Messaging](code/async-messaging.md) | Low | AsyncAPI broker mocking: spec parsing, example generation, Kafka/MQTT publisher adapters, orchestrator |
| [HTTP/3 (QUIC)](code/http3.md) | Low | Experimental HTTP/3 support: Http3Server, QUIC native dependency, MVP boundaries |

### Infrastructure

AWS accounts, CI/CD pipelines, container images, and Kubernetes deployment.

| Document | Description |
|----------|-------------|
| [AWS Infrastructure](infrastructure/aws-infrastructure.md) | AWS accounts, Terraform IaC, EC2 agents, S3 hosting, CloudFront CDN |
| [CI/CD](infrastructure/ci-cd.md) | Buildkite pipelines and GitHub Actions workflows |
| [Docker](infrastructure/docker.md) | Docker images, variants, multi-arch builds, and Compose examples |
| [Helm & Kubernetes](infrastructure/helm.md) | Helm charts, deployment templates, and Kind-based testing |
| [Service Mesh / Sidecar](infrastructure/service-mesh.md) | Transparent HTTP interception and Kubernetes sidecar deployment |
| [AWS SES Email Forwarding](infrastructure/aws-ses-email-forwarding.md) | SES catch-all email forwarding for mock-server.com |

### Operations

Build process, releases, dependencies, security scanning, and the documentation website.

| Document | Description |
|----------|-------------|
| [Build System](operations/build-system.md) | Maven configuration, profiles, plugins, and build scripts |
| [Release Process](operations/release-process.md) | End-to-end release workflow with Mermaid diagrams |
| [Security](operations/security.md) | Consolidated security overview: CodeQL, Dependabot, Snyk, AI security review, SNAPSHOT policy |
| [Snyk Security](operations/snyk-security.md) | Vulnerability scanning, CLI usage, javax/jakarta constraints, triage workflow |
| [Website](operations/website.md) | Jekyll documentation site structure and publishing |
| [Testing](testing.md) | Test frameworks, module inventory, architecture, configuration, coverage gaps, CI execution |
| [Performance Tuning](operations/performance-tuning.md) | Internal companion to the website performance page: where the budget goes, rules of thumb, JVM flags, measuring, regression triage |
| [AI-Native SDLC Principles](operations/ai-native-sdlc-principles.md) | Principles for working with AI across the SDLC: spec-first, verification, context, guardrails, the lethal trifecta |
| [AI-Assisted Development](operations/ai-assisted-development.md) | AI development approach, adversarial review, testing backstop, structural safety |
| [OpenCode Configuration](operations/opencode-configuration.md) | AI harness: config, agents, rules, skills, commands, plugins |
| [OpenCode Building Blocks](operations/opencode-building-blocks.md) | Generic guide to the 9 building blocks: what each controls, when to use which, and how they fit together |

### Plans

| Document | Description |
|----------|-------------|
| [Security Defaults](plans/security-defaults.md) | Insecure default flips planned for the next major release |
| [LLM & Agent Mocking Roadmap](plans/mockserver-llm-mocking.md) | Remaining work items after M0–M5 + U1–U4 delivery (drift detection, chaos profiles, tool-call assertions, etc.) |

### Other

| Document | Description |
|----------|-------------|
| [Architecture](architecture.md) | Original high-level architecture overview (see also [Code Overview](code/overview.md)) |

## Quick Reference

```
mockserver-monorepo/
├── mockserver/                     # Java server (multi-module Maven project)
│   ├── mockserver-core/            # Core domain model, matching, serialisation
│   ├── mockserver-client-java/     # Java client library
│   ├── mockserver-netty/           # Netty-based HTTP server (main artifact)
│   ├── mockserver-war/             # WAR-packaged mock server
│   ├── mockserver-proxy-war/       # WAR-packaged proxy
│   ├── mockserver-junit-rule/      # JUnit 4 integration
│   ├── mockserver-junit-jupiter/   # JUnit 5 integration
│   ├── mockserver-spring-test-listener/ # Spring test integration
│   ├── mockserver-testing/         # Shared test utilities
│   └── mockserver-integration-testing/ # Integration test infrastructure
├── examples/                       # Runnable usage examples (java/node/python/ruby/curl/json/docker-compose/wasm/chaos)
├── mockserver-ui/                  # React dashboard UI (Vite + TypeScript)
├── mockserver-node/                # Node.js MockServer launcher (npm)
├── mockserver-client-node/         # Node.js/browser client library (npm)
├── mockserver-client-python/       # Python client library (PyPI)
├── mockserver-client-ruby/         # Ruby client library (RubyGems)
├── mockserver-performance-test/    # k6-based performance tests
├── container_integration_tests/    # Docker & Helm integration tests
├── jekyll-www.mock-server.com/     # Jekyll documentation website
├── helm/                           # Helm charts (mockserver + mockserver-config)
├── docker/                         # Production Docker images (5 variants)
├── docker_build/                   # CI build Docker images
├── terraform/                      # Terraform IaC (Buildkite agents + pipelines)
├── scripts/                        # Build, deploy, and utility scripts
└── docs/                           # This documentation (you are here)
    ├── code/                       #   Code architecture (14 docs)
    ├── infrastructure/             #   AWS, CI/CD, Docker, Helm, Service Mesh (6 docs)
    ├── operations/                 #   Build, release, deps, security, website, perf (11 docs)
    ├── plans/                      #   Active plans and RFCs (3 docs)
    └── testing.md                  #   Test frameworks, architecture, config, coverage, CI
```

## Key Links

- **Website:** https://www.mock-server.com
- **GitHub:** https://github.com/mock-server/mockserver-monorepo
- **Docker Hub:** https://hub.docker.com/r/mockserver/mockserver
- **Maven Central:** `org.mock-server:mockserver-netty`
- **Helm Chart Repo:** https://www.mock-server.com/mockserver-6.1.0.tgz
- **SwaggerHub API:** https://app.swaggerhub.com/apis/jamesdbloom/mock-server-openapi
- **Buildkite:** https://buildkite.com/mockserver/mockserver
- **Snyk:** https://app.snyk.io/org/mockserver/projects
