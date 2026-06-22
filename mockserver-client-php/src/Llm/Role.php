<?php

declare(strict_types=1);

namespace MockServer\Llm;

/**
 * Parsed-message roles (mirrors org.mockserver.llm.ParsedMessage.Role).
 */
final class Role
{
    public const USER = 'USER';
    public const ASSISTANT = 'ASSISTANT';
    public const TOOL = 'TOOL';
    public const SYSTEM = 'SYSTEM';

    private function __construct()
    {
    }
}
