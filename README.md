MockServer &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp; [![Build status](https://badge.buildkite.com/3b6803f4fe98cb5ed7bf18292a1434f800b53d8fecb92811d8.svg?branch=master&style=square&theme=slack)](https://buildkite.com/mockserver/mockserver) 
[![GitHub license](https://img.shields.io/github/license/mock-server/mockserver-monorepo.svg)](https://github.com/mock-server/mockserver-monorepo/blob/master/LICENSE.md) 
[![GitHub stars](https://img.shields.io/github/stars/mock-server/mockserver-monorepo.svg)](https://github.com/mock-server/mockserver-monorepo/stargazers) 
[![Artifact Hub](https://img.shields.io/endpoint?url=https://artifacthub.io/badge/repository/mockserver)](https://artifacthub.io/packages/search?repo=mockserver)
=====

MockServer is an HTTP(S) **mock server and proxy** for testing. Mock any HTTP/HTTPS, REST, gRPC, or JSON-RPC dependency; record-and-replay real traffic as a proxy; and drive it all from a client library (Java, Node, Python, Ruby) or a built-in dashboard. Recent releases add first-class **LLM / AI-agent** provider mocking (Anthropic, OpenAI, Gemini, Bedrock, Ollama and more), an **MCP server** for AI coding assistants, and **chaos / fault injection** on mocked *and* forwarded responses — see the [changelog](changelog.md) for what has shipped in each version.

### Quick Start

Run MockServer with Docker in seconds:

```bash
docker run -d --rm -p 1080:1080 mockserver/mockserver
```

…or, on macOS / Linux, install it with [Homebrew](https://brew.sh/) and run the `mockserver` command:

```bash
brew install mockserver
mockserver -serverPort 1080
```

Then create your first expectation and call it (MockServer exposes a REST control plane on the same port):

```bash
# 1. Mock an endpoint: GET /hello -> 200 "Hello World"
curl -X PUT http://localhost:1080/mockserver/expectation \
  -H 'Content-Type: application/json' \
  -d '{
        "httpRequest":  { "method": "GET", "path": "/hello" },
        "httpResponse": { "statusCode": 200, "body": "Hello World" }
      }'

# 2. Call your mock
curl http://localhost:1080/hello
# -> Hello World
```

The same can be done from any client library or the dashboard at <http://localhost:1080/mockserver/dashboard>. For more configuration options see the [Docker documentation](https://www.mock-server.com/where/docker.html).

### Documentation

For usage guide please see: [www.mock-server.com](https://www.mock-server.com/)

### Developer Documentation

Architecture, code structure, infrastructure, and operations documentation is available in the [docs/](docs/README.md) directory.

### AI Integration

MockServer includes a built-in [MCP](https://modelcontextprotocol.io) server for AI coding assistant integration at `/mockserver/mcp`. See [llms.txt](https://www.mock-server.com/llms.txt) and [AI Integration docs](https://www.mock-server.com/mock_server/ai_mcp_setup.html).

### Change Log

Please see: [Change Log](https://github.com/mock-server/mockserver-monorepo/blob/master/changelog.md)

### Community

<table>
    <tr>
        <td>Discussions</td>
        <td><a href="https://github.com/mock-server/mockserver-monorepo/discussions"><img height="20px" src="https://mock-server.com/images/GitHub_Logo-md.png" alt="GitHub Discussions"></a></td>
    </tr>
    <tr>
        <td>Issues, Bugs &amp; Feature Requests</td>
        <td><a href="https://github.com/mock-server/mockserver-monorepo/issues"><img height="20px" src="https://mock-server.com/images/GitHub_Logo-md.png" alt="GitHub Issues"></a></td>
    </tr>
    <tr>
        <td>Roadmap</td>
        <td><a href="https://github.com/orgs/mock-server/projects/1"><img height="20px" src="https://mock-server.com/images/GitHub_Logo-md.png" alt="GitHub Project"></a></td>
    </tr>
    <tr>
        <td>Security</td>
        <td><a href="https://github.com/mock-server/mockserver-monorepo/blob/master/SECURITY.md"><img height="20px" src="https://mock-server.com/images/GitHub_Logo-md.png" alt="Security Policy"></a></td>
    </tr>
</table>

### Requirements

**Runtime:** MockServer 6.x requires **Java 17+**. The minimum was raised from Java 11 as part of the Jakarta EE 10 / Spring 7 platform modernisation — see the [Java 17 / Jakarta upgrade guide](docs/operations/migration-java17-jakarta.md). If you are still on Java 11, pin to the `5.15.x` line (no longer receiving security updates). The official Docker image already bundles a Java 17 runtime.

**Building from source:** requires **JDK 17+**; the produced bytecode targets Java 17.

**Security Note:** MockServer is a **development and testing tool only**. See [SECURITY.md](SECURITY.md) for important security considerations.

### Versions

##### Maven Central [![mockserver](https://img.shields.io/maven-central/v/org.mock-server/mockserver-netty.svg)](https://central.sonatype.com/search?q=g:org.mock-server)

Maven Central contains the following MockServer artifacts under the `org.mock-server` groupId. Every artifact ships in two forms — `-no-dependencies` (shaded, zero transitive deps, **recommended**) and the plain form (transitive deps declared in the POM, for the rare case where you need to override versions yourself).

**Server:**
* [mockserver-netty-no-dependencies](https://central.sonatype.com/artifact/org.mock-server/mockserver-netty-no-dependencies) / [mockserver-netty](https://central.sonatype.com/artifact/org.mock-server/mockserver-netty) — Netty-based HTTP(S) mock + proxy server (embed in tests or run standalone)
* [mockserver-war](https://central.sonatype.com/artifact/org.mock-server/mockserver-war) — deployable WAR for hosting MockServer in a servlet container (mock mode)
* [mockserver-proxy-war](https://central.sonatype.com/artifact/org.mock-server/mockserver-proxy-war) — deployable WAR for hosting MockServer in a servlet container (proxy mode)

**Java client:**
* [mockserver-client-java-no-dependencies](https://central.sonatype.com/artifact/org.mock-server/mockserver-client-java-no-dependencies) / [mockserver-client-java](https://central.sonatype.com/artifact/org.mock-server/mockserver-client-java) — Java client for the MockServer REST API

**Test framework integrations:**
* [mockserver-junit-rule-no-dependencies](https://central.sonatype.com/artifact/org.mock-server/mockserver-junit-rule-no-dependencies) / [mockserver-junit-rule](https://central.sonatype.com/artifact/org.mock-server/mockserver-junit-rule) — JUnit 4 `@Rule`
* [mockserver-junit-jupiter-no-dependencies](https://central.sonatype.com/artifact/org.mock-server/mockserver-junit-jupiter-no-dependencies) / [mockserver-junit-jupiter](https://central.sonatype.com/artifact/org.mock-server/mockserver-junit-jupiter) — JUnit 5 extension (`@MockServerSettings`, `@MockServerTest`)
* [mockserver-spring-test-listener-no-dependencies](https://central.sonatype.com/artifact/org.mock-server/mockserver-spring-test-listener-no-dependencies) / [mockserver-spring-test-listener](https://central.sonatype.com/artifact/org.mock-server/mockserver-spring-test-listener) — Spring `TestExecutionListener`
* [mockserver-integration-testing-no-dependencies](https://central.sonatype.com/artifact/org.mock-server/mockserver-integration-testing-no-dependencies) / [mockserver-integration-testing](https://central.sonatype.com/artifact/org.mock-server/mockserver-integration-testing) — shared integration-test helpers

**Build-tool plugin:**
* [mockserver-maven-plugin](https://central.sonatype.com/artifact/org.mock-server/mockserver-maven-plugin) — Maven plugin to start, stop, and fork MockServer during the build lifecycle

> **Tip:** The `-no-dependencies` artifacts bundle all dependencies into a single JAR with packages relocated under `shaded_package.*`, so they declare zero transitive dependencies. This avoids classpath conflicts with versions of Netty / Jackson / Guava / Bouncy Castle that your project already uses, and it removes the noise from CVE scanners flagging unused transitive dependencies. See the [Maven Central](https://www.mock-server.com/where/maven_central.html) page for full coordinates, snapshot repository setup, and the executable `jar-with-dependencies` form for command-line use.

> **6.0.0 breaking change:** the `<classifier>shaded</classifier>` form has been removed. Replace `mockserver-netty:<version>:shaded` with `mockserver-netty-no-dependencies:<version>` (and likewise for the other shaded artifacts). The replacement produces the same shaded bytes; only the coordinates change.

SNAPSHOT builds are published to the Sonatype Central Portal snapshot repository at <https://central.sonatype.com/repository/maven-snapshots/org/mock-server/>.

##### Node Module & Grunt Plugin

NPM Registry contains the following module:

* [mockserver-node](https://www.npmjs.org/package/mockserver-node) - a Node.js module and Grunt plugin to start and stop MockServer
    [![mockserver-node](https://nodei.co/npm/mockserver-node.png?downloads=true)](https://www.npmjs.org/package/mockserver-node)
* [mockserver-client-node](https://www.npmjs.org/package/mockserver-client) - a Node.js client for both the MockServer and the proxy 
    [![mockserver-client-node](https://nodei.co/npm/mockserver-client.png?downloads=true)](https://www.npmjs.org/package/mockserver-client)

##### Docker Hub

Docker Hub contains the following artifacts:

* [MockServer Docker Container](https://hub.docker.com/r/mockserver/mockserver/) - a Docker container containing the Netty MockServer and proxy

##### Homebrew [![Homebrew version](https://img.shields.io/homebrew/v/mockserver.svg)](https://formulae.brew.sh/formula/mockserver)

MockServer is available in [Homebrew](https://brew.sh/) (homebrew-core), the package manager for macOS and Linux:

```bash
brew install mockserver   # then run: mockserver -serverPort 1080
```

See the [Homebrew install page](https://www.mock-server.com/where/homebrew.html) and the [command-line usage guide](https://www.mock-server.com/mock_server/running_mock_server.html#running_from_command_line_using_homebrew).

##### Helm Chart

* [MockServer Helm Chart](helm/mockserver/README.md) - installs MockServer into a Kubernetes cluster. The chart is published to the GitHub Container Registry as an OCI artifact (no `helm repo add` needed):

  ```bash
  helm upgrade --install --create-namespace --namespace mockserver --version 7.0.0 mockserver oci://ghcr.io/mock-server/charts/mockserver
  ```

  See the [Install MockServer Helm Chart](helm/mockserver/README.md) guide for all versions and configuration options. A legacy `.tgz` is also available from [www.mock-server.com](https://www.mock-server.com/mockserver-7.0.0.tgz).

##### MockServer Clients

* [mockserver-client-java ![Maven Central](https://img.shields.io/maven-central/v/org.mock-server/mockserver-client-java.svg)](https://central.sonatype.com/artifact/org.mock-server/mockserver-client-java) - a Java client for both the MockServer and the proxy (use the `-no-dependencies` artifact to avoid transitive dependencies)
* [mockserver-client-node ![npm](https://img.shields.io/npm/v/mockserver-client.svg)](https://www.npmjs.org/package/mockserver-client) - a Node.js and [browser](https://github.com/mock-server/mockserver-monorepo/blob/master/mockserver-client-node/mockServerClient.js) client for both the MockServer and the proxy
* [mockserver-client-python](https://pypi.org/project/mockserver-client/) - a Python client for both the MockServer and the proxy
* [mockserver-client-ruby ![Gem](https://badge.fury.io/rb/mockserver-client.png)](https://rubygems.org/gems/mockserver-client) - a Ruby client for both the MockServer and the proxy

##### Community Tools

* [MockServer Browser Admin](https://github.com/johnnywang1994/mockserver-browser-admin) - a React + TypeScript SPA for managing MockServer expectations via a web UI

##### Previous Versions
| Version        | Date        | Git & Docker Tag / Git Hash                                                                                                                                                                                   | Documentation                                 | Java API                                                               | REST API                                                                                  |
|:---------------|:------------|:--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|:----------------------------------------------|:-----------------------------------------------------------------------|:------------------------------------------------------------------------------------------|
| 7.0.0 (latest) | 27 May 2026 | [mockserver-7.0.0](https://github.com/mock-server/mockserver-monorepo/tree/mockserver-7.0.0)   / [33c273](https://github.com/mock-server/mockserver-monorepo/commit/33c2739febd07ce1bf1e3f31ed9d93a61ac871dc) | [Documentation](https://mock-server.com)      | [Java API](https://mock-server.com/versions/7.0.0/apidocs/index.html)  | [7.0.x REST API](https://app.swaggerhub.com/apis/jamesdbloom/mock-server-openapi/7.0.x)   |
| 6.0.0          | 20 May 2026 | [mockserver-6.0.0](https://github.com/mock-server/mockserver-monorepo/tree/mockserver-6.0.0)   / [6a254e](https://github.com/mock-server/mockserver-monorepo/commit/6a254e2a5cb925c41bf8c0ef6a98e2c02712e3ab) | [Documentation](https://mock-server.com)      | [Java API](https://mock-server.com/versions/6.0.0/apidocs/index.html)  | [6.0.x REST API](https://app.swaggerhub.com/apis/jamesdbloom/mock-server-openapi/6.0.x)   |
| 5.15.0         | 11 Jan 2023 | [mockserver-5.15.0](https://github.com/mock-server/mockserver-monorepo/tree/mockserver-5.15.0) / [7c071b](https://github.com/mock-server/mockserver-monorepo/commit/7c071b8be3608036f2a2ea45eee6970d2f2b8d02) | [Documentation](https://5-15.mock-server.com) | [Java API](https://mock-server.com/versions/5.15.0/apidocs/index.html) | [5.15.x REST API](https://app.swaggerhub.com/apis/jamesdbloom/mock-server-openapi/5.15.x) |
| 5.14.0         | 22 Aug 2022 | [mockserver-5.14.0](https://github.com/mock-server/mockserver-monorepo/tree/mockserver-5.14.0) / [808eba](https://github.com/mock-server/mockserver-monorepo/commit/808ebaa44a88b630ca181e62712aa47d4c9c7ff4) | [Documentation](https://5-14.mock-server.com) | [Java API](https://mock-server.com/versions/5.14.0/apidocs/index.html) | [5.14.x REST API](https://app.swaggerhub.com/apis/jamesdbloom/mock-server-openapi/5.14.x) |
| 5.13.2         | 05 Apr 2022 | [mockserver-5.13.2](https://github.com/mock-server/mockserver-monorepo/tree/mockserver-5.13.2) / [81105b](https://github.com/mock-server/mockserver-monorepo/commit/81105b3153674bbe66df612ad1b3a09a34a520cf) | [Documentation](https://5-13.mock-server.com) | [Java API](https://mock-server.com/versions/5.13.2/apidocs/index.html) | [5.13.x REST API](https://app.swaggerhub.com/apis/jamesdbloom/mock-server-openapi/5.13.x) |
| 5.13.1         | 02 Apr 2022 | [mockserver-5.13.1](https://github.com/mock-server/mockserver-monorepo/tree/mockserver-5.13.1) / [39d1cc](https://github.com/mock-server/mockserver-monorepo/commit/39d1cc6251e6dbd00ab8012dbe39def6d8bb7312) | [Documentation](https://5-13.mock-server.com) | [Java API](https://mock-server.com/versions/5.13.1/apidocs/index.html) | [5.13.x REST API](https://app.swaggerhub.com/apis/jamesdbloom/mock-server-openapi/5.13.x) |
| 5.13.0         | 17 Mar 2022 | [mockserver-5.13.0](https://github.com/mock-server/mockserver-monorepo/tree/mockserver-5.13.0) / [604888](https://github.com/mock-server/mockserver-monorepo/commit/604888cdb0f66f1f217e54c4f3ad3e3c7785f3af) | [Documentation](https://5-13.mock-server.com) | [Java API](https://mock-server.com/versions/5.13.0/apidocs/index.html) | [5.13.x REST API](https://app.swaggerhub.com/apis/jamesdbloom/mock-server-openapi/5.13.x) |
| 5.12.0         | 12 Feb 2022 | [mockserver-5.12.0](https://github.com/mock-server/mockserver-monorepo/tree/mockserver-5.12.0) / [61747f](https://github.com/mock-server/mockserver-monorepo/commit/61747fd20316603e7ff4c0dd0e3ee34ea386882f) | [Documentation](https://5-12.mock-server.com) | [Java API](https://mock-server.com/versions/5.12.0/apidocs/index.html) | [5.12.x REST API](https://app.swaggerhub.com/apis/jamesdbloom/mock-server-openapi/5.12.x) |
| 5.11.2         | 08 Nov 2020 | [mockserver-5.11.2](https://github.com/mock-server/mockserver-monorepo/tree/mockserver-5.11.2) / [eb84f2](https://github.com/mock-server/mockserver-monorepo/commit/eb84f20b9485233c6926e4067e1e8de652a112d6) | [Documentation](https://5-11.mock-server.com) | [Java API](https://mock-server.com/versions/5.11.2/apidocs/index.html) | [5.11.x REST API](https://app.swaggerhub.com/apis/jamesdbloom/mock-server-openapi/5.11.x) |
| 5.11.1         | 22 Jul 2020 | [mockserver-5.11.1](https://github.com/mock-server/mockserver-monorepo/tree/mockserver-5.11.1) / [361e5c](https://github.com/mock-server/mockserver-monorepo/commit/361e5c74e5c7fd906957edbd5a46bb27582e4f5c) | [Documentation](https://5-11.mock-server.com) | [Java API](https://mock-server.com/versions/5.11.1/apidocs/index.html) | [5.11.x REST API](https://app.swaggerhub.com/apis/jamesdbloom/mock-server-openapi/5.11.x) |
| 5.11.0         | 08 Jul 2020 | [mockserver-5.11.0](https://github.com/mock-server/mockserver-monorepo/tree/mockserver-5.11.0) / [756758](https://github.com/mock-server/mockserver-monorepo/commit/756758ebe3d032f3852411a9bb91c3c66d819ddc) | [Documentation](https://5-11.mock-server.com) | [Java API](https://mock-server.com/versions/5.11.0/apidocs/index.html) | [5.11.x REST API](https://app.swaggerhub.com/apis/jamesdbloom/mock-server-openapi/5.11.x) |
| 5.10.0         | 24 Mar 2020 | [mockserver-5.10.0](https://github.com/mock-server/mockserver-monorepo/tree/mockserver-5.10.0) / [14124d](https://github.com/mock-server/mockserver-monorepo/commit/14124d32ef96c207cc73cc5334c1d7236d8c7640) | [Documentation](https://5-10.mock-server.com) | [Java API](https://mock-server.com/versions/5.10.0/apidocs/index.html) | [5.10.x REST API](https://app.swaggerhub.com/apis/jamesdbloom/mock-server-openapi/5.10.x) |
| 5.9.0          | 01 Feb 2020 | [mockserver-5.9.0](https://github.com/mock-server/mockserver-monorepo/tree/mockserver-5.9.0)   / [eacf07](https://github.com/mock-server/mockserver-monorepo/commit/eacf07ad1eb738bacbf7c473f0d1aa62b4028602) | [Documentation](https://5-9.mock-server.com)  | [Java API](https://mock-server.com/versions/5.9.0/apidocs/index.html)  | [5.9.x REST API](https://app.swaggerhub.com/apis/jamesdbloom/mock-server-openapi/5.9.x)   |
| 5.8.1          | 23 Dec 2019 | [mockserver-5.8.1](https://github.com/mock-server/mockserver-monorepo/tree/mockserver-5.8.1)   / [f0e9ab](https://github.com/mock-server/mockserver-monorepo/commit/f0e9ab3b64f47f7f8f756d5ae8bf7b1b4611d8e6) | [Documentation](https://5-8.mock-server.com)  | [Java API](https://mock-server.com/versions/5.8.1/apidocs/index.html)  | [5.8.x REST API](https://app.swaggerhub.com/apis/jamesdbloom/mock-server-openapi/5.8.x)   |
| 5.8.0          | 01 Dec 2019 | [mockserver-5.8.0](https://github.com/mock-server/mockserver-monorepo/tree/mockserver-5.8.0)   / [7c9fc5](https://github.com/mock-server/mockserver-monorepo/commit/7c9fc5e5e831feac71dd68d0341ff089f37cec1e) | [Documentation](https://5-8.mock-server.com)  | [Java API](https://mock-server.com/versions/5.8.0/apidocs/index.html)  | [5.8.x REST API](https://app.swaggerhub.com/apis/jamesdbloom/mock-server-openapi/5.8.x)   |
| 5.7.2          | 16 Nov 2019 | [mockserver-5.7.2](https://github.com/mock-server/mockserver-monorepo/tree/mockserver-5.7.2)   / [7c9fc5](https://github.com/mock-server/mockserver-monorepo/commit/7c9fc5e5e831feac71dd68d0341ff089f37cec1e) | [Documentation](https://5-7.mock-server.com)  | [Java API](https://mock-server.com/versions/5.7.2/apidocs/index.html)  | [5.7.x REST API](https://app.swaggerhub.com/apis/jamesdbloom/mock-server-openapi/5.7.x)   |
| 5.7.1          | 09 Nov 2019 | [mockserver-5.7.1](https://github.com/mock-server/mockserver-monorepo/tree/mockserver-5.7.1)   / [0ca353](https://github.com/mock-server/mockserver-monorepo/commit/0ca3537023e9e0f9abcb09c92279891cbc0527c7) | [Documentation](https://5-7.mock-server.com)  | [Java API](https://mock-server.com/versions/5.7.1/apidocs/index.html)  | [5.7.x REST API](https://app.swaggerhub.com/apis/jamesdbloom/mock-server-openapi/5.7.x)   |
| 5.7.0          | 01 Nov 2019 | [mockserver-5.7.0](https://github.com/mock-server/mockserver-monorepo/tree/mockserver-5.7.0)   / [b58bc5](https://github.com/mock-server/mockserver-monorepo/commit/b58bc589efbc76272a2053a64e774a001f1bb0a2) | [Documentation](https://5-7.mock-server.com)  | [Java API](https://mock-server.com/versions/5.7.0/apidocs/index.html)  | [5.7.x REST API](https://app.swaggerhub.com/apis/jamesdbloom/mock-server-openapi/5.7.x)   |
| 5.6.1          | 21 Jul 2019 | [mockserver-5.6.1](https://github.com/mock-server/mockserver-monorepo/tree/mockserver-5.6.1)   / [aec1fb](https://github.com/mock-server/mockserver-monorepo/commit/aec1fbf1e826dc59fe4a19c3331ab6802ec4c3c7) | [Documentation](https://5-6.mock-server.com)  | [Java API](https://mock-server.com/versions/5.6.0/apidocs/index.html)  | [5.6.x REST API](https://app.swaggerhub.com/apis/jamesdbloom/mock-server-openapi/5.6.x)   |
| 5.6.0          | 21 Jun 2019 | [mockserver-5.6.0](https://github.com/mock-server/mockserver-monorepo/tree/mockserver-5.6.0)   / [8f82dc](https://github.com/mock-server/mockserver-monorepo/commit/8f82dc4d37271c3cbfe0b3a1963e91ec3a4ef7a7) | [Documentation](https://5-6.mock-server.com)  | [Java API](https://mock-server.com/versions/5.6.0/apidocs/index.html)  | [5.6.x REST API](https://app.swaggerhub.com/apis/jamesdbloom/mock-server-openapi/5.6.x)   |
| 5.5.4          | 26 Apr 2019 | [mockserver-5.5.4](https://github.com/mock-server/mockserver-monorepo/tree/mockserver-5.5.4)   / [4ffd31](https://github.com/mock-server/mockserver-monorepo/commit/4ffd3162a3250f18d343901b30c3ee71a75b1982) | [Documentation](https://5-5.mock-server.com)  | [Java API](https://mock-server.com/versions/5.5.4/apidocs/index.html)  | [5.5.x REST API](https://app.swaggerhub.com/apis/jamesdbloom/mock-server-openapi/5.5.x)   |
| 5.5.1          | 29 Dec 2018 | [mockserver-5.5.1](https://github.com/mock-server/mockserver-monorepo/tree/mockserver-5.5.1)   / [11d8a9](https://github.com/mock-server/mockserver-monorepo/commit/11d8a96b0eaf07b7fffd29444203503b1cdca653) | [Documentation](https://5-5.mock-server.com)  | [Java API](https://mock-server.com/versions/5.5.1/apidocs/index.html)  | [5.5.x REST API](https://app.swaggerhub.com/apis/jamesdbloom/mock-server-openapi/5.5.x)   |
| 5.5.0          | 15 Nov 2018 | [mockserver-5.5.0](https://github.com/mock-server/mockserver-monorepo/tree/mockserver-5.5.0)   / [06e6fd](https://github.com/mock-server/mockserver-monorepo/commit/06e6fdc4757f13fb5943fc281d5e55dc1c30919d) | [Documentation](https://5-5.mock-server.com)  | [Java API](https://mock-server.com/versions/5.5.0/apidocs/index.html)  | [5.5.x REST API](https://app.swaggerhub.com/apis/jamesdbloom/mock-server-openapi/5.5.x)   |

### Issues

If you have any problems, please [check the project issues](https://github.com/mock-server/mockserver-monorepo/issues?state=open) and avoid opening issues that have already been fixed.  When you open an issue please provide the following information:
- MockServer version (i.e. 7.0.0)
- How your running the MockServer (i.e maven plugin, docker, etc)
- MockServer log output, at INFO level (or higher)
- What the error is
- What you are trying to do

### Contributions

Pull requests are, of course, very welcome! Please read our [contributing to the project](CONTRIBUTING.md) guide first. Then head over to the [open issues](https://github.com/mock-server/mockserver-monorepo/issues?state=open) to see what we need help with. Make sure you let us know if you intend to work on something. Also check out the [project roadmap](https://github.com/orgs/mock-server/projects/1) to see what is already in the backlog.

### Feature Requests

Feature requests are submitted to [GitHub issues](https://github.com/mock-server/mockserver-monorepo/issues?state=open) and tracked on the [project roadmap](https://github.com/orgs/mock-server/projects/1).

### Maintainers
* [James D Bloom](https://blog.jamesdbloom.com)
