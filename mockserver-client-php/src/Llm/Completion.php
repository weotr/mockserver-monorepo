<?php

declare(strict_types=1);

namespace MockServer\Llm;

/**
 * A mocked LLM chat/completion response, provider-agnostic
 * (mirrors org.mockserver.model.Completion).
 *
 * MockServer re-encodes this into the wire shape of the configured provider
 * (OpenAI / Anthropic / Gemini / Bedrock / ...) when the request is served.
 */
class Completion implements \JsonSerializable
{
    private ?string $text = null;
    /** @var list<ToolUse>|null */
    private ?array $toolCalls = null;
    private ?string $stopReason = null;
    private ?Usage $usage = null;
    private ?bool $streaming = null;
    private ?StreamingPhysics $streamingPhysics = null;
    private ?string $outputSchema = null;
    private ?string $model = null;

    /**
     * Static factory mirroring {@code Completion.completion()}.
     */
    public static function completion(): self
    {
        return new self();
    }

    public function withText(string $text): self
    {
        $this->text = $text;
        return $this;
    }

    public function withToolCall(ToolUse $toolCall): self
    {
        if ($this->toolCalls === null) {
            $this->toolCalls = [];
        }
        $this->toolCalls[] = $toolCall;
        return $this;
    }

    public function withToolCalls(ToolUse ...$toolCalls): self
    {
        $this->toolCalls = array_values($toolCalls);
        return $this;
    }

    public function withStopReason(string $stopReason): self
    {
        $this->stopReason = $stopReason;
        return $this;
    }

    public function withUsage(Usage $usage): self
    {
        $this->usage = $usage;
        return $this;
    }

    public function withStreaming(bool $streaming = true): self
    {
        $this->streaming = $streaming;
        return $this;
    }

    /**
     * Mirror of Java {@code completion().streaming()} — enables streaming.
     */
    public function streaming(): self
    {
        return $this->withStreaming(true);
    }

    public function withStreamingPhysics(StreamingPhysics $streamingPhysics): self
    {
        $this->streamingPhysics = $streamingPhysics;
        return $this;
    }

    /**
     * Set the structured-output JSON schema.
     *
     * @param array<mixed>|string $outputSchema JSON string (matching Java) or an
     *                                           array serialised to a JSON string.
     */
    public function withOutputSchema(array|string $outputSchema): self
    {
        $this->outputSchema = is_array($outputSchema)
            ? json_encode($outputSchema, JSON_THROW_ON_ERROR)
            : $outputSchema;
        return $this;
    }

    public function withModel(string $model): self
    {
        $this->model = $model;
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
        if ($this->text !== null) {
            $data['text'] = $this->text;
        }
        if ($this->toolCalls !== null) {
            $data['toolCalls'] = array_map(static fn (ToolUse $t): array => $t->toArray(), $this->toolCalls);
        }
        if ($this->stopReason !== null) {
            $data['stopReason'] = $this->stopReason;
        }
        if ($this->usage !== null) {
            $data['usage'] = $this->usage->toArray();
        }
        if ($this->streaming !== null) {
            $data['streaming'] = $this->streaming;
        }
        if ($this->streamingPhysics !== null) {
            $data['streamingPhysics'] = $this->streamingPhysics->toArray();
        }
        if ($this->outputSchema !== null) {
            $data['outputSchema'] = $this->outputSchema;
        }
        if ($this->model !== null) {
            $data['model'] = $this->model;
        }
        return $data;
    }
}
