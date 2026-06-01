# Named Quota / Rate Limit

## What it demonstrates

Implements a **deterministic, fixed-window rate limit** using MockServer's stateful quota feature. Unlike probabilistic error injection, the quota counts actual requests and deterministically rejects requests that exceed the limit.

Key fields:
- `quotaName` -- a shared counter key; expectations with the same `quotaName` share one rate-limit counter
- `quotaLimit` -- maximum requests allowed per window (>= 1)
- `quotaWindowMillis` -- fixed-window length in milliseconds (>= 1)
- `quotaErrorStatus` -- HTTP status returned when the quota is exceeded (default 429)
- `retryAfter` -- value for the `Retry-After` header on rejected requests

The quota gate fires after connection-drop checks but before probabilistic error injection and body/slow faults.

## Prerequisites

- A running MockServer instance
- `curl`

## Run

```bash
./run.sh
```

## Expected output

```
==> Sending 5 requests (first 3 should succeed, last 2 should be rate-limited)...
  Request 1: HTTP 200
  Request 2: HTTP 200
  Request 3: HTTP 200
  Request 4: HTTP 429
  Request 5: HTTP 429

==> Wait 11 seconds for the window to reset, then try again...
  Request after reset: HTTP 200
```
