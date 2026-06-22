<?php

declare(strict_types=1);

namespace MockServer\Mcp;

/**
 * Fluent sub-builder for an MCP tool. Terminated with {@see and()} which pushes
 * the tool onto the parent and enables the tools capability.
 */
class McpToolBuilder
{
    private McpMockBuilder $parent;
    public string $name;
    public ?string $description = null;
    public ?string $inputSchema = null;
    public ?string $responseContent = null;
    public bool $responseIsError = false;

    public function __construct(McpMockBuilder $parent, string $name)
    {
        $this->parent = $parent;
        $this->name = $name;
    }

    public function withDescription(string $description): self
    {
        $this->description = $description;
        return $this;
    }

    public function withInputSchema(string $jsonSchema): self
    {
        $this->inputSchema = $jsonSchema;
        return $this;
    }

    public function respondingWith(string $textContent, bool $isError = false): self
    {
        $this->responseContent = $textContent;
        $this->responseIsError = $isError;
        return $this;
    }

    public function and(): McpMockBuilder
    {
        return $this->parent->addTool($this);
    }
}
