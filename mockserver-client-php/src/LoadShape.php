<?php

declare(strict_types=1);

namespace MockServer;

/**
 * A declarative named load shape for a {@see LoadProfile} that expands into
 * ordinary {@see LoadStage}s; only the parameters its {@code type} needs are
 * read, the rest are ignored. Use a shape OR an explicit list of stages, not
 * both.
 *
 * Three shapes are supported:
 *   - SPIKE: ramp up from a baseline to a peak, hold the peak, ramp back down,
 *     with an optional recovery hold at baseline.
 *   - STAIRS: a flight of pure-hold steps, each one 'step' higher.
 *   - RAMP_HOLD: ramp 0 to a target then hold.
 *
 * The {@code metric} chooses what the shape drives: VU (concurrent virtual
 * users, closed model) or RATE (arrival rate in iterations/second, open model).
 *
 * Use the static factories {@see LoadShape::spike()}, {@see LoadShape::stairs()}
 * and {@see LoadShape::rampHold()}; each emits a camelCase array with only the
 * relevant fields set.
 *
 * @example
 *   LoadShape::spike('VU', 1, 50, 5000, 30000, 5000)->recoveryHoldMillis(10000);
 *   LoadShape::stairs('RATE', 10, 10, 5, 20000);
 *   LoadShape::rampHold('VU', 25, 10000, 60000);
 */
class LoadShape implements \JsonSerializable
{
    private string $type;
    private ?string $metric = null;
    private ?string $curve = null;
    private ?float $baseline = null;
    private ?float $peak = null;
    private ?int $rampUpMillis = null;
    private ?int $holdMillis = null;
    private ?int $rampDownMillis = null;
    private ?int $recoveryHoldMillis = null;
    private ?float $start = null;
    private ?float $step = null;
    private ?int $steps = null;
    private ?int $stepDurationMillis = null;
    private ?float $target = null;
    private ?int $rampMillis = null;

    private function __construct(string $type)
    {
        $this->type = strtoupper($type);
    }

    /**
     * A SPIKE shape: hold {@code $baseline}, ramp up to {@code $peak} over
     * {@code $rampUpMillis}, hold the peak for {@code $holdMillis}, then ramp
     * back down over {@code $rampDownMillis}. Add an optional recovery hold with
     * {@see LoadShape::recoveryHoldMillis()}.
     *
     * @param string $metric VU or RATE.
     */
    public static function spike(
        string $metric,
        float $baseline,
        float $peak,
        int $rampUpMillis,
        int $holdMillis,
        int $rampDownMillis,
    ): self {
        $shape = new self('SPIKE');
        $shape->metric = strtoupper($metric);
        $shape->baseline = $baseline;
        $shape->peak = $peak;
        $shape->rampUpMillis = $rampUpMillis;
        $shape->holdMillis = $holdMillis;
        $shape->rampDownMillis = $rampDownMillis;

        return $shape;
    }

    /**
     * A STAIRS shape: a flight of {@code $steps} pure-hold steps, the first at
     * {@code $start}, each one {@code $step} higher than the previous, each
     * holding for {@code $stepDurationMillis}.
     *
     * @param string $metric VU or RATE.
     */
    public static function stairs(
        string $metric,
        float $start,
        float $step,
        int $steps,
        int $stepDurationMillis,
    ): self {
        $shape = new self('STAIRS');
        $shape->metric = strtoupper($metric);
        $shape->start = $start;
        $shape->step = $step;
        $shape->steps = $steps;
        $shape->stepDurationMillis = $stepDurationMillis;

        return $shape;
    }

    /**
     * A RAMP_HOLD shape: ramp from 0 to {@code $target} over {@code $rampMillis}
     * then hold the target for {@code $holdMillis}.
     *
     * @param string $metric VU or RATE.
     */
    public static function rampHold(
        string $metric,
        float $target,
        int $rampMillis,
        int $holdMillis,
    ): self {
        $shape = new self('RAMP_HOLD');
        $shape->metric = strtoupper($metric);
        $shape->target = $target;
        $shape->rampMillis = $rampMillis;
        $shape->holdMillis = $holdMillis;

        return $shape;
    }

    /**
     * Set the ramp curve (e.g. LINEAR, EXPONENTIAL).
     */
    public function curve(string $curve): self
    {
        $this->curve = strtoupper($curve);

        return $this;
    }

    /**
     * SPIKE (optional): duration to hold at baseline after the down ramp.
     */
    public function recoveryHoldMillis(int $recoveryHoldMillis): self
    {
        $this->recoveryHoldMillis = $recoveryHoldMillis;

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
        $result = ['type' => $this->type];
        if ($this->metric !== null) {
            $result['metric'] = $this->metric;
        }
        if ($this->curve !== null) {
            $result['curve'] = $this->curve;
        }
        if ($this->baseline !== null) {
            $result['baseline'] = $this->baseline;
        }
        if ($this->peak !== null) {
            $result['peak'] = $this->peak;
        }
        if ($this->rampUpMillis !== null) {
            $result['rampUpMillis'] = $this->rampUpMillis;
        }
        if ($this->holdMillis !== null) {
            $result['holdMillis'] = $this->holdMillis;
        }
        if ($this->rampDownMillis !== null) {
            $result['rampDownMillis'] = $this->rampDownMillis;
        }
        if ($this->recoveryHoldMillis !== null) {
            $result['recoveryHoldMillis'] = $this->recoveryHoldMillis;
        }
        if ($this->start !== null) {
            $result['start'] = $this->start;
        }
        if ($this->step !== null) {
            $result['step'] = $this->step;
        }
        if ($this->steps !== null) {
            $result['steps'] = $this->steps;
        }
        if ($this->stepDurationMillis !== null) {
            $result['stepDurationMillis'] = $this->stepDurationMillis;
        }
        if ($this->target !== null) {
            $result['target'] = $this->target;
        }
        if ($this->rampMillis !== null) {
            $result['rampMillis'] = $this->rampMillis;
        }

        return $result;
    }
}
