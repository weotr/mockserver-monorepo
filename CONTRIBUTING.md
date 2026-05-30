# Contributing to MockServer

## Issues

If you have any problems, please [check the project issues](https://github.com/mock-server/mockserver-monorepo/issues?state=open) and avoid opening issues that have already been fixed. When you open an issue please provide:

- MockServer version
- How you are running MockServer (Maven plugin, Docker, JAR, etc.)
- MockServer log output at INFO level (or higher)
- What the error is
- What you are trying to do

## Repository Structure

This is a monorepo containing several projects:

| Directory | Language | Description |
|-----------|----------|-------------|
| `mockserver/` | Java | Main MockServer (Netty HTTP server + Maven plugin sibling module) |
| `mockserver/mockserver-maven-plugin/` | Java | Maven plugin for starting/stopping MockServer (separate build, inherits version from parent) |
| `mockserver-ui/` | TypeScript | Dashboard React SPA |
| `mockserver-node/` | JavaScript | Node.js launcher |
| `mockserver-client-node/` | JavaScript | Node.js/browser API client |
| `mockserver-client-python/` | Python | Python API client |
| `mockserver-client-ruby/` | Ruby | Ruby API client |
| `mockserver-performance-test/` | JavaScript | k6 performance tests |

For a deeper picture of how these fit together — module dependencies, the Netty pipeline, request matching, event system, dashboard, TLS — start with the [code overview](docs/code/overview.md) and follow the topic links in [docs/README.md](docs/README.md). High-level architecture: [docs/architecture.md](docs/architecture.md).

## Where to make changes

Most contributions touch one of these areas. Skim the linked docs before opening a PR — they save asking maintainers where a feature should live.

| Change type | Module(s) | Key reference |
|-------------|-----------|---------------|
| New request matcher / matching behaviour | `mockserver/mockserver-core` (model + matchers + serialisation) | [docs/code/request-processing.md](docs/code/request-processing.md), [docs/code/domain-model.md](docs/code/domain-model.md) |
| New action type (response/forward/error variant) | `mockserver/mockserver-core` action + `mockserver-netty` handler wiring | [docs/code/request-processing.md](docs/code/request-processing.md) |
| Netty pipeline / protocol detection / SSE / WebSocket / JSON-RPC | `mockserver/mockserver-netty` (handlers + unification) | [docs/code/netty-pipeline.md](docs/code/netty-pipeline.md), [docs/code/ai-protocol-mocking.md](docs/code/ai-protocol-mocking.md) |
| TLS, mTLS, certificates, JWT auth | `mockserver/mockserver-core/socket/tls/`, `mockserver-netty/unification/` | [docs/code/tls-and-security.md](docs/code/tls-and-security.md) |
| Configuration property (new flag / env var) | `mockserver/mockserver-core/configuration/` + `mockserver.example.properties` + jekyll-www docs | [docs/code/configuration-reference.md](docs/code/configuration-reference.md) |
| Memory / ring buffer / log retention tuning | `mockserver/mockserver-core/log/` + `mockserver-core/mock/` | [docs/code/memory-management.md](docs/code/memory-management.md) |
| Event logging / verification / persistence | `mockserver/mockserver-core/log/` + `mockserver-core/persistence/` | [docs/code/event-system.md](docs/code/event-system.md) |
| Dashboard UI (React/Redux) or live-feed protocol | `mockserver-ui/` (TS) + `mockserver-netty/web/` (server-side WebSocket) | [docs/code/dashboard-ui.md](docs/code/dashboard-ui.md) |
| Java client API or JUnit/Spring integrations | `mockserver/mockserver-client-java`, `mockserver-junit-*`, `mockserver-spring-test-listener` | [docs/code/client-and-integrations.md](docs/code/client-and-integrations.md) |
| Non-Java client (Python / Ruby / Node) | `mockserver-client-python/`, `mockserver-client-ruby/`, `mockserver-client-node/` | [docs/code/client-and-integrations.md](docs/code/client-and-integrations.md) |
| MCP / A2A mocking | Server handler: `mockserver/mockserver-netty/src/main/java/org/mockserver/netty/mcp/`. Builders: `McpMockBuilder` and `A2aMockBuilder` in `mockserver/mockserver-client-java/src/main/java/org/mockserver/client/` | [docs/code/ai-protocol-mocking.md](docs/code/ai-protocol-mocking.md) |
| Build / Maven / dependency upgrade | `mockserver/pom.xml` + child poms + `scripts/buildkite_*.sh` | [docs/operations/build-system.md](docs/operations/build-system.md), Java 11 ceiling in [AGENTS.md](AGENTS.md#java-compatibility-policy) |
| Docker image (server or CI base) | `docker/`, `docker_build/` | [docs/infrastructure/docker.md](docs/infrastructure/docker.md) |
| Helm chart / Kubernetes deployment | `helm/mockserver/`, `helm/mockserver-config/` | [docs/infrastructure/helm.md](docs/infrastructure/helm.md) |
| CI/CD pipeline (Buildkite, GitHub Actions) | `.buildkite/`, `.github/workflows/` | [docs/infrastructure/ci-cd.md](docs/infrastructure/ci-cd.md) |
| AWS infrastructure / Buildkite agents | `terraform/buildkite-agents/`, `terraform/buildkite-pipelines/` | [docs/infrastructure/aws-infrastructure.md](docs/infrastructure/aws-infrastructure.md) |
| Release pipeline (per-component publish) | `scripts/release/components/<component>.sh` | [docs/operations/release-process.md](docs/operations/release-process.md), [docs/operations/release-principles.md](docs/operations/release-principles.md) |
| Consumer docs site | `jekyll-www.mock-server.com/` | [docs/operations/website.md](docs/operations/website.md) |

## Building

### Java (main server)

```bash
cd mockserver && ./mvnw clean install
```

### UI

```bash
cd mockserver-ui && npm ci && npm run build
```

### Node.js client

```bash
cd mockserver-client-node && npm ci && npx grunt
```

### Python client

```bash
cd mockserver-client-python
python3 -m venv .venv
.venv/bin/pip install -e '.[dev]'
.venv/bin/pytest
```

### Ruby client

```bash
cd mockserver-client-ruby
bundle install
bundle exec rspec
```

### Maven plugin

```bash
cd mockserver && ./mvnw clean install -DskipTests
./mvnw -f mockserver-maven-plugin/pom.xml clean verify
```

## Contributions

Pull requests are welcome. Please:

1. Open an issue first to discuss what you plan to change
2. Follow existing code conventions in the module you are changing
3. Add tests for any new functionality
4. Ensure all tests pass before submitting

## Feature Requests

Feature requests are submitted to [GitHub issues](https://github.com/mock-server/mockserver-monorepo/issues?state=open) and tracked on the [project roadmap](https://github.com/orgs/mock-server/projects/1).
