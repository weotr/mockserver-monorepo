<?php

declare(strict_types=1);

namespace MockServer;

/**
 * The load profile of a {@see LoadScenario}: an ordered list of
 * {@see LoadStage}s the orchestrator runs in sequence. Each stage holds or ramps
 * a setpoint — concurrent virtual users (VU, closed model), an arrival rate in
 * iterations/second (RATE, open model), or no load at all (PAUSE) — for its
 * duration.
 *
 * Use {@see LoadProfile::of()} to build from a list of stages, or the
 * convenience constructors {@see LoadProfile::constant()} /
 * {@see LoadProfile::linear()} for a single VU stage.
 *
 * @example
 *   LoadProfile::of(
 *       LoadStage::vuRamp(1, 10, 10000),
 *       LoadStage::vuHold(10, 30000),
 *       LoadStage::pause(5000),
 *   );
 */
class LoadProfile implements \JsonSerializable
{
    /** @var array<int, LoadStage> */
    private array $stages = [];
    private ?LoadShape $shape = null;

    public function __construct()
    {
    }

    /**
     * Build a profile from an ordered list of stages.
     */
    public static function of(LoadStage ...$stages): self
    {
        $profile = new self();
        $profile->stages = $stages;

        return $profile;
    }

    /**
     * Build a profile from a single declarative named {@see LoadShape} that the
     * server expands into ordinary stages. Use a shape OR an explicit list of
     * stages, not both.
     */
    public static function fromShape(LoadShape $shape): self
    {
        $profile = new self();
        $profile->shape = $shape;

        return $profile;
    }

    /**
     * A single VU stage holding {@code $vus} virtual users for the whole
     * {@code $durationMillis}.
     */
    public static function constant(int $vus, int $durationMillis): self
    {
        return self::of(LoadStage::vuHold($vus, $durationMillis));
    }

    /**
     * A single linear VU ramp from {@code $startVus} to {@code $endVus} over
     * {@code $durationMillis}.
     */
    public static function linear(int $startVus, int $endVus, int $durationMillis): self
    {
        return self::of(LoadStage::vuRamp($startVus, $endVus, $durationMillis, 'LINEAR'));
    }

    /**
     * Append a stage and return this profile.
     */
    public function addStage(LoadStage $stage): self
    {
        $this->stages[] = $stage;

        return $this;
    }

    /**
     * Set the declarative named {@see LoadShape} for this profile. If explicit
     * stages are also set the server prefers the stages.
     */
    public function shape(LoadShape $shape): self
    {
        $this->shape = $shape;

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
        $result = [];
        if ($this->stages !== []) {
            $result['stages'] = array_map(
                static fn (LoadStage $stage): array => $stage->toArray(),
                $this->stages,
            );
        }
        if ($this->shape !== null) {
            $result['shape'] = $this->shape->toArray();
        }
        // Preserve the historical shape: a profile built from stages always
        // emits a (possibly empty) "stages" array even when none were added.
        if ($this->stages === [] && $this->shape === null) {
            $result['stages'] = [];
        }

        return $result;
    }
}
