<?php

declare(strict_types=1);

namespace MockServer\Llm;

use InvalidArgumentException;
use MockServer\Expectation;
use MockServer\MockServerClient;

/**
 * Sub-builder configuring one turn of a conversation mock
 * (mirrors org.mockserver.client.TurnBuilder).
 */
class TurnBuilder
{
    private LlmConversationBuilder $parent;

    public ?int $turnIndex = null;
    public ?string $latestMessageContains = null;
    public ?string $latestMessageMatches = null;
    public ?string $latestMessageRole = null;
    public ?string $containsToolResultFor = null;
    public ?string $semanticMatchAgainst = null;
    public ?NormalizationOptions $normalization = null;
    /** @var array<string, mixed>|null */
    public ?array $chaos = null;
    public ?Completion $completion = null;

    public function __construct(LlmConversationBuilder $parent)
    {
        $this->parent = $parent;
    }

    public function whenTurnIndex(int $turnIndex): self
    {
        $this->turnIndex = $turnIndex;
        return $this;
    }

    public function whenLatestMessageContains(string $text): self
    {
        $this->latestMessageContains = $text;
        return $this;
    }

    public function whenLatestMessageMatches(string $regex): self
    {
        if ($regex === '') {
            throw new InvalidArgumentException('regex must not be empty');
        }
        $this->latestMessageMatches = $regex;
        return $this;
    }

    public function whenLatestMessageRole(string $role): self
    {
        $this->latestMessageRole = $role;
        return $this;
    }

    public function whenContainsToolResultFor(string $toolName): self
    {
        $this->containsToolResultFor = $toolName;
        return $this;
    }

    public function whenSemanticMatch(string $expectedMeaning): self
    {
        $this->semanticMatchAgainst = $expectedMeaning;
        return $this;
    }

    public function withNormalization(NormalizationOptions $normalization): self
    {
        $this->normalization = $normalization;
        return $this;
    }

    /**
     * Attach a chaos/fault profile (a JSON-serialisable map) to this turn.
     *
     * @param array<string, mixed> $chaos
     */
    public function withChaos(array $chaos): self
    {
        $this->chaos = $chaos;
        return $this;
    }

    public function respondingWith(Completion $completion): self
    {
        $this->completion = $completion;
        return $this;
    }

    /**
     * Start a new turn on the parent conversation.
     */
    public function turn(): TurnBuilder
    {
        return $this->parent->turn();
    }

    /**
     * Return to the parent conversation builder.
     */
    public function andThen(): LlmConversationBuilder
    {
        return $this->parent;
    }

    /**
     * @return list<Expectation>
     */
    public function build(): array
    {
        return $this->parent->build();
    }

    /**
     * @return array<mixed>
     */
    public function applyTo(MockServerClient $client): array
    {
        return $this->parent->applyTo($client);
    }

    /**
     * Build the conversation predicates for this turn (always populated; the
     * caller decides whether to attach it based on {@see hasAnyPredicate()}).
     */
    public function predicates(): ConversationPredicates
    {
        $predicates = new ConversationPredicates();
        if ($this->turnIndex !== null) {
            $predicates->withTurnIndex($this->turnIndex);
        }
        if ($this->latestMessageContains !== null) {
            $predicates->withLatestMessageContains($this->latestMessageContains);
        }
        if ($this->latestMessageMatches !== null) {
            $predicates->withLatestMessageMatches($this->latestMessageMatches);
        }
        if ($this->latestMessageRole !== null) {
            $predicates->withLatestMessageRole($this->latestMessageRole);
        }
        if ($this->containsToolResultFor !== null) {
            $predicates->withContainsToolResultFor($this->containsToolResultFor);
        }
        if ($this->semanticMatchAgainst !== null) {
            $predicates->withSemanticMatchAgainst($this->semanticMatchAgainst);
        }
        if ($this->normalization !== null) {
            $predicates->withNormalization($this->normalization);
        }
        return $predicates;
    }

    /**
     * True if at least one predicate (NOT normalization) is set.
     */
    public function hasAnyPredicate(): bool
    {
        return $this->turnIndex !== null
            || $this->latestMessageContains !== null
            || $this->latestMessageMatches !== null
            || $this->latestMessageRole !== null
            || $this->containsToolResultFor !== null
            || $this->semanticMatchAgainst !== null;
    }
}
