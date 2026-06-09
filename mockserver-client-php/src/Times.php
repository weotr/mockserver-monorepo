<?php

declare(strict_types=1);

namespace MockServer;

/**
 * Controls how many times an expectation can be matched.
 */
class Times implements \JsonSerializable
{
    private ?int $remainingTimes;
    private bool $unlimited;

    private function __construct(?int $remainingTimes, bool $unlimited)
    {
        $this->remainingTimes = $remainingTimes;
        $this->unlimited = $unlimited;
    }

    /**
     * Match unlimited times.
     */
    public static function unlimited(): self
    {
        return new self(null, true);
    }

    /**
     * Match exactly N times.
     */
    public static function exactly(int $count): self
    {
        return new self($count, false);
    }

    /**
     * Match once.
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
        $data = ['unlimited' => $this->unlimited];
        if ($this->remainingTimes !== null) {
            $data['remainingTimes'] = $this->remainingTimes;
        }
        return $data;
    }
}
