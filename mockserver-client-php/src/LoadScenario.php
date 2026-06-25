<?php

declare(strict_types=1);

namespace MockServer;

/**
 * A small fluent value object describing an API load scenario for
 * {@see MockServerClient::loadScenario()}.
 *
 * A scenario is an ordered list of templated request steps fired at a target
 * load described by a staged {@see LoadProfile}, with per-iteration data
 * variation. It is a pure SLI producer: each completed request records a
 * latency/error sample that {@see MockServerClient::verifySlo()} can read.
 *
 * Each scenario carries a unique {@code name} that the server's registry keys it
 * by. {@see MockServerClient::loadScenario()} only registers (loads) the
 * scenario; {@see MockServerClient::startLoadScenarios()} drives the load.
 *
 * This object is optional — {@see MockServerClient::loadScenario()} also accepts
 * a plain associative array in the same camelCase shape — but it gives a typed,
 * discoverable builder that emits the correct JSON.
 *
 * @example
 *   $scenario = LoadScenario::scenario('checkout-load')
 *       ->maxRequests(5000)
 *       ->profile(LoadProfile::of(
 *           LoadStage::vuRamp(1, 10, 10000),
 *           LoadStage::vuHold(10, 30000),
 *       ))
 *       ->addStep(
 *           HttpRequest::request()->method('GET')->path('/api/item/$iteration.index'),
 *           Delay::milliseconds(20),
 *       );
 *   $client->loadScenario($scenario);
 */
class LoadScenario implements \JsonSerializable
{
    private string $name;
    private ?string $templateType = null;
    private ?int $maxRequests = null;
    private ?int $startDelayMillis = null;
    private ?LoadProfile $profile = null;
    /** @var array<int, array<string, mixed>> */
    private array $steps = [];
    /** @var array<string, string> */
    private array $labels = [];
    /** @var array<int, LoadThreshold> */
    private array $thresholds = [];
    private ?bool $abortOnFail = null;
    private ?int $abortGraceMillis = null;
    private ?LoadPacing $pacing = null;
    private ?LoadFeeder $feeder = null;
    private ?string $stepSelection = null;

    public function __construct(string $name)
    {
        $this->name = $name;
    }

    /**
     * Begin building a scenario with the given human-readable name.
     */
    public static function scenario(string $name): self
    {
        return new self($name);
    }

    /**
     * The unique name this scenario is registered under.
     */
    public function getName(): string
    {
        return $this->name;
    }

    /**
     * Set the template engine used to render per-iteration paths and bodies.
     *
     * Only VELOCITY (default) and MUSTACHE are supported for load steps.
     */
    public function templateType(string $templateType): self
    {
        $this->templateType = strtoupper($templateType);

        return $this;
    }

    /**
     * Set a hard cap on the total number of requests dispatched.
     */
    public function maxRequests(int $maxRequests): self
    {
        $this->maxRequests = $maxRequests;

        return $this;
    }

    /**
     * Set an optional delay (in milliseconds) applied after the scenario is
     * started before it begins driving load. Honoured by
     * {@see MockServerClient::startLoadScenarios()}.
     */
    public function startDelayMillis(int $startDelayMillis): self
    {
        $this->startDelayMillis = $startDelayMillis;

        return $this;
    }

    /**
     * Set the staged load profile describing the load over time.
     */
    public function profile(LoadProfile $profile): self
    {
        $this->profile = $profile;

        return $this;
    }

    /**
     * Add a templated request step.
     *
     * @param HttpRequest $request The (templated) request to fire each iteration
     * @param Delay|null $thinkTime Optional inter-step pause
     * @param string|null $name Optional human label / metric label for the step
     * @param array<string, string> $labels Optional step-level annotation labels
     * @param array<int, LoadCapture> $captures Optional cross-step capture rules
     *        applied to this step's response (visible to subsequent steps in the
     *        same iteration).
     * @param float|null $weight Relative selection weight, used only when the
     *        scenario's stepSelection is WEIGHTED.
     */
    public function addStep(
        HttpRequest $request,
        ?Delay $thinkTime = null,
        ?string $name = null,
        array $labels = [],
        array $captures = [],
        ?float $weight = null,
    ): self {
        $step = ['request' => $request->toArray()];
        if ($thinkTime !== null) {
            $step['thinkTime'] = $thinkTime->toArray();
        }
        if ($name !== null) {
            $step['name'] = $name;
        }
        if ($labels !== []) {
            $step['labels'] = $labels;
        }
        if ($captures !== []) {
            $step['captures'] = array_map(
                static fn (LoadCapture $capture): array => $capture->toArray(),
                array_values($captures),
            );
        }
        if ($weight !== null) {
            $step['weight'] = $weight;
        }
        $this->steps[] = $step;

        return $this;
    }

