# Connection Drop with Fixed Seed

## What it demonstrates

Injects **probabilistic TCP connection drops** on matched requests. The connection is closed without sending any HTTP response, simulating a network partition or server crash.

Key fields:
- `dropConnectionProbability` -- probability (0.0-1.0) that the connection is dropped
- `seed` -- when set, the random draw is deterministic: a fixed seed produces the same drop/no-drop decision on every request, making tests reproducible

Connection drops take priority over all other chaos faults (error injection, latency, body corruption, etc.).

## Prerequisites

- A running MockServer instance
- `curl`

## Run

```bash
./run.sh
```

## Expected output

With `seed=42` and `dropConnectionProbability=0.5`, the draw always produces the same result. Either every request gets a response or every request sees a connection reset:

```
==> Sending test request (with seed=42, the drop decision is the same every time)...
(connection was dropped)

==> Sending another request (same seed = same result)...
(connection was dropped)
```
