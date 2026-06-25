<?php

declare(strict_types=1);

namespace MockServer;

/**
 * An in-run pass/fail threshold for a {@see LoadScenario}: a per-run metric
 * compared against a value. All thresholds must hold for the run verdict to be
 * PASS (logical AND); any breach makes the verdict FAIL. Evaluated from the
 * run's own latency histogram and counters, not the global SLO sample store.
 *
 * Use the static factory {@see LoadThreshold::of()}; it emits a camelCase array
 * with the {@code metric}, {@code comparator} and {@code threshold} fields.
 *
 * @example
 *   LoadThreshold::of('LATENCY_P95', 'LESS_THAN', 250);
 *   LoadThreshold::of('ERROR_RATE', 'LESS_THAN_OR_EQUAL', 0.01);
 */
class LoadThreshold implements \JsonSerializable
{
    private string $metric;
    private string $comparator;
    private float $threshold;

    private function __construct(string $metric, string $comparator, float $threshold)
    {
        $this->metric = strtoupper($metric);
        $this->comparator = strtoupper($comparator);
        $this->threshold = $threshold;
    }

    /**
     * Build a threshold from a per-run metric, a comparator and a value.
     *
     * @param string $metric One of LATENCY_P50, LATENCY_P95, LATENCY_P99,
     *        LATENCY_P999, ERROR_RATE, THROUGHPUT_RPS.
     * @param string $comparator One of LESS_THAN, LESS_THAN_OR_EQUAL,
     *        GREATER_THAN, GREATER_THAN_OR_EQUAL.
     * @param float $threshold The threshold value (milliseconds for latency
     *        metrics, a 0.0-1.0 fraction for ERROR_RATE, requests/second for
     *        THROUGHPUT_RPS).
     */
    public static function of(string $metric, string $comparator, float $threshold): self
    {
        return new self($metric, $comparator, $threshold);
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
            'metric' => $this->metric,
            'comparator' => $this->comparator,
            'threshold' => $this->threshold,
        ];
    }
}
