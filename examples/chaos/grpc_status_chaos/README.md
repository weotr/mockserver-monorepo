# gRPC Status Chaos

## What it demonstrates

Injects **gRPC-layer faults** on matched gRPC method calls, including error status codes, latency, custom trailers, and rate-limiting quotas. The gRPC chaos API (`PUT/GET/PATCH /mockserver/grpcChaos`) is keyed by gRPC service name, with an empty-string key (`""`) serving as a default profile for all services.

Key fields:
- `errorStatusCode` -- gRPC status code name (e.g. `UNAVAILABLE`, `DEADLINE_EXCEEDED`, `RESOURCE_EXHAUSTED`, `INTERNAL`, `ABORTED`)
- `errorMessage` -- the `grpc-message` text returned with the error
- `errorProbability` -- probability of injecting the error (0.0-1.0)
- `latencyMs` -- delay before the response in milliseconds
- `customTrailers` -- arbitrary trailer key/value pairs injected on fault responses (keys must be lowercase HTTP/2 header tokens)
- `quotaName` / `quotaLimit` / `quotaWindowMillis` -- deterministic rate limiting (exceeded quota returns `RESOURCE_EXHAUSTED`)
- `succeedFirst` / `failRequestCount` -- count-based chaos window
- `omitGrpcStatus` -- omit the `grpc-status` trailer entirely (protocol violation)
- `corruptGrpcStatus` -- send a non-numeric `grpc-status` value (protocol violation)
- `abortAfterMessages` -- abort with `ABORTED` status when client-streaming message count >= threshold
- `seed` -- fixed seed for reproducible error draws

## Prerequisites

- A running MockServer instance with gRPC support enabled
- `curl`

## Run

```bash
./run.sh
```

## Expected output

```
==> Registering gRPC chaos for service 'com.example.OrderService'...
{
  "status" : "registered",
  "service" : "com.example.OrderService"
}

==> Registering a default gRPC chaos profile (applies to ALL services)...
{
  "status" : "registered",
  "service" : ""
}

==> Checking active gRPC chaos profiles...
{
  "services" : {
    "com.example.orderservice" : {
      "errorStatusCode" : "UNAVAILABLE",
      "errorMessage" : "service is undergoing maintenance",
      "errorProbability" : 0.5,
      "latencyMs" : 100,
      "customTrailers" : {
        "x-retry-reason" : "chaos-test",
        "x-fault-id" : "grpc-001"
      }
    },
    "" : {
      "errorStatusCode" : "INTERNAL",
      "errorProbability" : 0.1,
      "seed" : 99
    }
  }
}
```
