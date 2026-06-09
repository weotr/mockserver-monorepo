# Chaos proxy — inject latency and errors into a dependency

One command to put a misbehaving proxy in front of a service: MockServer forwards to the upstream but adds latency and intermittent `503`s, so you can test how your application copes with a flaky dependency.

```bash
docker compose up
```

This starts a chaos proxy on `1080` and a stand-in `upstream` on `1090`. Hammer the proxy and watch some calls succeed slowly and others fail:

```bash
for i in $(seq 1 10); do curl -s -o /dev/null -w "%{http_code} %{time_total}s\n" http://localhost:1080/api/orders; done
# -> a mix of 200s (with ~400ms latency) and 503s
```

## What it shows

- An `httpForward` expectation forwards every request to `host: upstream` / `port: 1090`.
- A `chaos` profile on the same expectation injects:
  - **latency** — Gaussian, mean 400ms, stddev 100ms.
  - **errors** — 30% of responses become `503` with a `Retry-After: 5` header (`errorProbability: 0.3`).

Tune the profile in `config/chaos-proxy.json` — for example drop connections (`dropConnectionProbability`), truncate bodies (`truncateBodyAtFraction`), or slow-dribble the response (`slowResponseChunkSize` / `slowResponseChunkDelay`). See the [`examples/chaos`](../../chaos) recipes for the full set of fault types, and the service-scoped `PUT /mockserver/serviceChaos` API for breaking a dependency live without redeploying.

Stop with `docker compose down`.
