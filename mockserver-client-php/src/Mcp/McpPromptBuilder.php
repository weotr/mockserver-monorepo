<?php

declare(strict_types=1);

namespace MockServer\Mcp;

/**
 * Fluent sub-builder for an MCP prompt. Terminated with {@see and()} which
 * pushes the prompt onto the parent and enables the prompts capability.
 */
class McpPromptBuilder
{
    private McpMockBuilder $parent;
    public string $name;
    public ?string $description = null;
    /** @var list<array{name: string, description: string|null, required: bool}> */
    public array $arguments = [];
    /** @var list<array{role: string, text: string}> */
    public array $messages = [];

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

    public function withArgument(string $name, ?string $description, bool $required): self
    {
        $this->arguments[] = ['name' => $name, 'description' => $description, 'required' => $required];
        return $this;
    }

    public function respondingWith(string $role, string $textContent): self
    {
        $this->messages[] = ['role' => $role, 'text' => $textContent];
        return $this;
    }

    public function and(): McpMockBuilder
    {
        return $this->parent->addPrompt($this);
    }
}
