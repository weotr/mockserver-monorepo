<?php

declare(strict_types=1);

namespace MockServer\A2a;

use MockServer\Expectation;
use MockServer\MockServerClient;

/**
 * Fluent builder for mocking an A2A (Agent-to-Agent) agent speaking JSON-RPC 2.0
 * over HTTP (mirrors {@code org.mockserver.client.A2aMockBuilder}).
 *
 * Produces a list of HTTP expectations whose wire JSON is identical to the Java
 * builder, so every client drives the same server behaviour. The generated
 * control-plane expectations are:
 *
 *  - {@code GET <agentCardPath>} returning the agent card (literal JSON body);
 *  - {@code POST <path>} JSON-RPC {@code tasks/send}, {@code tasks/get},
 *    {@code tasks/cancel} (Velocity-templated JSON-RPC responses);
 *  - per custom task handler, a JSON-PATH-matched {@code tasks/send} responder;
 *  - optionally an SSE streaming responder for {@code message/stream};
 *  - optionally {@code tasks/pushNotificationConfig/set} plus a push-notification
 *    delivery override that POSTs the completed task to the webhook URL while
 *    still returning the JSON-RPC task response to the caller.
 *
 * Note: A2A is purely declarative (matchers + templated/literal responses), so
 * it is fully supported by the REST-only PHP client.
 *
 * @example
 *   A2aMockBuilder::a2aMock('/a2a')
 *       ->withAgentName('WeatherAgent')
 *       ->withSkill('forecast')
 *           ->withName('Forecast')
 *           ->withDescription('Weather forecasting')
 *           ->withTag('weather')
 *       ->and()
 *       ->onTaskSend()
 *           ->matchingMessage('.*weather.*')
 *           ->respondingWith('72F and sunny')
 *       ->and()
 *       ->applyTo($client);
 */
class A2aMockBuilder
{
    private string $path = '/a2a';
    private string $agentCardPath = '/.well-known/agent.json';
    private string $agentName = 'MockAgent';
    private string $agentDescription = 'A mock A2A agent';
    private string $agentVersion = '1.0.0';
    private ?string $agentUrl = null;
    /** @var list<A2aSkillBuilder> */
    private array $skills = [];
    /** @var list<A2aTaskHandlerBuilder> */
    private array $taskHandlers = [];
    private string $defaultTaskResponse = 'Task completed successfully';
    private bool $streaming = false;
    private string $streamingMethod = 'message/stream';
    private ?string $pushNotificationUrl = null;

    public function __construct(string $path = '/a2a')
    {
        $this->path = $path;
    }

    public static function a2aMock(string $path = '/a2a'): self
    {
        return new self($path);
    }

    // --- top-level configuration -------------------------------------------

    public function withAgentName(string $name): self
    {
        $this->agentName = $name;
        return $this;
    }

    public function withAgentDescription(string $description): self
    {
        $this->agentDescription = $description;
        return $this;
    }

    public function withAgentVersion(string $version): self
    {
        $this->agentVersion = $version;
        return $this;
    }

    public function withAgentUrl(string $url): self
    {
        $this->agentUrl = $url;
        return $this;
    }

    public function withAgentCardPath(string $path): self
    {
        $this->agentCardPath = $path;
        return $this;
    }

    public function withDefaultTaskResponse(string $response): self
    {
        $this->defaultTaskResponse = $response;
        return $this;
    }

    /**
     * Advertise and mock the A2A streaming capability. The agent card reports
     * {@code capabilities.streaming: true} and the streaming method (default
     * {@code message/stream}) returns an SSE stream of status/artifact update
     * events wrapped in JSON-RPC 2.0 envelopes.
     */
    public function withStreaming(): self
    {
        $this->streaming = true;
        return $this;
    }

    /**
     * Override the JSON-RPC method that triggers the streaming response and imply
     * {@see withStreaming()}.
     */
    public function withStreamingMethod(string $method): self
    {
        $this->streamingMethod = $method;
        $this->streaming = true;
        return $this;
    }

    /**
     * Advertise and mock A2A push notifications. The agent card reports
     * {@code capabilities.pushNotifications: true}, the
     * {@code tasks/pushNotificationConfig/set} method echoes the registered
     * config, and each {@code tasks/send} additionally POSTs the completed task
     * to {@code $webhookUrl} while still returning the JSON-RPC task response.
     */
    public function withPushNotifications(string $webhookUrl): self
    {
        $this->pushNotificationUrl = $webhookUrl;
        return $this;
    }

