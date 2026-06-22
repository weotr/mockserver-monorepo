<?php

declare(strict_types=1);

namespace MockServer\Llm;

use MockServer\Expectation;
use MockServer\HttpRequest;
use MockServer\MockServerClient;

/**
 * Fluent builder for a single LLM completion or embedding mock
 * (mirrors org.mockserver.client.LlmMockBuilder).
 *
 * @example
 *   LlmMockBuilder::llmMock('/v1/chat/completions')
 *       ->withProvider(Provider::OPENAI)
 *       ->withModel('gpt-4o')
 *       ->respondingWith(Completion::completion()->withText('Hello!'))
 *       ->applyTo($client);
 */
class LlmMockBuilder
{
    private string $path;
    private ?string $provider = null;
    private ?string $model = null;
    private ?Completion $completion = null;
    private ?EmbeddingResponse $embedding = null;

    public function __construct(string $path)
    {
        $this->path = $path;
    }

    /**
     * Entry point mirroring {@code LlmMockBuilder.llmMock(path)}.
     */
    public static function llmMock(string $path): self
    {
        return new self($path);
    }

    public function withProvider(string $provider): self
    {
        $this->provider = $provider;
        return $this;
    }

    public function withModel(string $model): self
    {
        $this->model = $model;
        return $this;
    }

    /**
     * Respond with a completion or an embedding. Setting one clears the other.
     */
    public function respondingWith(Completion|EmbeddingResponse $response): self
    {
        if ($response instanceof EmbeddingResponse) {
            $this->embedding = $response;
            $this->completion = null;
        } else {
            $this->completion = $response;
            $this->embedding = null;
        }
        return $this;
    }

    public function build(): Expectation
    {
        $action = HttpLlmResponse::llmResponse();
        if ($this->provider !== null) {
            $action->withProvider($this->provider);
        }
        if ($this->model !== null) {
            $action->withModel($this->model);
        }
        if ($this->completion !== null) {
            $action->withCompletion($this->completion);
        }
        if ($this->embedding !== null) {
            $action->withEmbedding($this->embedding);
        }

        return (new Expectation())
            ->httpRequest(HttpRequest::request()->method('POST')->path($this->path))
            ->httpLlmResponse($action);
    }

    /**
     * Build the expectation and register it via the client.
     *
     * @return array<mixed>
     */
    public function applyTo(MockServerClient $client): array
    {
        return $client->upsertExpectation($this->build());
    }
}
