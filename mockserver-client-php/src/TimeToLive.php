<?php

declare(strict_types=1);

namespace MockServer;

/**
 * Controls how long an expectation stays active.
 */
class TimeToLive implements \JsonSerializable
{
    private ?string $timeUnit;
    private ?int $timeToLive;
    private bool $unlimited;

    private function __construct(?string $timeUnit, ?int $timeToLive, bool $unlimited)
    {
        $this->timeUnit = $timeUnit;
        $this->timeToLive = $timeToLive;
        $this->unlimited = $unlimited;
    }

    /**
     * Never expires.
     */
    public static function unlimited(): self
    {
        return new self(null, null, true);
    }

    /**
     * Expires after a given number of time units.
     *
     * @param string $timeUnit e.g. "SECONDS", "MILLISECONDS", "MINUTES"
     * @param int $value
     */
    public static function exactly(string $timeUnit, int $value): self
    {
        return new self(strtoupper($timeUnit), $value, false);
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
        if ($this->timeUnit !== null) {
            $data['timeUnit'] = $this->timeUnit;
        }
        if ($this->timeToLive !== null) {
            $data['timeToLive'] = $this->timeToLive;
        }
        return $data;
    }
}
