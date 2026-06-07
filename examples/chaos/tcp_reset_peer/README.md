# TCP Reset Peer

## What it demonstrates

Injects a **TCP RST (connection reset)** at the transport layer for all connections to a target host. This operates before HTTP decoding, making the upstream appear completely unreachable with an immediate connection reset.

The TCP chaos API (`PUT /mockserver/tcpChaos`) supports eight Toxiproxy-style fault types:
- `resetPeer` -- send TCP RST and close immediately
- `down` -- silently drop all inbound data (service appears down)
- `latencyMs` -- delay all inbound data by N milliseconds
- `bandwidthBytesPerSec` -- throttle to N bytes/sec
- `slowClose` -- delay the TCP FIN by 2 seconds
- `timeout` -- never send FIN; connection hangs on close
- `slicerChunkSize` -- fragment inbound data into chunks of N bytes
- `limitDataBytes` -- close connection after N bytes received

Multiple faults can be combined on a single profile.

## Prerequisites

- A running MockServer instance configured as a **proxy** (forward/proxy mode) so it handles connections to upstream hosts
- `curl`

## Run

```bash
./run.sh
```

## Expected output

```
==> Registering TCP RST chaos for host 'upstream-service.example.com'...
{
  "status" : "registered",
  "host" : "upstream-service.example.com"
}

==> Checking active TCP chaos profiles...
{
  "hosts" : {
    "upstream-service.example.com" : {
      "resetPeer" : true
    }
  }
}
```
