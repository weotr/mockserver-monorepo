# Java Examples

Java client and proxy examples for MockServer. Unlike the other tracks, this is a
**buildable Maven module** (`org.mock-server:mockserver-examples`) wired into the reactor,
so the examples are **compiled and tested in CI** and cannot silently rot.

## What it demonstrates

- **Mocking scenarios** — [`src/main/java/org/mockserver/examples/mockserver/`](src/main/java/org/mockserver/examples/mockserver/): response actions, forward actions, callbacks, OpenAPI expectations, request matchers, verification, recording/retrieval.
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
