<?php

declare(strict_types=1);

namespace MockServer\Llm;

/**
 * LLM provider names. Serialized on the wire as the upper-case enum name
 * (mirrors org.mockserver.model.Provider).
 */
final class Provider
{
    public const ANTHROPIC = 'ANTHROPIC';
    public const OPENAI = 'OPENAI';
    public const OPENAI_RESPONSES = 'OPENAI_RESPONSES';
    public const GEMINI = 'GEMINI';
    public const BEDROCK = 'BEDROCK';
    public const AZURE_OPENAI = 'AZURE_OPENAI';
    public const OLLAMA = 'OLLAMA';

    private function __construct()
    {
    }
}
