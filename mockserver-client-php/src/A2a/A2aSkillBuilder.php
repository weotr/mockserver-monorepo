<?php

declare(strict_types=1);

namespace MockServer\A2a;

/**
 * Fluent sub-builder for an A2A agent skill (mirrors the Java
 * {@code A2aMockBuilder.A2aSkillBuilder}). Terminated with {@see and()} which
 * pushes the skill onto the parent agent card.
 */
class A2aSkillBuilder
{
    private A2aMockBuilder $parent;
    public string $id;
    public ?string $name = null;
    public ?string $description = null;
    /** @var list<string> */
    public array $tags = [];
    /** @var list<string> */
    public array $examples = [];

    public function __construct(A2aMockBuilder $parent, string $id)
    {
        $this->parent = $parent;
        $this->id = $id;
    }

    public function withName(string $name): self
    {
        $this->name = $name;
        return $this;
    }

    public function withDescription(string $description): self
    {
        $this->description = $description;
        return $this;
    }

    public function withTag(string $tag): self
    {
        $this->tags[] = $tag;
        return $this;
    }

    public function withExample(string $example): self
    {
        $this->examples[] = $example;
        return $this;
    }

    public function and(): A2aMockBuilder
    {
        return $this->parent->addSkill($this);
    }
}
