# Record &amp; replay a proxy

One command to stand up a recording proxy: MockServer forwards traffic to an upstream service and writes every interaction out as a replayable expectation file.

```bash
docker compose up
```

This starts two containers — a recording proxy on `1080` and a stand-in `upstream` on `1090` (replace `upstream` with your real dependency). Drive some traffic through the proxy:

```bash
curl http://localhost:1080/api/greeting
# -> {"message": "hello from the real upstream"}
```

The proxy forwards the request to the upstream, returns the real response, and records the interaction to `recorded/recorded.json` on the host. Stop the stack and inspect it:

```bash
docker compose down
cat recorded/recorded.json
```

## What it shows

- `MOCKSERVER_PROXY_REMOTE_HOST` / `MOCKSERVER_PROXY_REMOTE_PORT` put MockServer in port-forwarding (proxy) mode — unmatched requests go to the upstream.
- `MOCKSERVER_PERSIST_RECORDED_EXPECTATIONS=true` plus `MOCKSERVER_PERSISTED_RECORDED_EXPECTATIONS_PATH` write recorded interactions to a host-mounted file.

## Replay

Feed the recorded file back as initial expectations to replay the captured responses with no upstream at all — e.g. mount it and set `MOCKSERVER_INITIALIZATION_JSON_PATH=/recorded/recorded.json` (see the [`docker_compose_with_expectation_initialiser`](../docker_compose_with_expectation_initialiser) recipe), or from the CLI: `mockserver run --init recorded.json`.
