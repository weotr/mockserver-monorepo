// Package mockserver provides a Testcontainers module for starting a MockServer container.
//
// MockServer is an open-source HTTP(S) mock server and proxy for testing. This module
// starts the official mockserver/mockserver Docker image, waits for readiness, and
// exposes helpers to obtain the container's URL and mapped port.
//
// Usage:
//
//	ctx := context.Background()
//	ctr, err := mockserver.Run(ctx, "mockserver/mockserver:mockserver-7.0.0")
//	if err != nil {
//	    log.Fatal(err)
//	}
//	defer ctr.Terminate(ctx)
//
//	url, err := ctr.URL(ctx)
//	// url is "http://localhost:<mapped-port>"
package mockserver

import (
	"context"
	"fmt"

	"github.com/testcontainers/testcontainers-go"
	"github.com/testcontainers/testcontainers-go/wait"
)

const (
	// DefaultPort is the default port MockServer listens on inside the container.
	// MockServer serves HTTP, HTTPS, SOCKS, and HTTP CONNECT on a single unified port.
	DefaultPort = "1080/tcp"

	// DefaultImage is the default Docker image used when Run is called without an explicit image.
	// The tag corresponds to the latest released MockServer version.
	DefaultImage = "mockserver/mockserver:mockserver-7.0.0"
)

// MockServerContainer wraps a testcontainers.Container with MockServer-specific helpers.
type MockServerContainer struct {
	testcontainers.Container
}

// URL returns the HTTP base URL for the running MockServer container (e.g. "http://localhost:32769").
func (c *MockServerContainer) URL(ctx context.Context) (string, error) {
	host, err := c.Host(ctx)
	if err != nil {
		return "", fmt.Errorf("mockserver: failed to get host: %w", err)
	}
	port, err := c.MappedPort(ctx, "1080/tcp")
	if err != nil {
		return "", fmt.Errorf("mockserver: failed to get mapped port: %w", err)
	}
	return fmt.Sprintf("http://%s:%s", host, port.Port()), nil
}

// ServerPort returns the mapped host port for the MockServer container port 1080.
func (c *MockServerContainer) ServerPort(ctx context.Context) (int, error) {
	port, err := c.MappedPort(ctx, "1080/tcp")
	if err != nil {
		return 0, fmt.Errorf("mockserver: failed to get mapped port: %w", err)
	}
	return int(port.Num()), nil
}

// Run creates and starts a MockServer container. The image parameter specifies the Docker
// image to use (e.g. "mockserver/mockserver:mockserver-7.0.0"). Pass testcontainers
// CustomizeRequestOption values to configure the container request further.
//
// The container waits for an HTTP 200 response on PUT /mockserver/status before
// returning, ensuring MockServer is fully ready to receive expectations.
func Run(ctx context.Context, img string, opts ...testcontainers.ContainerCustomizer) (*MockServerContainer, error) {
	req := testcontainers.ContainerRequest{
		Image:        img,
		ExposedPorts: []string{DefaultPort},
		WaitingFor: wait.ForHTTP("/mockserver/status").
			WithMethod("PUT").
			WithPort("1080/tcp").
			WithStatusCodeMatcher(func(status int) bool {
				return status == 200
			}),
	}

	genericReq := testcontainers.GenericContainerRequest{
		ContainerRequest: req,
		Started:          true,
	}

	for _, opt := range opts {
		if err := opt.Customize(&genericReq); err != nil {
			return nil, fmt.Errorf("mockserver: failed to apply option: %w", err)
		}
	}

	container, err := testcontainers.GenericContainer(ctx, genericReq)
	if err != nil {
		return nil, fmt.Errorf("mockserver: failed to start container: %w", err)
	}

	return &MockServerContainer{Container: container}, nil
}
