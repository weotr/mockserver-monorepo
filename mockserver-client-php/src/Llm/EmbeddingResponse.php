<?php

declare(strict_types=1);

namespace MockServer\Llm;

/**
 * A mocked embedding response — vector shape and determinism
 * (mirrors org.mockserver.model.EmbeddingResponse).
 */
class EmbeddingResponse implements \JsonSerializable
{
    private ?int $dimensions = null;
    private ?bool $deterministicFromInput = null;
    private ?int $seed = null;

    /**
     * Static factory mirroring {@code EmbeddingResponse.embedding()}.
     */
    public static function embedding(): self
    {
        return new self();
    }

    public function withDimensions(int $dimensions): self
    {
        $this->dimensions = $dimensions;
        return $this;
    }

    public function withDeterministicFromInput(bool $deterministicFromInput): self
    {
        $this->deterministicFromInput = $deterministicFromInput;
        return $this;
    }

    public function withSeed(int $seed): self
    {
        $this->seed = $seed;
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
        if ($this->dimensions !== null) {
            $data['dimensions'] = $this->dimensions;
        }
        if ($this->deterministicFromInput !== null) {
            $data['deterministicFromInput'] = $this->deterministicFromInput;
        }
        if ($this->seed !== null) {
            $data['seed'] = $this->seed;
        }
        return $data;
    }
}
