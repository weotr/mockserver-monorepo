<?php

declare(strict_types=1);

namespace MockServer\A2a;

/**
 * Fluent sub-builder for a custom {@code tasks/send} handler matched by a regular
 * expression against {@code params.message.parts[0].text} (mirrors the Java
 * {@code A2aMockBuilder.A2aTaskHandlerBuilder}). Terminated with {@see and()}.
 */
class A2aTaskHandlerBuilder
{
    private A2aMockBuilder $parent;
    public string $messagePattern = '.*';
    public string $responseText = 'Task completed';
    public bool $isError = false;

    public function __construct(A2aMockBuilder $parent)
    {
        $this->parent = $parent;
    }

    public function matchingMessage(string $pattern): self
    {
        $this->messagePattern = $pattern;
        return $this;
    }

    public function respondingWith(string $text, bool $isError = false): self
    {
        $this->responseText = $text;
        $this->isError = $isError;
        return $this;
    }

    public function and(): A2aMockBuilder
    {
        return $this->parent->addTaskHandler($this);
    }
}
