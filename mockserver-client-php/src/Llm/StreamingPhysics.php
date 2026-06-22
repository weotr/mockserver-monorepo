<?php

declare(strict_types=1);

namespace MockServer\Llm;

use InvalidArgumentException;
use MockServer\Delay;

/**
 * Controls the timing physics of a streamed (SSE) completion
 * (mirrors org.mockserver.model.StreamingPhysics).
 *
 * {@code timeToFirstToken} serialises as a Delay: {@code {timeUnit, value}}.
 */
class StreamingPhysics implements \JsonSerializable
{
    private ?Delay $timeToFirstToken = null;
    private ?int $tokensPerSecond = null;
    private ?float $jitter = null;
    private ?int $seed = null;

    /**
     * Static factory mirroring {@code StreamingPhysics.streamingPhysics()}.
     */
    public static function streamingPhysics(): self
    {
        return new self();
    }

    /**
     * Static factory mirroring {@code StreamingPhysics.tokensPerSecond(n)}.
     */
    public static function tokensPerSecond(int $tokensPerSecond): self
    {
        return (new self())->withTokensPerSecond($tokensPerSecond);
    }

    /**
     * Static factory mirroring {@code StreamingPhysics.jitter(j)}.
     */
    public static function jitter(float $jitter): self
    {
        return (new self())->withJitter($jitter);
    }

    /**
     * Build a Delay representing time-to-first-token.
     */
    public static function timeToFirstToken(int $value, string $timeUnit = 'MILLISECONDS'): Delay
    {
        return new Delay($timeUnit, $value);
    }

    public function withTimeToFirstToken(Delay $delay): self
    {
        $this->timeToFirstToken = $delay;
        return $this;
    }

    public function withTokensPerSecond(int $tokensPerSecond): self
    {
        if ($tokensPerSecond < 1 || $tokensPerSecond > 10000) {
            throw new InvalidArgumentException('tokensPerSecond must be between 1 and 10000');
        }
        $this->tokensPerSecond = $tokensPerSecond;
        return $this;
    }

    public function withJitter(float $jitter): self
    {
        if ($jitter < 0.0 || $jitter > 1.0) {
            throw new InvalidArgumentException('jitter must be between 0.0 and 1.0');
        }
        $this->jitter = $jitter;
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
        if ($this->timeToFirstToken !== null) {
            $data['timeToFirstToken'] = $this->timeToFirstToken->toArray();
        }
        if ($this->tokensPerSecond !== null) {
            $data['tokensPerSecond'] = $this->tokensPerSecond;
        }
        if ($this->jitter !== null) {
            $data['jitter'] = $this->jitter;
        }
        if ($this->seed !== null) {
            $data['seed'] = $this->seed;
        }
        return $data;
    }
}
