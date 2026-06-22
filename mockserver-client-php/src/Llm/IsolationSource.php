<?php

declare(strict_types=1);

namespace MockServer\Llm;

use InvalidArgumentException;

/**
 * Where to read the per-session isolation key from an inbound request
 * (mirrors org.mockserver.llm.IsolationSource).
 *
 * Encodes as {@code "<kind>:<name>"} (e.g. {@code "header:x-session-id"}).
 */
class IsolationSource
{
    private string $kind;
    private string $name;

    private function __construct(string $kind, string $name)
    {
        if ($name === '') {
            throw new InvalidArgumentException('name must not be empty');
        }
        $this->kind = $kind;
        $this->name = $name;
    }

    public static function header(string $name): self
    {
        return new self('header', $name);
    }

    public static function queryParameter(string $name): self
    {
        return new self('query_parameter', $name);
    }

    public static function cookie(string $name): self
    {
        return new self('cookie', $name);
    }

    public function encode(): string
    {
        return $this->kind . ':' . $this->name;
    }
}
