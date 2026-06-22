<?php

declare(strict_types=1);

namespace MockServer\Llm;

/**
 * A single tool/function call emitted by the assistant
 * (mirrors org.mockserver.model.ToolUse).
 *
 * @example
 *   ToolUse::toolUse('get_weather')
 *       ->withId('call_1')
 *       ->withArguments('{"city":"London"}');
 */
class ToolUse implements \JsonSerializable
{
    private ?string $id = null;
    private ?string $name;
    private ?string $arguments = null;

    public function __construct(?string $name = null)
    {
        $this->name = $name;
    }

    /**
     * Static factory mirroring {@code ToolUse.toolUse(name)}.
     */
    public static function toolUse(string $name): self
    {
        return new self($name);
    }

    public function withId(string $id): self
    {
        $this->id = $id;
        return $this;
    }

    public function withName(string $name): self
    {
        $this->name = $name;
        return $this;
    }

    /**
     * Set the tool-call arguments.
     *
     * @param array<mixed>|string $arguments JSON string (matching the Java API)
     *                                        or an array serialised to JSON.
     */
    public function withArguments(array|string $arguments): self
    {
        $this->arguments = is_array($arguments)
            ? json_encode($arguments, JSON_THROW_ON_ERROR)
            : $arguments;
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
        if ($this->id !== null) {
            $data['id'] = $this->id;
        }
        if ($this->name !== null) {
            $data['name'] = $this->name;
        }
        if ($this->arguments !== null) {
            $data['arguments'] = $this->arguments;
        }
        return $data;
    }
}
