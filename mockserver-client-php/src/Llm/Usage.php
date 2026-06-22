<?php

declare(strict_types=1);

namespace MockServer\Llm;

use InvalidArgumentException;

/**
 * Token usage counts for a completion (mirrors org.mockserver.model.Usage).
 */
class Usage implements \JsonSerializable
{
    private ?int $inputTokens = null;
    private ?int $outputTokens = null;

    /**
     * Static factory mirroring {@code Usage.usage()}.
     */
    public static function usage(): self
    {
        return new self();
    }

    /**
     * Static factory mirroring {@code Usage.inputTokens(n)}.
     */
    public static function inputTokens(int $inputTokens): self
    {
        return (new self())->withInputTokens($inputTokens);
    }

    /**
     * Static factory mirroring {@code Usage.outputTokens(n)}.
     */
    public static function outputTokens(int $outputTokens): self
    {
        return (new self())->withOutputTokens($outputTokens);
    }

    public function withInputTokens(int $inputTokens): self
    {
        if ($inputTokens < 0) {
            throw new InvalidArgumentException('inputTokens must be >= 0');
        }
        $this->inputTokens = $inputTokens;
        return $this;
    }

    public function withOutputTokens(int $outputTokens): self
    {
        if ($outputTokens < 0) {
            throw new InvalidArgumentException('outputTokens must be >= 0');
        }
        $this->outputTokens = $outputTokens;
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
        if ($this->inputTokens !== null) {
            $data['inputTokens'] = $this->inputTokens;
        }
        if ($this->outputTokens !== null) {
            $data['outputTokens'] = $this->outputTokens;
        }
        return $data;
    }
}