    /**
     * Set scenario-level annotation labels (exported as OpenTelemetry attributes
     * and, for allowlisted keys, Prometheus labels).
     *
     * @param array<string, string> $labels
     */
    public function labels(array $labels): self
    {
        $this->labels = $labels;

        return $this;
    }

    /**
     * Set the in-run pass/fail thresholds. The run carries a PASS verdict iff
     * all hold, FAIL otherwise. Empty means no verdict is computed.
     *
     * @param array<int, LoadThreshold> $thresholds
     */
    public function thresholds(LoadThreshold ...$thresholds): self
    {
        $this->thresholds = array_values($thresholds);

        return $this;
    }

    /**
     * Append a single in-run pass/fail threshold.
     */
    public function addThreshold(LoadThreshold $threshold): self
    {
        $this->thresholds[] = $threshold;

        return $this;
    }

    /**
     * When true, a FAIL verdict aborts the run early (terminal STOPPED state,
     * abortedByThreshold set); default false (the run always finishes its
     * stages and carries a final verdict).
     */
    public function abortOnFail(bool $abortOnFail = true): self
    {
        $this->abortOnFail = $abortOnFail;

        return $this;
    }

    /**
     * Suppress abortOnFail for the first N milliseconds of the run so noisy
     * startup samples cannot trigger a premature abort.
     */
    public function abortGraceMillis(int $abortGraceMillis): self
    {
        $this->abortGraceMillis = $abortGraceMillis;

        return $this;
    }

    /**
     * Set adaptive iteration pacing (think-time) for the closed-model VU loop.
     */
    public function pacing(LoadPacing $pacing): self
    {
        $this->pacing = $pacing;

        return $this;
    }

    /**
     * Set the parameterized test data (data feeder) for the scenario.
     */
    public function feeder(LoadFeeder $feeder): self
    {
        $this->feeder = $feeder;

        return $this;
    }

    /**
     * Set how each iteration selects which steps to run: SEQUENTIAL (default,
     * all steps in declared order) or WEIGHTED (exactly one step per iteration
     * chosen at random proportional to each step's weight).
     */
    public function stepSelection(string $stepSelection): self
    {
        $this->stepSelection = strtoupper($stepSelection);

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
        $result = ['name' => $this->name];
        if ($this->templateType !== null) {
            $result['templateType'] = $this->templateType;
        }
        if ($this->maxRequests !== null) {
            $result['maxRequests'] = $this->maxRequests;
        }
        if ($this->startDelayMillis !== null) {
            $result['startDelayMillis'] = $this->startDelayMillis;
        }
        if ($this->labels !== []) {
            $result['labels'] = $this->labels;
        }
        if ($this->thresholds !== []) {
            $result['thresholds'] = array_map(
                static fn (LoadThreshold $threshold): array => $threshold->toArray(),
                $this->thresholds,
            );
        }
        if ($this->abortOnFail !== null) {
            $result['abortOnFail'] = $this->abortOnFail;
        }
        if ($this->abortGraceMillis !== null) {
            $result['abortGraceMillis'] = $this->abortGraceMillis;
        }
        if ($this->pacing !== null) {
            $result['pacing'] = $this->pacing->toArray();
        }
        if ($this->feeder !== null) {
            $result['feeder'] = $this->feeder->toArray();
        }
        if ($this->profile !== null) {
            $result['profile'] = $this->profile->toArray();
        }
        if ($this->stepSelection !== null) {
            $result['stepSelection'] = $this->stepSelection;
        }
        $result['steps'] = $this->steps;

        return $result;
    }
}
