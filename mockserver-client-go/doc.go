// Package mockserver provides an idiomatic Go client for the MockServer
// control-plane REST API.
//
// It supports creating expectations (When/Respond), verifying received requests,
// retrieving recorded data, and managing server state (Clear, Reset).
//
// The client uses only the Go standard library (net/http + encoding/json) and
// has zero third-party dependencies.
//
// Basic usage:
//
//	client := mockserver.New("localhost", 1080)
//	client.When(
//	    mockserver.Request().Method("GET").Path("/hello"),
//	).Respond(
//	    mockserver.Response().StatusCode(200).Body("world"),
//	)
//
//	err := client.Verify(mockserver.Request().Path("/hello"), mockserver.AtLeast(1))
//	if err != nil {
//	    log.Fatal(err)
//	}
//
//	client.Reset()
package mockserver
