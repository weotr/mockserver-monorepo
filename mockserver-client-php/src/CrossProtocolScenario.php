<?php

declare(strict_types=1);

namespace MockServer;

/**
 * A cross-protocol scenario transition: when an event on one protocol fires
 * (a DNS query, WebSocket connect, gRPC request or HTTP request), advance a
 * named scenario state machine to a target state.
 *
 * @example
 *   $scenario = CrossProtocolScenario::trigger(CrossProtocolTrigger::DNS_QUERY)
 *       ->matchPattern('api.example.com')
 *       ->scenarioName('Deploy')
 *       ->targetState('Deploying');
 */
class CrossProtocolScenario implements \JsonSerializable
{
    private string $trigger;
    private ?string $matchPattern = null;
    private ?string $scenarioName = null;
    private ?string $targetState = null;

    /**
     * @param string $trigger One of the {@see CrossProtocolTrigger} constants.
     */
    public function __construct(string $trigger)
    {
        $this->trigger = $trigger;
    }

    /**
     * Static factory for fluent construction.
     *
     * @param string $trigger One of the {@see CrossProtocolTrigger} constants.
     */
    public static function trigger(string $trigger): self
    {
        return new self($trigger);
    }

    /**
     * Optional substring filter on the event identifier; omit to match all.
     */
    public function matchPattern(string $matchPattern): self
    {
        $this->matchPattern = $matchPattern;
        return $this;
    }

    public function scenarioName(string $scenarioName): self
    {
        $this->scenarioName = $scenarioName;
        return $this;
    }

    public function targetState(string $targetState): self
    {
        $this->targetState = $targetState;
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
        $data = ['trigger' => $this->trigger];

        if ($this->matchPattern !== null) {
            $data['matchPattern'] = $this->matchPattern;
        }
        if ($this->scenarioName !== null) {
            $data['scenarioName'] = $this->scenarioName;
        }
        if ($this->targetState !== null) {
            $data['targetState'] = $this->targetState;
        }

        return $data;
    }
}
