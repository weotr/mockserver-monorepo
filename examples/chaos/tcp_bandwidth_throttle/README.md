# TCP Bandwidth Throttle

## What it demonstrates

Throttles inbound TCP bandwidth to a target host at **1024 bytes/sec**, simulating a severely constrained network link. The profile includes:
- A `ttlMillis` so it auto-expires after 60 seconds (dead-man's switch)
- A follow-up `PATCH` that adds data slicing without replacing the existing profile

Key fields:
- `bandwidthBytesPerSec` -- throttle to this many bytes per second (>= 1)
- `slicerChunkSize` -- fragment inbound data into chunks of this many bytes (>= 1)
- `ttlMillis` -- optional time-to-live; the profile auto-expires after this many milliseconds

The TTL acts as a safety net: if the test runner crashes before cleaning up, the chaos profile self-reverts. The `GET /mockserver/tcpChaos` response includes `ttlRemainingMillis` so operators can see the countdown.

## Prerequisites

- A running MockServer instance configured as a proxy
- `curl`

## Run

```bash
./run.sh
```

## Expected output

```
==> Registering TCP bandwidth throttle (1 KB/s, TTL 60s) for 'slow-api.example.com'...
{
  "status" : "registered",
  "host" : "slow-api.example.com",
  "ttlMillis" : 60000
}

==> Checking active TCP chaos profiles (note ttlRemainingMillis)...
{
  "hosts" : {
    "slow-api.example.com" : {
      "bandwidthBytesPerSec" : 1024
    }
  },
  "ttlRemainingMillis" : {
    "slow-api.example.com" : 59850
  }
}

==> Live-patching: also add data slicing (256-byte chunks)...
{
  "status" : "patched",
  "host" : "slow-api.example.com",
  "chaos" : {
    "bandwidthBytesPerSec" : 1024,
    "slicerChunkSize" : 256
  }
}
```
