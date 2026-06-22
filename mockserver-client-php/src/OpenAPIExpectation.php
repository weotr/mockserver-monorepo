<?php

declare(strict_types=1);

namespace MockServer;

/**
 * Definition for importing expectations from an OpenAPI / Swagger specification.
 *
 * Produces the JSON body for {@code PUT /mockserver/openapi}. Wire keys:
 * specUrlOrPayload, operationsAndResponses.
 *
 * The {@code specUrlOrPayload} may be a URL, a classpath/file path, or the
 * inline spec itself (JSON or YAML). {@code operationsAndResponses} optionally
 * maps operationId to the response example to use for that operation.
 *
 * @example From a URL, all operations
 *   OpenAPIExpectation::openAPI('https://example.com/openapi.json');
 *
 * @example Specific operations to specific response examples
 *   OpenAPIExpectation::openAPI('org/mockserver/openapi/openapi_petstore.json', [
 *       'listPets'   => '200',
 *       'createPets' => '201',
 *   ]);
 */
class OpenAPIExpectation implements \JsonSerializable
{
    private ?string $specUrlOrPayload = null;
    /** @var array<string, string>|null */
    private ?array $operationsAndResponses = null;

    /**
     * @param string $specUrlOrPayload URL, file path, or inline OpenAPI spec (JSON/YAML)
     * @param array<string, string>|null $operationsAndResponses Map of operationId => response key
     */
    public static function openAPI(string $specUrlOrPayload, ?array $operationsAndResponses = null): self
    {
        $expectation = new self();
        $expectation->specUrlOrPayload = $specUrlOrPayload;
        $expectation->operationsAndResponses = $operationsAndResponses;
        return $expectation;
    }

    public function specUrlOrPayload(string $specUrlOrPayload): self
    {
        $this->specUrlOrPayload = $specUrlOrPayload;
        return $this;
    }

    /**
     * @param array<string, string> $operationsAndResponses Map of operationId => response key
     */
    public function operationsAndResponses(array $operationsAndResponses): self
    {
        $this->operationsAndResponses = $operationsAndResponses;
        return $this;
    }

    /**
     * @return array<string, mixed>
     */
    public function jsonSerialize(): array
    {
        return $this->toArray();
    }

    /**
     * @return array<string, mixed>
     */
    public function toArray(): array
    {
        $data = [];
        if ($this->specUrlOrPayload !== null) {
            $data['specUrlOrPayload'] = $this->specUrlOrPayload;
        }
        if ($this->operationsAndResponses !== null) {
            $data['operationsAndResponses'] = $this->operationsAndResponses;
        }
        return $data;
    }
}
