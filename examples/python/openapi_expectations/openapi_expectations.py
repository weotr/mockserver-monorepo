#!/usr/bin/env python3
"""MockServer Python client -- OpenAPI expectation examples.

Each function creates expectations automatically from an OpenAPI specification.
Run this script against a MockServer on localhost:1080.
"""

from mockserver import (
    MockServerClient,
    OpenAPIExpectation,
)


MOCK_HOST = "localhost"
MOCK_PORT = 1080

PETSTORE_SPEC_URL = (
    "https://raw.githubusercontent.com/mock-server/mockserver-monorepo/master/"
    "mockserver/mockserver-integration-testing/src/main/resources/"
    "org/mockserver/openapi/openapi_petstore_example.json"
)


def openapi_from_url(client: MockServerClient) -> None:
    """Load an OpenAPI spec from a URL and generate expectations for all operations."""
    client.open_api_expectation(
        OpenAPIExpectation(spec_url_or_payload=PETSTORE_SPEC_URL)
    )
    print("expectation created: OpenAPI from URL (all operations)")


def openapi_with_specific_operations(client: MockServerClient) -> None:
    """Load an OpenAPI spec and map specific operations to response codes."""
    client.open_api_expectation(
        OpenAPIExpectation(
            spec_url_or_payload=PETSTORE_SPEC_URL,
            operations_and_responses={
                "showPetById": "200",
                "createPets": "500",
            },
        )
    )
    print("expectation created: OpenAPI with specific operations")


def openapi_from_inline_yaml(client: MockServerClient) -> None:
    """Pass an inline YAML OpenAPI spec."""
    yaml_spec = """\
---
openapi: 3.0.0
info:
  version: 1.0.0
  title: Inline Petstore
paths:
  /pets:
    get:
      summary: List all pets
      operationId: listPets
      responses:
        '200':
          description: A list of pets
          content:
            application/json:
              schema:
                type: array
                items:
                  type: object
                  properties:
                    id:
                      type: integer
                    name:
                      type: string
"""
    client.open_api_expectation(
        OpenAPIExpectation(spec_url_or_payload=yaml_spec)
    )
    print("expectation created: OpenAPI from inline YAML")


def main() -> None:
    with MockServerClient(MOCK_HOST, MOCK_PORT) as client:
        client.reset()

        openapi_from_url(client)
        openapi_with_specific_operations(client)
        openapi_from_inline_yaml(client)

        print("\nAll OpenAPI expectations created successfully.")


if __name__ == "__main__":
    main()
