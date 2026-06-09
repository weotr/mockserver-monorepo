# Docker Compose Examples

Minimal, runnable `docker-compose.yml` files demonstrating different ways to configure and run MockServer in Docker. Each example is exercised in CI by `container_integration_tests/<same-name>/` — those tests overlay a `client:` sidecar and replace the public image with a locally-built one, but the MockServer configuration here is the single source of truth, so the published examples and the tested configurations cannot drift apart.

## Running an example

```bash
cd mock-from-openapi
docker compose up -d
# MockServer is now reachable on the host port exposed by the example
docker compose down
```

## Quick-start recipes

Task-oriented, one-command starting points for the most common use cases. Each is a self-contained directory with its own `README.md`.

| Recipe | What it does |
|--------|--------------|
| [`mock-from-openapi`](mock-from-openapi) | Serve mocks generated from a mounted OpenAPI spec |
| [`record-replay-proxy`](record-replay-proxy) | Proxy to an upstream and record traffic to a replayable file |
| [`validation-proxy`](validation-proxy) | Proxy to an upstream and validate requests/responses against an OpenAPI spec |
| [`chaos-proxy`](chaos-proxy) | Proxy to an upstream while injecting latency and intermittent errors |

## Configuration examples

Minimal permutations showing individual configuration mechanisms.

| Directory | What it shows |
|-----------|---------------|
| `docker_compose_server_port_by_command` | Setting the server port via a CLI flag |
| `docker_compose_server_port_by_environment_variable_long_name` | Setting the server port via `MOCKSERVER_SERVER_PORT` |
| `docker_compose_server_port_by_environment_variable_short_name` | Setting the server port via `SERVER_PORT` |
| `docker_compose_with_server_port_from_default_properties_file` | Loading config from the default `mockserver.properties` location |
| `docker_compose_with_server_port_from_custom_properties_file` | Loading config from a custom properties file path |
| `docker_compose_without_server_port` | Using the default server port |
| `docker_compose_with_expectation_initialiser` | Pre-loading expectations from a mounted JSON file |
| `docker_compose_with_persisted_expectations` | Persisting expectations to a host-mounted file |
| `docker_compose_remote_host_and_port_by_environment_variable` | Configuring MockServer as a proxy to a remote host via env vars |
| `docker_compose_forward_with_override` | Forwarding a request to an upstream and overriding the response |
| `docker_compose_with_mtls` | Enforcing mutual TLS with a self-signed CA and client certificates |