    public function withSkill(string $id): A2aSkillBuilder
    {
        return new A2aSkillBuilder($this, $id);
    }

    public function onTaskSend(): A2aTaskHandlerBuilder
    {
        return new A2aTaskHandlerBuilder($this);
    }

    /**
     * @internal Called by {@see A2aSkillBuilder::and()}.
     */
    public function addSkill(A2aSkillBuilder $skill): self
    {
        $this->skills[] = $skill;
        return $this;
    }

    /**
     * @internal Called by {@see A2aTaskHandlerBuilder::and()}.
     */
    public function addTaskHandler(A2aTaskHandlerBuilder $handler): self
    {
        $this->taskHandlers[] = $handler;
        return $this;
    }

    // --- terminal operations -----------------------------------------------

    /**
     * @return list<Expectation>
     */
    public function build(): array
    {
        $expectations = [];

        $expectations[] = $this->buildAgentCardExpectation();

        foreach ($this->taskHandlers as $handler) {
            $expectations[] = $this->buildCustomTaskHandler($handler);
        }

        if ($this->streaming) {
            $expectations[] = $this->buildStreamingExpectation();
        }

        if ($this->pushNotificationUrl !== null) {
            $expectations[] = $this->buildPushNotificationConfigExpectation();
            $expectations[] = $this->buildPushNotificationDeliveryExpectation();
        } else {
            $expectations[] = $this->buildTasksSendExpectation();
        }
        $expectations[] = $this->buildTasksGetExpectation();
        $expectations[] = $this->buildTasksCancelExpectation();

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
     * @param array<string, mixed> $httpRequest
     */
    private function velocityTemplateExpectation(array $httpRequest, string $resultJson): Expectation
    {
        return Expectation::fromArray([
            'httpRequest' => $httpRequest,
            'httpResponseTemplate' => [
                'template' => A2aEscaping::velocityJsonRpcResponse($resultJson),
                'templateType' => 'VELOCITY',
            ],
        ]);
    }

    // --- expectation builders ----------------------------------------------

    private function buildAgentCardExpectation(): Expectation
    {
        $skillItems = [];
        foreach ($this->skills as $skill) {
            $parts = '{';
            $parts .= '"id": "' . A2aEscaping::escapeJson($skill->id) . '"';
            $parts .= ', "name": "' . A2aEscaping::escapeJson($skill->name ?? $skill->id) . '"';
            if ($skill->description !== null) {
                $parts .= ', "description": "' . A2aEscaping::escapeJson($skill->description) . '"';
            }
            if (count($skill->tags) > 0) {
                $tagItems = [];
                foreach ($skill->tags as $tag) {
                    $tagItems[] = '"' . A2aEscaping::escapeJson($tag) . '"';
                }
                $parts .= ', "tags": [' . implode(', ', $tagItems) . ']';
            }
            if (count($skill->examples) > 0) {
                $exampleItems = [];
                foreach ($skill->examples as $example) {
                    $exampleItems[] = '"' . A2aEscaping::escapeJson($example) . '"';
                }
                $parts .= ', "examples": [' . implode(', ', $exampleItems) . ']';
            }
            $parts .= '}';
            $skillItems[] = $parts;
        }
        $skillsJson = '[' . implode(', ', $skillItems) . ']';

        $url = $this->agentUrl ?? 'http://localhost' . $this->path;

        $agentCardJson = '{'
            . '"name": "' . A2aEscaping::escapeJson($this->agentName) . '", '
            . '"description": "' . A2aEscaping::escapeJson($this->agentDescription) . '", '
            . '"version": "' . A2aEscaping::escapeJson($this->agentVersion) . '", '
            . '"url": "' . A2aEscaping::escapeJson($url) . '", '
            . '"capabilities": {"streaming": ' . ($this->streaming ? 'true' : 'false')
            . ', "pushNotifications": ' . ($this->pushNotificationUrl !== null ? 'true' : 'false')
            . ', "stateTransitionHistory": false}, '
            . '"skills": ' . $skillsJson . '}';

        return Expectation::fromArray([
            'httpRequest' => [
                'method' => 'GET',
                'path' => $this->agentCardPath,
            ],
            'httpResponse' => [
                'statusCode' => 200,
                'headers' => [['name' => 'Content-Type', 'values' => ['application/json']]],
                'body' => $agentCardJson,
            ],
        ]);
    }

    private function buildTasksSendExpectation(): Expectation
    {
        return $this->velocityTemplateExpectation(
            $this->jsonRpcRequest('tasks/send'),
            $this->buildTaskResultJson($this->defaultTaskResponse, false)
        );
    }

    private function buildTasksGetExpectation(): Expectation
    {
        return $this->velocityTemplateExpectation(
            $this->jsonRpcRequest('tasks/get'),
            $this->buildTaskResultJson($this->defaultTaskResponse, false)
        );
    }

    private function buildTasksCancelExpectation(): Expectation
    {
        $resultJson = '{"id": "mock-task-id", "status": {"state": "canceled"}}';
        return $this->velocityTemplateExpectation($this->jsonRpcRequest('tasks/cancel'), $resultJson);
    }

    private function buildStreamingExpectation(): Expectation
    {
        $text = A2aEscaping::escapeJson($this->defaultTaskResponse);
        $taskId = 'mock-task-id';

        // A2A streaming: each SSE event data is a JSON-RPC 2.0 response envelope
        // wrapping a TaskStatusUpdateEvent or TaskArtifactUpdateEvent. The
        // JSON-RPC id is not known at build time, so a stable placeholder is used.
        $events = [
            [
                'event' => 'message',
                'data' => '{"jsonrpc": "2.0", "id": "1", "result": '
                    . '{"taskId": "' . $taskId . '", "kind": "status-update", '
                    . '"status": {"state": "working"}, "final": false}}',
            ],
            [
                'event' => 'message',
                'data' => '{"jsonrpc": "2.0", "id": "1", "result": '
                    . '{"taskId": "' . $taskId . '", "kind": "artifact-update", '
                    . '"artifact": {"parts": [{"type": "text", "text": "' . $text . '"}]}}}',
            ],
            [
                'event' => 'message',
                'data' => '{"jsonrpc": "2.0", "id": "1", "result": '
                    . '{"taskId": "' . $taskId . '", "kind": "status-update", '
                    . '"status": {"state": "completed"}, "final": true}}',
            ],
        ];

        return Expectation::fromArray([
            'httpRequest' => $this->jsonRpcRequest($this->streamingMethod),
            'httpSseResponse' => [
                'statusCode' => 200,
                'events' => $events,
                'closeConnection' => true,
            ],
        ]);
    }

    private function buildPushNotificationConfigExpectation(): Expectation
    {
        // Echo the registered push-notification config back as the JSON-RPC result.
        $url = A2aEscaping::escapeVelocity(A2aEscaping::escapeJson($this->pushNotificationUrl));
        $resultJson = '{"url": "' . $url . '"}';
        return $this->velocityTemplateExpectation(
            $this->jsonRpcRequest('tasks/pushNotificationConfig/set'),
            $resultJson
        );
    }

    private function buildPushNotificationDeliveryExpectation(): Expectation
    {
        // When push notifications are configured, a tasks/send both returns the
        // JSON-RPC task response to the caller AND POSTs the completed task to the
        // configured webhook URL. This is modelled with an
        // httpOverrideForwardedRequest: the request override targets the webhook
        // (literal body), and a Velocity response *template* produces the caller's
        // JSON-RPC response so the request's id is echoed back.
        $target = A2aEscaping::parseWebhook((string) $this->pushNotificationUrl);

        // Literal webhook POST body — no Velocity engine runs over a request
        // override, so only JSON escaping is applied. The push payload carries no
        // JSON-RPC id (server-initiated).
        $pushBody = '{"jsonrpc": "2.0", "result": '
            . $this->buildTaskResultJsonRaw($this->defaultTaskResponse, false) . '}';

        $hostHeader = $target['host'] . ':' . $target['port'];

        $webhookRequest = [
            'method' => 'POST',
            'path' => $target['path'],
            'socketAddress' => [
                'host' => $target['host'],
                'port' => $target['port'],
                'scheme' => $target['secure'] ? 'HTTPS' : 'HTTP',
            ],
            'secure' => $target['secure'],
            'headers' => [
                'Host' => [$hostHeader],
                'Content-Type' => ['application/json'],
            ],
            'body' => $pushBody,
        ];

        // Caller response — a Velocity template so $!{request.jsonRpcRawId} echoes
        // the request id, matching the non-push tasks/send contract.
        $clientResponseTemplate = [
            'template' => A2aEscaping::velocityJsonRpcResponse(
                $this->buildTaskResultJson($this->defaultTaskResponse, false)
            ),
            'templateType' => 'VELOCITY',
        ];

        return Expectation::fromArray([
            'httpRequest' => $this->jsonRpcRequest('tasks/send'),
            'httpOverrideForwardedRequest' => [
                'requestOverride' => $webhookRequest,
                'responseTemplate' => $clientResponseTemplate,
            ],
        ]);
    }

    private function buildCustomTaskHandler(A2aTaskHandlerBuilder $handler): Expectation
    {
        $escapedPattern = $this->escapeMessagePattern($handler->messagePattern);
        $jsonPathBody = "$[?(@.method == 'tasks/send' && @.params.message.parts[0].text =~ /"
            . $escapedPattern . "/)]";
        $resultJson = $this->buildTaskResultJson($handler->responseText, $handler->isError);

        return $this->velocityTemplateExpectation(
            [
                'method' => 'POST',
                'path' => $this->path,
                'body' => ['type' => 'JSON_PATH', 'jsonPath' => $jsonPathBody],
            ],
            $resultJson
        );
    }

    /**
     * Escape a user-supplied regular expression so it can be embedded between the
     * '/' delimiters of the JsonPath {@code =~ /<PATTERN>/} matcher without the
     * pattern being able to break OUT of the regex literal.
     *
     * Single-pass, left-to-right, byte-oriented (all special chars are ASCII;
     * other bytes are emitted verbatim to preserve UTF-8):
     *  - On '\\': preserve an existing escape sequence by emitting the backslash
     *    plus the following char verbatim (so '\d' stays '\d', '\/' stays '\/');
     *    a TRAILING lone backslash is doubled to a literal backslash so it cannot
     *    escape the closing delimiter.
     *  - On bare '/': emit '\/' so it cannot terminate the regex literal.
     *  - On newline / CR: emit the two-char escapes '\n' / '\r'.
     *  - On NUL: strip it.
     *  - Otherwise: emit the byte verbatim.
     */
    private function escapeMessagePattern(string $pattern): string
    {
        $out = '';
        $len = strlen($pattern);
        for ($i = 0; $i < $len; $i++) {
            $c = $pattern[$i];
            if ($c === '\\') {
                if ($i + 1 < $len) {
                    $out .= '\\' . $pattern[$i + 1];
                    $i++;
                } else {
                    // trailing lone backslash -> literal backslash
                    $out .= '\\\\';
                }
            } elseif ($c === '/') {
                $out .= '\\/';
            } elseif ($c === "\n") {
                $out .= '\\n';
            } elseif ($c === "\r") {
                $out .= '\\r';
            } elseif ($c === "\0") {
                // strip NUL
                continue;
            } else {
                $out .= $c;
            }
        }
        return $out;
    }

    /**
     * For Velocity-templated response bodies: the text must survive the Velocity
     * engine, so metacharacters are escaped here and un-escaped at response time.
     */
    private function buildTaskResultJson(string $responseText, bool $isError): string
    {
        return $this->taskResultJson(
            (string) A2aEscaping::escapeVelocity(A2aEscaping::escapeJson($responseText)),
            $isError
        );
    }

    /**
     * For literal (non-templated) response bodies (e.g. the webhook POST payload),
     * where no Velocity engine runs, only JSON escaping is applied — Velocity
     * escaping would corrupt any '$' / '#' into "${esc.d}" / "${esc.h}".
     */
    private function buildTaskResultJsonRaw(string $responseText, bool $isError): string
    {
        return $this->taskResultJson(A2aEscaping::escapeJson($responseText), $isError);
    }

    private function taskResultJson(string $escapedText, bool $isError): string
    {
        $state = $isError ? 'failed' : 'completed';
        return '{"id": "mock-task-id", '
            . '"status": {"state": "' . $state . '"}, '
            . '"artifacts": [{"parts": [{"type": "text", "text": "' . $escapedText . '"}]}]}';
    }
}
