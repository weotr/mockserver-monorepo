# Outage Window with Controllable Clock

## What it demonstrates

Models a **self-healing outage**: chaos is active only during a time window, then the service recovers automatically. The window is driven by the **controllable clock** (`PUT /mockserver/clock`), enabling fully deterministic testing without real-time waits.

Key fields:
- `outageAfterMillis` -- chaos activates this many milliseconds after the expectation's first match (0 = immediately)
- `outageDurationMillis` -- chaos stays active for this long, then self-heals (omit for an unbounded outage)
- `errorStatus` / `errorProbability` -- the fault injected during the window

Clock control API:
- `PUT /mockserver/clock {"action": "freeze", "instant": "2025-01-01T00:00:00Z"}` -- freeze the clock at a specific ISO-8601 instant
- `PUT /mockserver/clock {"action": "advance", "durationMillis": 5000}` -- advance the frozen clock by 5 seconds
- `PUT /mockserver/clock {"action": "reset"}` -- return to real wall-clock time
- `GET /mockserver/clock` -- read the current clock state

## Prerequisites

- A running MockServer instance
- `curl`

## Run

```bash
./run.sh
```

## Expected output

```
==> Step 3: First request at t=0 (before outage window, expect 200)...
{"products": ["item-1"]} => HTTP 200

==> Step 5: Request during outage (expect 503)...
{"error":"chaos_injected", ...} => HTTP 503

==> Step 7: Request after self-heal (expect 200 again)...
{"products": ["item-1"]} => HTTP 200
```
