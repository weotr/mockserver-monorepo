<?php

declare(strict_types=1);

namespace MockServer;

/**
 * One stage of a {@see LoadProfile}: a contiguous slice of the run holding or
 * ramping a setpoint for its duration. Stages run in sequence.
 *
 * A stage is one of three kinds:
 *   - VU (closed model): hold {@code vus} or ramp from {@code startVus} to
 *     {@code endVus} along a curve.
 *   - RATE (open model): hold {@code rate} or ramp from {@code startRate} to
 *     {@code endRate} (iterations/second) along a curve, optionally capped at
 *     {@code maxVus} virtual users.
 *   - PAUSE: drive no load for the duration.
 *
 * Use the static factories {@see LoadStage::vuHold()}, {@see LoadStage::vuRamp()},
 * {@see LoadStage::rateHold()}, {@see LoadStage::rateRamp()} and
 * {@see LoadStage::pause()}; each emits a camelCase array with only the relevant
 * fields set.
 */
class LoadStage implements \JsonSerializable
{
    private string $type;
    private int $durationMillis;
    private ?string $curve = null;
    private ?int $vus = null;
    private ?int $startVus = null;
    private ?int $endVus = null;
    private ?float $rate = null;
    private ?float $startRate = null;
    private ?float $endRate = null;
    private ?int $maxVus = null;

    private function __construct(string $type, int $durationMillis)
    {
        $this->type = $type;
        $this->durationMillis = $durationMillis;
    }

    /**
     * A VU stage holding {@code $vus} virtual users for {@code $durationMillis}.
     */
    public static function vuHold(int $vus, int $durationMillis): self
    {
        $stage = new self('VU', $durationMillis);
        $stage->vus = $vus;

        return $stage;
    }

    /**
     * A VU stage ramping from {@code $startVus} to {@code $endVus} over
     * {@code $durationMillis} along {@code $curve} (default LINEAR).
     */
    public static function vuRamp(
        int $startVus,
        int $endVus,
        int $durationMillis,
        string $curve = 'LINEAR',
    ): self {
        $stage = new self('VU', $durationMillis);
        $stage->startVus = $startVus;
        $stage->endVus = $endVus;
        $stage->curve = strtoupper($curve);

        return $stage;
    }

    /**
     * A RATE stage holding {@code $rate} iterations/second for
     * {@code $durationMillis}.
     */
    public static function rateHold(float $rate, int $durationMillis): self
    {
        $stage = new self('RATE', $durationMillis);
        $stage->rate = $rate;

        return $stage;
    }

    /**
     * A RATE stage ramping from {@code $startRate} to {@code $endRate}
     * iterations/second over {@code $durationMillis} along {@code $curve}
     * (default LINEAR).
     */
    public static function rateRamp(
        float $startRate,
        float $endRate,
        int $durationMillis,
        string $curve = 'LINEAR',
    ): self {
        $stage = new self('RATE', $durationMillis);
        $stage->startRate = $startRate;
        $stage->endRate = $endRate;
        $stage->curve = strtoupper($curve);

        return $stage;
    }

    /**
     * A PAUSE stage that drives no load for {@code $durationMillis}.
     */
    public static function pause(int $durationMillis): self
    {
        return new self('PAUSE', $durationMillis);
    }

    /**
     * Cap the auto-scaling virtual-user pool for this RATE stage.
     */
    public function maxVus(int $maxVus): self
    {
        $this->maxVus = $maxVus;

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
        $result = [
            'type' => $this->type,
            'durationMillis' => $this->durationMillis,
        ];
        if ($this->curve !== null) {
            $result['curve'] = $this->curve;
        }
        if ($this->vus !== null) {
            $result['vus'] = $this->vus;
        }
        if ($this->startVus !== null) {
            $result['startVus'] = $this->startVus;
        }
        if ($this->endVus !== null) {
            $result['endVus'] = $this->endVus;
        }
        if ($this->rate !== null) {
            $result['rate'] = $this->rate;
        }
        if ($this->startRate !== null) {
            $result['startRate'] = $this->startRate;
        }
        if ($this->endRate !== null) {
            $result['endRate'] = $this->endRate;
        }
        if ($this->maxVus !== null) {
            $result['maxVus'] = $this->maxVus;
        }

        return $result;
    }
}
