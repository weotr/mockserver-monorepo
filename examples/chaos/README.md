# MockServer Chaos Engineering Examples

MockServer provides **multi-layer chaos injection** spanning TCP, HTTP, gRPC, and LLM protocols -- a key differentiator from single-layer tools. These examples demonstrate each chaos capability with runnable `curl` scripts that talk directly to the MockServer control-plane API.

## Prerequisites

- A running MockServer instance (e.g. `docker run -p 1080:1080 mockserver/mockserver`)
- `curl` installed
- Set `MOCKSERVER_URL` to the control-plane base URL (defaults to `http://localhost:1080` in every script)

## Scenarios

| Folder | Layer | What it demonstrates |
|--------|-------|---------------------|
| [http_latency_gaussian](http_latency_gaussian/) | HTTP | Inject Gaussian-distributed latency on a mocked response |
| [http_error_with_retry_after](http_error_with_retry_after/) | HTTP | Deterministic 503 error injection with a Retry-After header |
| [connection_drop_seeded](connection_drop_seeded/) | HTTP | Probabilistic TCP connection drops with a fixed seed for reproducibility |
| [gradual_degradation_ramp](gradual_degradation_ramp/) | HTTP | Error probability that ramps from 0% to 100% over a configurable duration |
| [outage_window_controllable_clock](outage_window_controllable_clock/) | HTTP | Time-based outage window using the controllable clock (`PUT /mockserver/clock`) |
| [named_quota_rate_limit](named_quota_rate_limit/) | HTTP | Fixed-window rate limiting with a named, shared quota counter |
| [tcp_reset_peer](tcp_reset_peer/) | TCP | Inject TCP RST (reset) on connections to a target host |
| [tcp_bandwidth_throttle](tcp_bandwidth_throttle/) | TCP | Throttle bandwidth to a target host at the TCP layer |
| [grpc_status_chaos](grpc_status_chaos/) | gRPC | Inject gRPC error statuses with latency and custom trailers |
| [llm_midstream_truncation](llm_midstream_truncation/) | LLM | Truncate an LLM streaming (SSE) response mid-stream |
| [service_scoped_profile_with_live_patch](service_scoped_profile_with_live_patch/) | HTTP (service-scoped) | Register a service-level chaos profile and live-patch it with PATCH |

## API Endpoint Summary

| Endpoint | Methods | Layer |
|----------|---------|-------|
| `PUT /mockserver/expectation` | Expectation-level chaos via the `chaos` field | HTTP, LLM |
| `PUT/GET/PATCH /mockserver/serviceChaos` | Service-scoped HTTP chaos by host | HTTP |
| `PUT/GET/PATCH /mockserver/tcpChaos` | TCP-layer chaos by host | TCP |
| `PUT/GET/PATCH /mockserver/grpcChaos` | gRPC chaos by service name | gRPC |
| `PUT/GET /mockserver/clock` | Controllable clock (freeze, advance, reset) | Cross-cutting |
