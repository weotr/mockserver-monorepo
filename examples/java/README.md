# Java Examples

Java client and proxy examples for MockServer. Unlike the other tracks, this is a
**buildable Maven module** (`org.mock-server:mockserver-examples`) wired into the reactor,
so the examples are **compiled and tested in CI** and cannot silently rot.

## What it demonstrates

- **Mocking scenarios** — [`src/main/java/org/mockserver/examples/mockserver/`](src/main/java/org/mockserver/examples/mockserver/): response actions, forward actions, callbacks, OpenAPI expectations, request matchers, verification, recording/retrieval, interactive breakpoints (modify proxied exchanges).
- **Stateful scenarios** — [`src/test/java/org/mockserver/examples/mockserver/StatefulScenarioExamples.java`](src/test/java/org/mockserver/examples/mockserver/StatefulScenarioExamples.java): a single runnable, self-asserting test demonstrating all 5 canonical stateful-scenario features via the typed Java client — a login state machine (`withScenarioName`/`withScenarioState`/`withNewScenarioState`), sequential multi-response cycling (`withHttpResponses` + `ResponseMode.SEQUENTIAL`), a timed auto-transition (`scenario(name).set(state, ms, next)`), an external trigger (`scenario(name).trigger(state)`), and a cross-protocol trigger (`withCrossProtocolScenario(...)`). It starts its own embedded MockServer by default, or connects to one given by `MOCKSERVER_HOST`/`MOCKSERVER_PORT`, and resets the server before each scenario.
- **Load scenarios** — [`src/test/java/org/mockserver/examples/mockserver/LoadScenarioExamples.java`](src/test/java/org/mockserver/examples/mockserver/LoadScenarioExamples.java): a single runnable, self-asserting test exercising the **Load Scenario registry** via the typed Java client — register (`loadScenario`), start (`startLoadScenarios`), list (`loadScenarios`, asserting `RUNNING`), live status (`getLoadScenario`), stop (`stopLoadScenarios`) and clear (`clearLoadScenarios`) — driving a realistic multi-stage scenario (RATE ramp → VU hold → PAUSE with two Velocity-templated steps and a `withStartDelayMillis`). It starts its own embedded MockServer **with load generation enabled** (`configuration().loadGenerationEnabled(true)`), or connects to one given by `MOCKSERVER_HOST`/`MOCKSERVER_PORT` (which must itself set `-Dmockserver.loadGenerationEnabled=true`).
- **Proxying with a range of HTTP libraries** — [`src/main/java/org/mockserver/examples/proxy/`](src/main/java/org/mockserver/examples/proxy/): Apache HttpClient, Google HTTP Client, JDK `HttpURLConnection`, Jersey, Jetty, Spring `RestTemplate`, and Spring `WebClient`.

## Prerequisites

- JDK 17+ and Maven (or run from the repo's reactor).

## Run

```bash
# Compile/package within the reactor (resolves sibling MockServer artifacts):
cd ../../mockserver
mvn -pl ../examples/java -am package -DskipTests

# Run the example tests (they start a MockServer and exercise the scenarios):
mvn -pl ../examples/java -am test
```

## Expected output

`BUILD SUCCESS`, with the example tests starting an embedded MockServer, registering
expectations, exercising each client/scenario, and verifying the recorded interactions.

---

For conceptual documentation see https://www.mock-server.com and the other tracks in
[`../`](../) (Node, Python, Ruby, curl, JSON, Docker Compose, WASM, chaos).
