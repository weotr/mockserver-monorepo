<?php

declare(strict_types=1);

namespace MockServer;

/**
 * Adaptive iteration pacing (think-time) for a {@see LoadScenario}: a target
 * per-virtual-user iteration cycle time. After a closed-model VU finishes one
 * pass through the steps, the orchestrator waits whatever remains of the target
 * cycle before launching that VU's next iteration; on overrun the next
 * iteration starts immediately (no wait).
 *
 * Applies only to the closed-model VU loop — open-model RATE iterations ignore
 * it — and composes with per-step think-time.
 *
 * Use the static factories {@see LoadPacing::none()},
 * {@see LoadPacing::constantPacing()} and
 * {@see LoadPacing::constantThroughput()}.
 *
 * @example
 *   LoadPacing::constantPacing(1000);      // target 1s iteration cycle
 *   LoadPacing::constantThroughput(2.0);   // 2 iterations/second per VU
 */
class LoadPacing implements \JsonSerializable
{
    private string $mode;
    private float $value;

    private function __construct(string $mode, float $value)
    {
        $this->mode = strtoupper($mode);
        $this->value = $value;
    }

    /**
     * No pacing (immediate reschedule). The value is ignored.
     */
    public static function none(): self
    {
        return new self('NONE', 0.0);
    }

    /**
     * Hold a target iteration cycle of {@code $millis} milliseconds.
     */
    public static function constantPacing(float $millis): self
    {
        return new self('CONSTANT_PACING', $millis);
    }

    /**
     * Hold a target throughput of {@code $iterationsPerSecond} iterations/second
     * per virtual user (cycle = 1000 / value ms).
     */
    public static function constantThroughput(float $iterationsPerSecond): self
    {
        return new self('CONSTANT_THROUGHPUT', $iterationsPerSecond);
    }

    /**
     * Build pacing from an explicit mode and value.
     *
     * @param string $mode One of NONE, CONSTANT_PACING, CONSTANT_THROUGHPUT.
     * @param float $value Target cycle in milliseconds (CONSTANT_PACING) or
     *        iterations/second per VU (CONSTANT_THROUGHPUT); ignored for NONE.
     */
    public static function of(string $mode, float $value): self
    {
        return new self($mode, $value);
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
            'mode' => $this->mode,
            'value' => $this->value,
        ];
    }
}
