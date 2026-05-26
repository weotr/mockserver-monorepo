import { describe, it, expect } from 'vitest';
import {
  extractExpectationFromCapture,
  isLlmTraffic,
  type ExpectationDraft,
} from '../lib/expectationFromCapture';
import type {
  AnthropicParsed,
  OpenAiParsed,
  OpenAiResponsesParsed,
  GeminiParsed,
  OllamaParsed,
  GenericParsed,
  McpParsed,
} from '../lib/llmTraffic';

describe('isLlmTraffic', () => {
  it('returns true for all LLM provider kinds', () => {
    const kinds: Array<'anthropic' | 'openai' | 'openai_responses' | 'gemini' | 'ollama'> = [
      'anthropic',
      'openai',
      'openai_responses',
      'gemini',
      'ollama',
    ];
    for (const kind of kinds) {
      expect(isLlmTraffic({ kind } as never)).toBe(true);
    }
  });

  it('returns false for generic and mcp kinds', () => {
    expect(isLlmTraffic({ kind: 'generic' } as GenericParsed)).toBe(false);
    expect(isLlmTraffic({ kind: 'mcp' } as McpParsed)).toBe(false);
  });
});

describe('extractExpectationFromCapture', () => {
  it('extracts from Anthropic traffic', () => {
    const parsed: AnthropicParsed = {
      kind: 'anthropic',
      model: 'claude-sonnet-4-20250514',
      stream: false,
      messages: [],
      system: null,
      tools: null,
      maxTokens: null,
      responseContent: [
        { type: 'text', text: 'Hello world' },
        { type: 'tool_use', name: 'search', input: { query: 'test' } },
      ],
      usage: null,
      stopReason: 'end_turn',
      sseEvents: null,
      streamed: false,
      streamTruncated: false,
    };

    const draft: ExpectationDraft = extractExpectationFromCapture(parsed, '/v1/messages');

    expect(draft.provider).toBe('ANTHROPIC');
    expect(draft.path).toBe('/v1/messages');
    expect(draft.model).toBe('claude-sonnet-4-20250514');
    expect(draft.text).toBe('Hello world');
    expect(draft.toolCalls).toEqual([
      { name: 'search', arguments: '{"query":"test"}' },
    ]);
    expect(draft.stopReason).toBe('end_turn');
    expect(draft.streaming).toBe(false);
  });

  it('extracts from OpenAI traffic', () => {
    const parsed: OpenAiParsed = {
      kind: 'openai',
      model: 'gpt-4o',
      stream: true,
      messages: [],
      tools: null,
      choices: [
        {
          message: {
            role: 'assistant',
            content: 'The answer is 42.',
            tool_calls: [
              { function: { name: 'calculate', arguments: '{"x":1}' } },
            ],
          },
          finish_reason: 'stop',
        },
      ],
      usage: null,
      sseEvents: null,
      streamed: false,
      streamTruncated: false,
    };

    const draft = extractExpectationFromCapture(parsed, '/chat/completions');

    expect(draft.provider).toBe('OPENAI');
    expect(draft.path).toBe('/chat/completions');
    expect(draft.model).toBe('gpt-4o');
    expect(draft.text).toBe('The answer is 42.');
    expect(draft.toolCalls).toEqual([
      { name: 'calculate', arguments: '{"x":1}' },
    ]);
    expect(draft.stopReason).toBe('stop');
    expect(draft.streaming).toBe(true);
  });

  it('extracts from OpenAI Responses API traffic', () => {
    const parsed: OpenAiResponsesParsed = {
      kind: 'openai_responses',
      model: 'gpt-4o',
      stream: false,
      input: [],
      tools: null,
      output: [
        {
          type: 'message',
          content: [{ type: 'output_text', text: 'Response text' }],
        },
        { type: 'function_call', name: 'my_func', arguments: '{}' },
      ],
      usage: null,
      sseEvents: null,
      streamed: false,
      streamTruncated: false,
    };

    const draft = extractExpectationFromCapture(parsed, '/v1/responses');

    expect(draft.provider).toBe('OPENAI_RESPONSES');
    expect(draft.text).toBe('Response text');
    expect(draft.toolCalls).toEqual([{ name: 'my_func', arguments: '{}' }]);
  });

  it('extracts from Gemini traffic', () => {
    const parsed: GeminiParsed = {
      kind: 'gemini',
      model: 'gemini-2.0-flash',
      stream: false,
      contents: [],
      tools: null,
      candidates: [
        {
          content: {
            parts: [
              { text: 'Gemini response' },
              { functionCall: { name: 'tool1', args: { a: 1 } } },
            ],
          },
        },
      ],
      usage: null,
      sseEvents: null,
      streamed: false,
      streamTruncated: false,
    };

    const draft = extractExpectationFromCapture(parsed, '/v1beta/models/gemini-2.0-flash:generateContent');

    expect(draft.provider).toBe('GEMINI');
    expect(draft.model).toBe('gemini-2.0-flash');
    expect(draft.text).toBe('Gemini response');
    expect(draft.toolCalls).toEqual([
      { name: 'tool1', arguments: '{"a":1}' },
    ]);
  });

  it('extracts from Ollama traffic', () => {
    const parsed: OllamaParsed = {
      kind: 'ollama',
      model: 'llama3',
      stream: false,
      messages: [],
      tools: null,
      responseMessage: {
        role: 'assistant',
        content: 'Ollama says hello',
        tool_calls: [
          { function: { name: 'search', arguments: '{"q":"test"}' } },
        ],
      },
      done: true,
      usage: null,
      sseEvents: null,
      streamed: false,
      streamTruncated: false,
    };

    const draft = extractExpectationFromCapture(parsed, '/api/chat');

    expect(draft.provider).toBe('OLLAMA');
    expect(draft.model).toBe('llama3');
    expect(draft.text).toBe('Ollama says hello');
    expect(draft.toolCalls).toEqual([
      { name: 'search', arguments: '{"q":"test"}' },
    ]);
  });

  it('handles generic traffic gracefully', () => {
    const parsed: GenericParsed = {
      kind: 'generic',
      method: 'GET',
      path: '/health',
      statusCode: 200,
    };

    const draft = extractExpectationFromCapture(parsed, '/health');

    expect(draft.provider).toBe('ANTHROPIC'); // fallback
    expect(draft.text).toBe('');
    expect(draft.toolCalls).toEqual([]);
  });

  it('handles null model and missing data', () => {
    const parsed: AnthropicParsed = {
      kind: 'anthropic',
      model: null,
      stream: false,
      messages: [],
      system: null,
      tools: null,
      maxTokens: null,
      responseContent: [],
      usage: null,
      stopReason: null,
      sseEvents: null,
      streamed: false,
      streamTruncated: false,
    };

    const draft = extractExpectationFromCapture(parsed, '/v1/messages');

    expect(draft.model).toBe('');
    expect(draft.text).toBe('');
    expect(draft.stopReason).toBe('');
  });
});
