# Validate proxied traffic against an OpenAPI spec

One command to put a contract-checking proxy in front of a service: MockServer forwards every request to the upstream and validates both the request and the response against a mounted OpenAPI spec, logging any contract violations.

```bash
docker compose up
```

This starts a validating proxy on `1080` and a stand-in `upstream` on `1090`. Send a request through the proxy:

```bash
curl http://localhost:1080/api/greeting
# -> {"message": "hello"}
```

The request is forwarded to the upstream and the response is returned. If either the request or the response breaks the spec, MockServer logs an `OpenAPI ... validation failed` warning (visible in `docker compose logs mockserver`).

## What it shows

- An `httpForwardValidateAction` expectation forwards to `host: upstream` / `port: 1090` and validates against `specUrlOrPayload: /config/openapi.yaml`.
- `validateRequest` and `validateResponse` are both on.
- `validationMode: LOG_ONLY` forwards traffic and only **logs** violations. Switch to `STRICT` in `config/validation-proxy.json` to instead reject invalid requests with `400` and invalid responses with `502`.

To see validation fire, change the upstream response in `config/upstream.json` to break the contract (for example return a non-JSON body or drop the required `message` field) and re-run.

Stop with `docker compose down`.
