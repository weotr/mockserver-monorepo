<?php

declare(strict_types=1);

namespace MockServer;

/**
 * Specifies the expected number of times a request should have been received for verification.
 */
class VerificationTimes implements \JsonSerializable
{
    private ?int $atLeast;
    private ?int $atMost;

    private function __construct(?int $atLeast, ?int $atMost)
    {
        $this->atLeast = $atLeast;
        $this->atMost = $atMost;
    }

    /**
     * Request received at least N times.
     */
    public static function atLeast(int $count): self
    {
        return new self($count, null);
    }

    /**
     * Request received at most N times.
     */
    public static function atMost(int $count): self
    {
        return new self(null, $count);
    }

    /**
     * Request received exactly N times.
     */
    public static function exactly(int $count): self
    {
        return new self($count, $count);
    }

    /**
     * Request received between N and M times (inclusive).
     */
    public static function between(int $atLeast, int $atMost): self
    {
        return new self($atLeast, $atMost);
    }

    /**
     * Request received once.
     */
    public static function once(): self
    {
        return self::exactly(1);
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
        if ($this->atLeast !== null) {
            $data['atLeast'] = $this->atLeast;
        }
        if ($this->atMost !== null) {
            $data['atMost'] = $this->atMost;
        }
        return $data;
    }
}
