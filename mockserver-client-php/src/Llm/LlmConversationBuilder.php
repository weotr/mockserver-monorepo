<?php

declare(strict_types=1);

namespace MockServer\Llm;

use InvalidArgumentException;
use MockServer\Expectation;
use MockServer\HttpRequest;
use MockServer\MockServerClient;

/**
 * Builder for multi-turn LLM conversation mocks using MockServer scenario
 * state advancement (mirrors org.mockserver.client.LlmConversationBuilder).
 *
 * For turn {@code i} (0-based, of {@code n} turns):
 *   scenarioState     = i == 0 ? "Started" : "turn_<i>"
 *   newScenarioState  = i < n-1 ? "turn_<i+1>" : "__done"
 *
 * @example
 *   LlmConversationBuilder::conversation()
 *       ->withPath('/v1/chat/completions')
 *       ->withProvider(Provider::ANTHROPIC)
 *       ->turn()->respondingWith(Completion::completion()->withText('Hi'))
 *       ->turn()->respondingWith(Completion::completion()->withText('Bye'))
 *       ->applyTo($client);
 */
class LlmConversationBuilder
{
    private const SCENARIO_PREFIX = '__llm_conv_';
    private const ISOLATION_MARKER = '__iso=';
    private const DONE_STATE = '__done';

    private ?string $path = null;
    private ?string $provider = null;
    private ?string $model = null;
    private ?IsolationSource $isolationSource = null;
    /** @var list<TurnBuilder> */
    private array $turns = [];

    /**
     * Entry point mirroring {@code LlmConversationBuilder.conversation()}.
     */
    public static function conversation(): self
    {
        return new self();
    }

    public function withPath(string $path): self
    {
        $this->path = $path;
        return $this;
    }

    public function withProvider(string $provider): self
    {
        $this->provider = $provider;
        return $this;
    }

    public function withModel(string $model): self
    {
        $this->model = $model;
        return $this;
    }

    public function isolateBy(IsolationSource $source): self
    {
        $this->isolationSource = $source;
        return $this;
    }

    public function turn(): TurnBuilder
    {
        $turnBuilder = new TurnBuilder($this);
        $this->turns[] = $turnBuilder;
        return $turnBuilder;
    }

    /**
     * @return list<Expectation>
     */
    public function build(): array
    {
        if (count($this->turns) === 0) {
            throw new InvalidArgumentException('At least one turn must be defined');
        }
        if ($this->path === null) {
            throw new InvalidArgumentException('Path must be set');
        }
        if ($this->provider === null) {
            throw new InvalidArgumentException('Provider must be set');
        }

        $conversationId = self::SCENARIO_PREFIX . Uuid::v4();
        $scenarioName = $conversationId;
        if ($this->isolationSource !== null) {
            $scenarioName = $conversationId . self::ISOLATION_MARKER . $this->isolationSource->encode();
        }

        $expectations = [];
        $n = count($this->turns);
        foreach ($this->turns as $i => $turn) {
            $nextState = $i < $n - 1 ? 'turn_' . ($i + 1) : self::DONE_STATE;

            $action = HttpLlmResponse::llmResponse()->withProvider($this->provider);
            if ($this->model !== null) {
                $action->withModel($this->model);
            }
            if ($turn->completion !== null) {
                $action->withCompletion($turn->completion);
            }
            if ($turn->chaos !== null) {
                $action->withChaos($turn->chaos);
            }
            if ($turn->hasAnyPredicate()) {
                $action->withConversationPredicates($turn->predicates());
            }

            $expectations[] = (new Expectation())
                ->httpRequest(HttpRequest::request()->method('POST')->path($this->path))
                ->scenarioName($scenarioName)
                ->scenarioState($i === 0 ? 'Started' : 'turn_' . $i)
                ->newScenarioState($nextState)
                ->httpLlmResponse($action);
        }

        return $expectations;
    }

    /**
     * @return array<mixed>
     */
    public function applyTo(MockServerClient $client): array
    {
        $results = [];
        foreach ($this->build() as $expectation) {
            $results[] = $client->upsertExpectation($expectation);
        }
        return $results;
    }
}
