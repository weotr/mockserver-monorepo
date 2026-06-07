# Service-Scoped Chaos Profile with Live Patch

## What it demonstrates

Registers a **service-level chaos profile** that applies to all forwarded/proxied requests to a given host -- without needing a `chaos` block on every individual expectation. Then demonstrates **live-patching** the profile with `PATCH /mockserver/serviceChaos` (JSON Merge Patch semantics: only supplied fields are updated, all others are preserved).

This is the "break service X" use case: a single API call makes an upstream dependency start misbehaving across all requests.

### Full lifecycle shown

1. **PUT** -- register the profile with latency, error injection, slow dribble, and a TTL
2. **GET** -- read all active profiles and TTL countdowns
3. **PATCH** -- increase error rate and add body truncation without replacing the existing profile
4. **GET** -- verify the merged result
5. **PUT with `remove`** -- remove the profile for one host
6. **GET** -- verify removal

### API Reference

| Method | Endpoint | Purpose |
|--------|----------|---------|
| `PUT` | `/mockserver/serviceChaos` | Register, replace, or remove a profile (or `{"clear": true}` to clear all) |
| `GET` | `/mockserver/serviceChaos` | List all active profiles and TTL countdowns |
| `PATCH` | `/mockserver/serviceChaos` | Merge-patch a profile (only supplied fields are updated) |

### Request body (PUT -- register)

```json
{
  "host": "payments.internal:8443",
  "chaos": { ... HttpChaosProfile fields ... },
  "ttlMillis": 120000
}
```

### Request body (PATCH)

```json
{
  "host": "payments.internal:8443",
  "chaos": {
    "errorProbability": 0.8
  }
}
```

Only `errorProbability` is updated; all other fields are preserved from the existing profile.

### Key fields

- `host` -- the upstream host to target (case-insensitive, port suffix is stripped for matching)
- `chaos` -- an `HttpChaosProfile` object with any combination of fault fields
- `ttlMillis` -- optional time-to-live in milliseconds (dead-man's switch; auto-expires if the orchestrator crashes)
- `remove` -- set to `true` to remove the profile for a host
- `clear` -- set to `true` to clear all service-scoped chaos

## Prerequisites

- A running MockServer instance (typically in proxy/forward mode for service-scoped chaos to take effect)
- `curl`

## Run

```bash
./run.sh
```

## Expected output

```
==> Step 1: Register service chaos for 'payments.internal:8443'...
{
  "status" : "registered",
  "host" : "payments.internal:8443",
  "ttlMillis" : 120000
}

==> Step 2: Read active service chaos profiles...
{
  "services" : {
    "payments.internal" : {
      "errorStatus" : 500,
      "errorProbability" : 0.3,
      "latency" : { ... },
      "slowResponseChunkSize" : 512,
      "slowResponseChunkDelay" : { ... }
    }
  },
  "ttlRemainingMillis" : {
    "payments.internal" : 119850
  }
}

==> Step 3: Live-patch -- increase error rate to 80% and add body truncation...
{
  "status" : "patched",
  "host" : "payments.internal:8443",
  "chaos" : {
    "errorStatus" : 500,
    "errorProbability" : 0.8,
    "truncateBodyAtFraction" : 0.5,
    "latency" : { ... },
    "slowResponseChunkSize" : 512,
    "slowResponseChunkDelay" : { ... }
  }
}

==> Step 5: Remove the profile for this specific host...
{
  "status" : "removed",
  "host" : "payments.internal:8443"
}

==> Step 6: Verify it was removed...
{
  "services" : { }
}
```
