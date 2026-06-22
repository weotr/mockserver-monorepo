package mockserver

import (
	"encoding/json"
	"fmt"
)

// OpenAPIExpectation registers expectations generated from an OpenAPI (Swagger)
// specification. It is sent to the MockServer "PUT /mockserver/openapi" endpoint.
//
// SpecURLOrPayload is either a URL/file path pointing at the spec, or the spec
// document itself as a string (JSON or YAML). OperationsAndResponses optionally
// maps OpenAPI operationId values to the example response name to use for that
// operation; when nil, MockServer generates a response for every operation.
//
// Wire keys (specUrlOrPayload, operationsAndResponses) match the Java, Python,
// Ruby and Node clients.
type OpenAPIExpectation struct {
	SpecURLOrPayload       string            `json:"specUrlOrPayload,omitempty"`
	OperationsAndResponses map[string]string `json:"operationsAndResponses,omitempty"`
}

// OpenAPIBuilder provides a fluent API for building an OpenAPIExpectation.
type OpenAPIBuilder struct {
	expectation OpenAPIExpectation
}

// OpenAPI creates a new OpenAPIBuilder from a spec URL, file path, or inline
// spec document (JSON or YAML).
func OpenAPI(specURLOrPayload string) *OpenAPIBuilder {
	return &OpenAPIBuilder{expectation: OpenAPIExpectation{SpecURLOrPayload: specURLOrPayload}}
}

// OperationResponse maps an OpenAPI operationId to the example response name to
// use for that operation. It may be called multiple times.
func (b *OpenAPIBuilder) OperationResponse(operationID, response string) *OpenAPIBuilder {
	if b.expectation.OperationsAndResponses == nil {
		b.expectation.OperationsAndResponses = make(map[string]string)
	}
	b.expectation.OperationsAndResponses[operationID] = response
	return b
}

// Build returns the constructed OpenAPIExpectation.
func (b *OpenAPIBuilder) Build() OpenAPIExpectation {
	return b.expectation
}

// UpsertOpenAPI registers expectations generated from an OpenAPI specification.
func (c *Client) UpsertOpenAPI(b *OpenAPIBuilder) error {
	expectation := b.Build()
	body, err := json.Marshal(expectation)
	if err != nil {
		return fmt.Errorf("mockserver: marshal openapi expectation: %w", err)
	}

	respBody, statusCode, err := c.doRequest("PUT", "/mockserver/openapi", body, nil)
	if err != nil {
		return err
	}

	if statusCode == 400 || statusCode == 406 {
		return fmt.Errorf("mockserver: invalid openapi expectation (status %d): %s", statusCode, string(respBody))
	}
	if statusCode >= 400 {
		return fmt.Errorf("mockserver: create openapi expectation failed (status %d): %s", statusCode, string(respBody))
	}
	return nil
}
