# HTTP Error Injection with Retry-After

## What it demonstrates

Injects a **deterministic 503 Service Unavailable** error on every matched request, with a `Retry-After: 30` header. This is useful for testing client retry logic and circuit breakers.

Key fields:
- `errorStatus` -- the HTTP status code to return (100-599)
- `retryAfter` -- value for the `Retry-After` response header (string, e.g. `"30"` for 30 seconds)
- `errorProbability` -- probability of injecting the error (0.0-1.0); `1.0` = every request fails

When `errorProbability` is `1.0` (or omitted, which defaults to always), the behaviour is fully deterministic. Fractional values (e.g. `0.3`) make 30% of requests fail randomly.

## Prerequisites

- A running MockServer instance
- `curl`

## Run

```bash
./run.sh
```

## Expected output

```
==> Creating expectation with 503 error + Retry-After chaos...
{ ... }

==> Sending test request (expect 503 with Retry-After: 30)...
HTTP/1.1 503 Service Unavailable
retry-after: 30
content-type: application/json; charset=utf-8
...
```
