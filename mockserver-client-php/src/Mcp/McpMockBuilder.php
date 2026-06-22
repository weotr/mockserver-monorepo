<?php

declare(strict_types=1);

namespace MockServer\Mcp;

use MockServer\Expectation;
use MockServer\MockServerClient;

/**
 * Fluent builder for mocking an MCP (Model Context Protocol) server speaking
 * JSON-RPC 2.0 over the Streamable HTTP transport
 * (mirrors org.mockserver.client.McpMockBuilder).
 *
 * Produces a list of HTTP expectations whose wire JSON is identical to the
 * Java/Node/Python builders so all clients drive the same server behaviour.
 *
 * @example
 *   McpMockBuilder::mcpMock('/mcp')
 *       ->withServerName('MyServer')
 *       ->withTool('get_weather')
 *           ->withDescription('Get the weather for a city')
 *           ->withInputSchema('{"type":"object","properties":{"city":{"type":"string"}}}')
 *           ->respondingWith('sunny')
 *       ->and()
 *       ->applyTo($client);
 */
class McpMockBuilder
{
    private string $path;
    private string $serverName = 'MockMCPServer';
    private string $serverVersion = '1.0.0';
    private string $protocolVersion = '2025-03-26';
    private bool $toolsCapability = false;
    private bool $resourcesCapability = false;
    private bool $promptsCapability = false;
    /** @var list<McpToolBuilder> */
    private array $tools = [];
    /** @var list<McpResourceBuilder> */
    private array $resources = [];
    /** @var list<McpPromptBuilder> */
    private array $prompts = [];

    public function __construct(string $path = '/mcp')
    {
        $this->path = $path;
    }

    /**
     * Entry point mirroring {@code McpMockBuilder.mcpMock(path)}.
     */
    public static function mcpMock(string $path = '/mcp'): self
    {
        return new self($path);
    }

    // --- top-level configuration -------------------------------------------

    public function withServerName(string $name): self
    {
        $this->serverName = $name;
        return $this;
    }

    public function withServerVersion(string $version): self
    {
        $this->serverVersion = $version;
        return $this;
    }

    public function withProtocolVersion(string $version): self
    {
        $this->protocolVersion = $version;
        return $this;
    }

    public function withToolsCapability(): self
    {
        $this->toolsCapability = true;
        return $this;
    }

    public function withResourcesCapability(): self
    {
        $this->resourcesCapability = true;
        return $this;
    }

    public function withPromptsCapability(): self
    {
        $this->promptsCapability = true;
        return $this;
    }

    public function withTool(string $name): McpToolBuilder
    {
        return new McpToolBuilder($this, $name);
    }

    public function withResource(string $uri): McpResourceBuilder
    {
        return new McpResourceBuilder($this, $uri);
    }

    public function withPrompt(string $name): McpPromptBuilder
    {
        return new McpPromptBuilder($this, $name);
    }

    /**
     * @internal Called by {@see McpToolBuilder::and()}.
     */
    public function addTool(McpToolBuilder $tool): self
    {
        $this->tools[] = $tool;
        $this->toolsCapability = true;
        return $this;
    }

    /**
     * @internal Called by {@see McpResourceBuilder::and()}.
     */
    public function addResource(McpResourceBuilder $resource): self
    {
        $this->resources[] = $resource;
        $this->resourcesCapability = true;
        return $this;
    }

    /**
     * @internal Called by {@see McpPromptBuilder::and()}.
     */
    public function addPrompt(McpPromptBuilder $prompt): self
    {
        $this->prompts[] = $prompt;
        $this->promptsCapability = true;
        return $this;
    }

    // --- terminal operations -----------------------------------------------

    /**
     * @return list<Expectation>
     */
    public function build(): array
    {
        $expectations = [
            $this->buildInitializeExpectation(),
            $this->buildPingExpectation(),
            $this->buildNotificationsInitializedExpectation(),
        ];

        if ($this->toolsCapability || count($this->tools) > 0) {
            $expectations[] = $this->buildToolsListExpectation();
        }
        foreach ($this->tools as $tool) {
            $expectations[] = $this->buildToolsCallExpectation($tool);
        }

        if ($this->resourcesCapability || count($this->resources) > 0) {
            $expectations[] = $this->buildResourcesListExpectation();
        }
        foreach ($this->resources as $resource) {
            $expectations[] = $this->buildResourcesReadExpectation($resource);
        }

        if ($this->promptsCapability || count($this->prompts) > 0) {
            $expectations[] = $this->buildPromptsListExpectation();
        }
        foreach ($this->prompts as $prompt) {
            $expectations[] = $this->buildPromptsGetExpectation($prompt);
        }

        return $expectations;
    }

