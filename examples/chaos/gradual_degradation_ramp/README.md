# Gradual Degradation Ramp

## What it demonstrates

Models a **dependency that slowly degrades** over time. The `degradationRampMillis` field causes `errorProbability` and `dropConnectionProbability` to ramp linearly from `0.0` at the expectation's first match up to their configured values once the ramp duration has elapsed.

This is useful for testing:
- Alerting systems that detect gradual SLO burn
- Circuit breakers that trip at a threshold error rate
- Retry budgets under worsening conditions

Key fields:
- `errorStatus` -- the HTTP status to inject when the error fires
- `errorProbability` -- the target probability at the end of the ramp (1.0 = 100%)
- `degradationRampMillis` -- duration in milliseconds over which the probability ramps from 0 to the configured value (minimum 1)

The ramp is measured with the controllable clock (`PUT /mockserver/clock`), so it can be driven deterministically in tests by freezing and advancing the clock.

## Prerequisites

- A running MockServer instance
- `curl`

## Run

```bash
./run.sh
```

## Expected output

```
==> Creating expectation with gradual degradation ramp (60s)...
{ ... }

==> Request at t=0 (error probability ~0%, expect 200)...
{"orders": []} => HTTP 200

==> Waiting 10 seconds...
==> Request at t~10s (error probability ~16%, may still succeed)...
{"orders": []} => HTTP 200
```

Over time, increasingly many requests return 500 until the ramp completes at 60s, after which every request fails.
