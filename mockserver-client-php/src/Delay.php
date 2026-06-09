<?php

declare(strict_types=1);

namespace MockServer;

/**
 * Represents a time delay applied to a response.
 */
class Delay implements \JsonSerializable
{
    private string $timeUnit;
    private int $value;

    public function __construct(string $timeUnit, int $value)
    {
        $this->timeUnit = strtoupper($timeUnit);
        $this->value = $value;
    }

    /**
     * Create a delay in milliseconds.
     */
    public static function milliseconds(int $value): self
    {
        return new self('MILLISECONDS', $value);
    }

    /**
     * Create a delay in seconds.
     */
    public static function seconds(int $value): self
    {
        return new self('SECONDS', $value);
    }

    public function getTimeUnit(): string
    {
        return $this->timeUnit;
    }

    public function getValue(): int
    {
        return $this->value;
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
        return [
            'timeUnit' => $this->timeUnit,
            'value' => $this->value,
        ];
    }
}
