# HTTP Latency with Gaussian Distribution

## What it demonstrates

Injects latency on a mocked HTTP response using a **Gaussian (normal) distribution**. Each response is delayed by a value sampled from a distribution with `mean=200ms` and `stdDev=50ms`, so most responses arrive around 200ms but with realistic jitter.

MockServer supports three latency distribution types:
- `GAUSSIAN` -- parameterised by `mean` and `stdDev`
- `LOG_NORMAL` -- parameterised by `median` and `p99`
- `UNIFORM` -- parameterised by `min` and `max`

The `chaos.latency` field is a standard MockServer `Delay` object with an optional `distribution` sub-object.

## Prerequisites

- A running MockServer instance
- `curl` and optionally `python3` (for pretty-printing JSON)

## Run

```bash
export MOCKSERVER_URL=http://localhost:1080  # optional, this is the default
./run.sh
```

## Expected output

The expectation is created successfully. Each subsequent request to `GET /api/inventory` returns HTTP 200 after approximately 200ms (plus or minus jitter). Timing varies between requests because the distribution is sampled independently.

```
==> Creating expectation with Gaussian latency chaos...
{ ... }

==> Sending test request (expect ~200ms latency with Gaussian jitter)...
HTTP 200 in 0.215s

==> Sending another request (latency varies each time)...
HTTP 200 in 0.187s
```
