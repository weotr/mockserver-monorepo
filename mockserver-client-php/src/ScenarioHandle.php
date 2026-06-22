<?php

declare(strict_types=1);

namespace MockServer;

/**
 * A typed handle for a single named scenario state machine, returned by
 * {@see MockServerClient::scenario()}.
 *
 * @example
 *   $client->scenario('Deploy')->set('Deploying', 5000, 'Deployed');
 *   $client->scenario('Deploy')->trigger('Failed');
 *   $state = $client->scenario('Deploy')->state();
 */
class ScenarioHandle
{
    private MockServerClient $client;
    private string $name;

    public function __construct(MockServerClient $client, string $name)
    {
        $this->client = $client;
        $this->name = $name;
    }

    /**
     * Get the current state of this scenario.
     *
     * @return array<string, mixed> {@code {"scenarioName","currentState"}}.
     */
    public function state(): array
    {
        return $this->client->getScenario($this->name);
    }

    /**
     * Set the state of this scenario, optionally scheduling a timed transition
     * to a follow-up state.
     *
     * @param string $state The state to set immediately.
     * @param int|null $transitionAfterMs Milliseconds after which to transition.
     * @param string|null $nextState The state to transition to (requires
     *        {@code $transitionAfterMs}).
     * @return array<string, mixed> {@code {"scenarioName","currentState",...}}.
     */
    public function set(string $state, ?int $transitionAfterMs = null, ?string $nextState = null): array
    {
        return $this->client->setScenario($this->name, $state, $transitionAfterMs, $nextState);
    }

    /**
     * Trigger an immediate transition of this scenario to a new state.
     *
     * @param string $newState The state to transition to.
     * @return array<string, mixed> {@code {"scenarioName","currentState"}}.
     */
    public function trigger(string $newState): array
    {
        return $this->client->triggerScenario($this->name, $newState);
    }
}
