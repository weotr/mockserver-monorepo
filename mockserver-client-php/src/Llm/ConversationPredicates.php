<?php

declare(strict_types=1);

namespace MockServer\Llm;

/**
 * Serialisable predicate descriptors for LLM conversation matching
 * (mirrors org.mockserver.model.ConversationPredicates).
 */
class ConversationPredicates implements \JsonSerializable
{
    private ?int $turnIndex = null;
    private ?string $latestMessageContains = null;
    private ?string $latestMessageMatches = null;
    private ?string $latestMessageRole = null;
    private ?string $containsToolResultFor = null;
    private ?string $semanticMatchAgainst = null;
    private ?NormalizationOptions $normalization = null;

    public function withTurnIndex(int $turnIndex): self
    {
        $this->turnIndex = $turnIndex;
        return $this;
    }

    public function withLatestMessageContains(string $latestMessageContains): self
    {
        $this->latestMessageContains = $latestMessageContains;
        return $this;
    }

    public function withLatestMessageMatches(string $latestMessageMatches): self
    {
        $this->latestMessageMatches = $latestMessageMatches;
        return $this;
    }

    public function withLatestMessageRole(string $latestMessageRole): self
    {
        $this->latestMessageRole = $latestMessageRole;
        return $this;
    }

    public function withContainsToolResultFor(string $containsToolResultFor): self
    {
        $this->containsToolResultFor = $containsToolResultFor;
        return $this;
    }

    public function withSemanticMatchAgainst(string $semanticMatchAgainst): self
    {
        $this->semanticMatchAgainst = $semanticMatchAgainst;
        return $this;
    }

    public function withNormalization(NormalizationOptions $normalization): self
    {
        $this->normalization = $normalization;
        return $this;
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
        $data = [];
        if ($this->turnIndex !== null) {
            $data['turnIndex'] = $this->turnIndex;
        }
        if ($this->latestMessageContains !== null) {
            $data['latestMessageContains'] = $this->latestMessageContains;
        }
        if ($this->latestMessageMatches !== null) {
            $data['latestMessageMatches'] = $this->latestMessageMatches;
        }
        if ($this->latestMessageRole !== null) {
            $data['latestMessageRole'] = $this->latestMessageRole;
        }
        if ($this->containsToolResultFor !== null) {
            $data['containsToolResultFor'] = $this->containsToolResultFor;
        }
        if ($this->semanticMatchAgainst !== null) {
            $data['semanticMatchAgainst'] = $this->semanticMatchAgainst;
        }
        if ($this->normalization !== null) {
            $data['normalization'] = $this->normalization->toArray();
        }
        return $data;
    }
}
