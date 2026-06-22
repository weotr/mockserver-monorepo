package mockserver

import (
	"encoding/json"
	"fmt"
)

// GrpcMethod describes a single method of a gRPC service registered from an
// uploaded protobuf descriptor set. The JSON keys (name, inputType, outputType,
// clientStreaming, serverStreaming) match the Java, Python and Node clients and
// the MockServer "PUT /mockserver/grpc/services" response.
type GrpcMethod struct {
	Name            string `json:"name"`
	InputType       string `json:"inputType"`
	OutputType      string `json:"outputType"`
	ClientStreaming bool   `json:"clientStreaming"`
	ServerStreaming bool   `json:"serverStreaming"`
}

// GrpcService describes a gRPC service registered from an uploaded protobuf
// descriptor set, including its fully-qualified name and methods.
type GrpcService struct {
	Name    string       `json:"name"`
	Methods []GrpcMethod `json:"methods"`
}

// UploadGrpcDescriptor uploads a compiled protobuf descriptor set so that gRPC
// requests can be matched and mocked.
//
// descriptorBytes must be the raw bytes of a FileDescriptorSet (e.g. the output
// of "protoc --descriptor_set_out=... --include_imports"). The bytes are sent
// verbatim — NOT base64-encoded — as an application/octet-stream body to the
// "PUT /mockserver/grpc/descriptors" endpoint, which returns 201 Created.
func (c *Client) UploadGrpcDescriptor(descriptorBytes []byte) error {
	if len(descriptorBytes) == 0 {
		return fmt.Errorf("mockserver: descriptor set bytes must not be empty")
	}

	respBody, statusCode, err := c.doRequestWithContentType(
		"PUT", "/mockserver/grpc/descriptors", descriptorBytes, nil, "application/octet-stream",
	)
	if err != nil {
		return err
	}
	if statusCode >= 400 {
		return fmt.Errorf("mockserver: upload gRPC descriptor failed (status %d): %s", statusCode, string(respBody))
	}
	return nil
}

// RetrieveGrpcServices retrieves the gRPC services registered from uploaded
// descriptor sets. It calls "PUT /mockserver/grpc/services" (no body) and
// returns the parsed service list.
func (c *Client) RetrieveGrpcServices() ([]GrpcService, error) {
	respBody, statusCode, err := c.doRequest("PUT", "/mockserver/grpc/services", nil, nil)
	if err != nil {
		return nil, err
	}
	if statusCode >= 400 {
		return nil, fmt.Errorf("mockserver: retrieve gRPC services failed (status %d): %s", statusCode, string(respBody))
	}
	if len(respBody) == 0 {
		return nil, nil
	}

	var services []GrpcService
	if err := json.Unmarshal(respBody, &services); err != nil {
		return nil, fmt.Errorf("mockserver: unmarshal gRPC services: %w", err)
	}
	return services, nil
}

// ClearGrpcDescriptors clears all uploaded gRPC descriptor sets and the services
// registered from them. It calls "PUT /mockserver/grpc/clear" (no body).
func (c *Client) ClearGrpcDescriptors() error {
	respBody, statusCode, err := c.doRequest("PUT", "/mockserver/grpc/clear", nil, nil)
	if err != nil {
		return err
	}
	if statusCode >= 400 {
		return fmt.Errorf("mockserver: clear gRPC descriptors failed (status %d): %s", statusCode, string(respBody))
	}
	return nil
}
