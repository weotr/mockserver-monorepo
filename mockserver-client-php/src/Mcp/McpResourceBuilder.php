<?php

declare(strict_types=1);

namespace MockServer\Mcp;

/**
 * Fluent sub-builder for an MCP resource. Terminated with {@see and()} which
 * pushes the resource onto the parent and enables the resources capability.
 */
class McpResourceBuilder
{
    private McpMockBuilder $parent;
    public string $uri;
    public ?string $name = null;
    public ?string $description = null;
    public string $mimeType = 'application/json';
    public ?string $content = null;

    public function __construct(McpMockBuilder $parent, string $uri)
    {
        $this->parent = $parent;
        $this->uri = $uri;
    }

    public function withName(string $name): self
    {
        $this->name = $name;
        return $this;
    }

    public function withDescription(string $description): self
    {
        $this->description = $description;
        return $this;
    }

    public function withMimeType(string $mimeType): self
    {
        $this->mimeType = $mimeType;
        return $this;
    }

    public function withContent(string $content): self
    {
        $this->content = $content;
        return $this;
    }

    public function and(): McpMockBuilder
    {
        return $this->parent->addResource($this);
    }
}