    /**
     * @return array<mixed>
     */
    public function applyTo(MockServerClient $client): array
    {
        $results = [];
        foreach ($this->build() as $expectation) {
            $results[] = $client->upsertExpectation($expectation);
        }
        return $results;
    }

    // --- request matchers --------------------------------------------------

    /**
     * @return array<string, mixed>
     */
    private function jsonRpcRequest(string $method): array
    {
        return [
            'method' => 'POST',
            'path' => $this->path,
            'body' => ['type' => 'JSON_RPC', 'method' => $method],
        ];
    }

    /**
     * @return array<string, mixed>
     */
    private function jsonPathRequest(string $jsonPath): array
    {
        return [
            'method' => 'POST',
            'path' => $this->path,
            'body' => ['type' => 'JSON_PATH', 'jsonPath' => $jsonPath],
        ];
    }

    /**
     * @param array<string, mixed> $httpRequest
     */
    private function velocityTemplateExpectation(array $httpRequest, string $resultJson): Expectation
    {
        return Expectation::fromArray([
            'httpRequest' => $httpRequest,
            'httpResponseTemplate' => [
                'template' => McpEscaping::velocityJsonRpcResponse($resultJson),
                'templateType' => 'VELOCITY',
            ],
        ]);
    }

    // --- expectation builders ----------------------------------------------

    private function buildInitializeExpectation(): Expectation
    {
        $capsParts = [];
        if ($this->toolsCapability || count($this->tools) > 0) {
            $capsParts[] = '"tools": {"listChanged": false}';
        }
        if ($this->resourcesCapability || count($this->resources) > 0) {
            $capsParts[] = '"resources": {"subscribe": false, "listChanged": false}';
        }
        if ($this->promptsCapability || count($this->prompts) > 0) {
            $capsParts[] = '"prompts": {"listChanged": false}';
        }
        $caps = '{' . implode(', ', $capsParts) . '}';

        $resultJson = '{"protocolVersion": "' . McpEscaping::escapeVelocity(McpEscaping::escapeJson($this->protocolVersion)) . '", '
            . '"capabilities": ' . $caps . ', '
            . '"serverInfo": {"name": "' . McpEscaping::escapeVelocity(McpEscaping::escapeJson($this->serverName))
            . '", "version": "' . McpEscaping::escapeVelocity(McpEscaping::escapeJson($this->serverVersion)) . '"}}';

        return $this->velocityTemplateExpectation($this->jsonRpcRequest('initialize'), $resultJson);
    }

    private function buildPingExpectation(): Expectation
    {
        return $this->velocityTemplateExpectation($this->jsonRpcRequest('ping'), '{}');
    }

    private function buildNotificationsInitializedExpectation(): Expectation
    {
        return Expectation::fromArray([
            'httpRequest' => $this->jsonRpcRequest('notifications/initialized'),
            'httpResponse' => [
                'statusCode' => 200,
                'headers' => [['name' => 'Content-Type', 'values' => ['application/json']]],
                'body' => '{}',
            ],
        ]);
    }

    private function buildToolsListExpectation(): Expectation
    {
        $items = [];
        foreach ($this->tools as $tool) {
            $parts = '{"name": "' . McpEscaping::escapeVelocity(McpEscaping::escapeJson($tool->name)) . '"';
            if ($tool->description !== null) {
                $parts .= ', "description": "' . McpEscaping::escapeVelocity(McpEscaping::escapeJson($tool->description)) . '"';
            }
            if ($tool->inputSchema !== null) {
                $parts .= ', "inputSchema": ' . McpEscaping::escapeVelocity(McpEscaping::validateAndSerializeJson($tool->inputSchema));
            }
            $parts .= '}';
            $items[] = $parts;
        }
        $toolsJson = '[' . implode(', ', $items) . ']';

        return $this->velocityTemplateExpectation(
            $this->jsonRpcRequest('tools/list'),
            '{"tools": ' . $toolsJson . '}'
        );
    }

    private function buildToolsCallExpectation(McpToolBuilder $tool): Expectation
    {
        $jsonPath = "$[?(@.method == 'tools/call' && @.params.name == '" . McpEscaping::escapeJsonPath($tool->name) . "')]";
        $content = $tool->responseContent !== null
            ? McpEscaping::escapeVelocity(McpEscaping::escapeJson($tool->responseContent))
            : '';
        $isError = $tool->responseIsError ? 'true' : 'false';
        $resultJson = '{"content": [{"type": "text", "text": "' . $content . '"}], "isError": ' . $isError . '}';

        return $this->velocityTemplateExpectation($this->jsonPathRequest($jsonPath), $resultJson);
    }

