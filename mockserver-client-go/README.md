# MockServer Go Client

An idiomatic Go client for the [MockServer](https://www.mock-server.com) control-plane REST API.

Zero third-party dependencies — uses only the Go standard library (`net/http` + `encoding/json`).

## Installation

```bash
go get github.com/mock-server/mockserver-monorepo/mockserver-client-go
```

## Quick Start

```go
package main

import (
    "log"

    mockserver "github.com/mock-server/mockserver-monorepo/mockserver-client-go"
)

func main() {
    client := mockserver.New("localhost", 1080)

    // Create an expectation
    _, err := client.When(
        mockserver.Request().Method("GET").Path("/hello"),
    ).Respond(
        mockserver.Response().StatusCode(200).Body("world"),
    )
    if err != nil {
        log.Fatal(err)
    }

    // Verify the request was received at least once
    err = client.Verify(
        mockserver.Request().Path("/hello"),
        mockserver.AtLeast(1),
    )
    if err != nil {
        log.Fatal(err)
    }

    // Reset all expectations and logs
    if err := client.Reset(); err != nil {
        log.Fatal(err)
    }
}
```

## API

### Client Construction

```go
// By host and port
client := mockserver.New("localhost", 1080)

// From a full URL
client := mockserver.NewFromURL("http://mockserver.internal:1080")

// With options
client := mockserver.New("localhost", 1080,
    mockserver.WithContextPath("/myapp"),
    mockserver.WithTimeout(10 * time.Second),
)
```

### Creating Expectations

```go
// Simple response
client.When(
    mockserver.Request().Method("GET").Path("/api/users"),
).Respond(
    mockserver.Response().StatusCode(200).JSONBody(`[{"id":1}]`),
)

// With times and TTL
client.When(
    mockserver.Request().Method("POST").Path("/api/orders"),
    mockserver.WithTimes(mockserver.Once()),
    mockserver.WithTimeToLive(mockserver.TTL("SECONDS", 60)),
).Respond(
    mockserver.Response().StatusCode(201).WithDelay("MILLISECONDS", 100),
)

// Forward to another host
client.When(
    mockserver.Request().Path("/proxy/.*"),
).Forward(
    mockserver.Forward().Host("backend.local").Port(8080).Scheme("HTTP"),
)

// With expectation ID for deduplication
client.When(
    mockserver.Request().Path("/stable"),
).WithID("my-stable-exp").Respond(
    mockserver.Response().StatusCode(200),
)
```

### Verification

```go
// At least N times
client.Verify(mockserver.Request().Path("/hello"), mockserver.AtLeast(1))

// At most N times
client.Verify(mockserver.Request().Path("/hello"), mockserver.AtMost(5))

// Exactly N times
client.Verify(mockserver.Request().Path("/hello"), mockserver.ExactlyTimes(3))

// Between min and max
client.Verify(mockserver.Request().Path("/hello"), mockserver.Between(1, 5))

// Sequence verification
client.VerifySequence(
    mockserver.Request().Path("/login"),
    mockserver.Request().Path("/dashboard"),
)
```

### Retrieval

```go
// Active expectations
expectations, _ := client.RetrieveActiveExpectations(nil)

// Recorded requests (optionally filtered)
requests, _ := client.RetrieveRecordedRequests(mockserver.Request().Path("/api/.*"))

// Recorded expectations
recorded, _ := client.RetrieveRecordedExpectations(nil)

// Log messages
logs, _ := client.RetrieveLogMessages(nil)
```

### Control

```go
// Clear specific expectations
client.Clear(mockserver.Request().Path("/old"), mockserver.ClearExpectations)

// Clear by expectation ID
client.ClearByID("exp-123", mockserver.ClearAll)

// Reset everything
client.Reset()

// Check server status
status, _ := client.Status()
fmt.Println(status.Ports)

// Check if running
if client.IsRunning() { ... }

// Bind additional ports
ports, _ := client.Bind(1081, 1082)
```

## Build & Test

```bash
go test ./...
go vet ./...
```

Integration tests require `MOCKSERVER_URL` environment variable:

```bash
MOCKSERVER_URL=http://localhost:1080 go test ./... -v
```

## Requirements

- Go 1.21 or later
- No third-party dependencies
