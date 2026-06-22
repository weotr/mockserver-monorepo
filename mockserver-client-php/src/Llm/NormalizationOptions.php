<?php

declare(strict_types=1);

namespace MockServer\Llm;

/**
 * Opt-in prompt normalisation applied before text predicates
 * (mirrors org.mockserver.model.NormalizationOptions).
 */
class NormalizationOptions implements \JsonSerializable
{
    private ?bool $collapseWhitespace = null;
    private ?bool $lowercase = null;
    private ?bool $sortJsonKeys = null;
    private ?bool $dropBuiltInVolatileFields = null;
    /** @var list<string>|null */
    private ?array $dropVolatileFields = null;

    public static function normalization(): self
    {
        return new self();
    }

    public function withCollapseWhitespace(bool $collapseWhitespace): self
    {
        $this->collapseWhitespace = $collapseWhitespace;
        return $this;
    }

    public function withLowercase(bool $lowercase): self
    {
        $this->lowercase = $lowercase;
        return $this;
    }

    public function withSortJsonKeys(bool $sortJsonKeys): self
    {
        $this->sortJsonKeys = $sortJsonKeys;
        return $this;
    }

    public function withDropBuiltInVolatileFields(bool $dropBuiltInVolatileFields): self
    {
        $this->dropBuiltInVolatileFields = $dropBuiltInVolatileFields;
        return $this;
    }

    /**
     * @param list<string> $dropVolatileFields
     */
    public function withDropVolatileFields(array $dropVolatileFields): self
    {
        $this->dropVolatileFields = $dropVolatileFields;
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
        $data = [];
        if ($this->collapseWhitespace !== null) {
            $data['collapseWhitespace'] = $this->collapseWhitespace;
        }
        if ($this->lowercase !== null) {
            $data['lowercase'] = $this->lowercase;
        }
        if ($this->sortJsonKeys !== null) {
            $data['sortJsonKeys'] = $this->sortJsonKeys;
        }
        if ($this->dropBuiltInVolatileFields !== null) {
            $data['dropBuiltInVolatileFields'] = $this->dropBuiltInVolatileFields;
        }
        if ($this->dropVolatileFields !== null) {
            $data['dropVolatileFields'] = $this->dropVolatileFields;
        }
        return $data;
    }
}