    private function buildResourcesListExpectation(): Expectation
    {
        $items = [];
        foreach ($this->resources as $resource) {
            $parts = '{"uri": "' . McpEscaping::escapeVelocity(McpEscaping::escapeJson($resource->uri)) . '"';
            if ($resource->name !== null) {
                $parts .= ', "name": "' . McpEscaping::escapeVelocity(McpEscaping::escapeJson($resource->name)) . '"';
            }
            if ($resource->description !== null) {
                $parts .= ', "description": "' . McpEscaping::escapeVelocity(McpEscaping::escapeJson($resource->description)) . '"';
            }
            if ($resource->mimeType !== null) {
                $parts .= ', "mimeType": "' . McpEscaping::escapeVelocity(McpEscaping::escapeJson($resource->mimeType)) . '"';
            }
            $parts .= '}';
            $items[] = $parts;
        }
        $resourcesJson = '[' . implode(', ', $items) . ']';

        return $this->velocityTemplateExpectation(
            $this->jsonRpcRequest('resources/list'),
            '{"resources": ' . $resourcesJson . '}'
        );
    }

    private function buildResourcesReadExpectation(McpResourceBuilder $resource): Expectation
    {
        $jsonPath = "$[?(@.method == 'resources/read' && @.params.uri == '" . McpEscaping::escapeJsonPath($resource->uri) . "')]";
        $content = $resource->content !== null
            ? McpEscaping::escapeVelocity(McpEscaping::escapeJson($resource->content))
            : '';
        $mimeType = $resource->mimeType !== null ? $resource->mimeType : 'application/json';
        $resultJson = '{"contents": [{"uri": "' . McpEscaping::escapeVelocity(McpEscaping::escapeJson($resource->uri)) . '", '
            . '"mimeType": "' . McpEscaping::escapeVelocity(McpEscaping::escapeJson($mimeType)) . '", '
            . '"text": "' . $content . '"}]}';

        return $this->velocityTemplateExpectation($this->jsonPathRequest($jsonPath), $resultJson);
    }

    private function buildPromptsListExpectation(): Expectation
    {
        $items = [];
        foreach ($this->prompts as $prompt) {
            $parts = '{"name": "' . McpEscaping::escapeVelocity(McpEscaping::escapeJson($prompt->name)) . '"';
            if ($prompt->description !== null) {
                $parts .= ', "description": "' . McpEscaping::escapeVelocity(McpEscaping::escapeJson($prompt->description)) . '"';
            }
            if (count($prompt->arguments) > 0) {
                $argItems = [];
                foreach ($prompt->arguments as $arg) {
                    $argParts = '{"name": "' . McpEscaping::escapeVelocity(McpEscaping::escapeJson($arg['name'])) . '"';
                    if ($arg['description'] !== null) {
                        $argParts .= ', "description": "' . McpEscaping::escapeVelocity(McpEscaping::escapeJson($arg['description'])) . '"';
                    }
                    $argParts .= ', "required": ' . ($arg['required'] ? 'true' : 'false');
                    $argParts .= '}';
                    $argItems[] = $argParts;
                }
                $parts .= ', "arguments": [' . implode(', ', $argItems) . ']';
            }
            $parts .= '}';
            $items[] = $parts;
        }
        $promptsJson = '[' . implode(', ', $items) . ']';

        return $this->velocityTemplateExpectation(
            $this->jsonRpcRequest('prompts/list'),
            '{"prompts": ' . $promptsJson . '}'
        );
    }

    private function buildPromptsGetExpectation(McpPromptBuilder $prompt): Expectation
    {
        $jsonPath = "$[?(@.method == 'prompts/get' && @.params.name == '" . McpEscaping::escapeJsonPath($prompt->name) . "')]";
        $msgItems = [];
        foreach ($prompt->messages as $msg) {
            $msgItems[] = '{"role": "' . McpEscaping::escapeVelocity(McpEscaping::escapeJson($msg['role'])) . '", '
                . '"content": {"type": "text", "text": "' . McpEscaping::escapeVelocity(McpEscaping::escapeJson($msg['text'])) . '"}}';
        }
        $messagesJson = '[' . implode(', ', $msgItems) . ']';
        $resultJson = '{"messages": ' . $messagesJson . '}';

        return $this->velocityTemplateExpectation($this->jsonPathRequest($jsonPath), $resultJson);
    }
}
