import { describe, it, expect } from 'vitest';
import {
  extractExpectationFromCapture,
  extractGenericExpectationFromCapture,
  isLlmTraffic,
  isCapturableTraffic,
  type LlmExpectationDraft,
  type GenericExpectationDraft,
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

describe('isCapturableTraffic', () => {
  it('returns true for LLM and generic kinds', () => {
    const kinds: Array<'anthropic' | 'openai' | 'openai_responses' | 'gemini' | 'ollama' | 'generic'> = [
      'anthropic', 'openai', 'openai_responses', 'gemini', 'ollama', 'generic',
    ];
    for (const kind of kinds) {
      expect(isCapturableTraffic({ kind } as never)).toBe(true);
    }
  });

  it('returns false for mcp kind', () => {
    expect(isCapturableTraffic({ kind: 'mcp' } as McpParsed)).toBe(false);
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

    const draft = extractExpectationFromCapture(parsed, '/v1/messages');

    expect(draft.kind).toBe('llm');
    const llm = draft as LlmExpectationDraft;
    expect(llm.provider).toBe('ANTHROPIC');
    expect(llm.path).toBe('/v1/messages');
    expect(llm.model).toBe('claude-sonnet-4-20250514');
    expect(llm.text).toBe('Hello world');
    expect(llm.toolCalls).toEqual([
      { name: 'search', arguments: '{"query":"test"}' },
    ]);
    expect(llm.stopReason).toBe('end_turn');
    expect(llm.streaming).toBe(false);
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

    expect(draft.kind).toBe('llm');
    const llm = draft as LlmExpectationDraft;
    expect(llm.provider).toBe('OPENAI');
    expect(llm.path).toBe('/chat/completions');
    expect(llm.model).toBe('gpt-4o');
    expect(llm.text).toBe('The answer is 42.');
    expect(llm.toolCalls).toEqual([
      { name: 'calculate', arguments: '{"x":1}' },
    ]);
    expect(llm.stopReason).toBe('stop');
    expect(llm.streaming).toBe(true);
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

    expect(draft.kind).toBe('llm');
    const llm = draft as LlmExpectationDraft;
    expect(llm.provider).toBe('OPENAI_RESPONSES');
    expect(llm.text).toBe('Response text');
    expect(llm.toolCalls).toEqual([{ name: 'my_func', arguments: '{}' }]);
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

    expect(draft.kind).toBe('llm');
    const llm = draft as LlmExpectationDraft;
    expect(llm.provider).toBe('GEMINI');
    expect(llm.model).toBe('gemini-2.0-flash');
    expect(llm.text).toBe('Gemini response');
    expect(llm.toolCalls).toEqual([
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

    expect(draft.kind).toBe('llm');
    const llm = draft as LlmExpectationDraft;
    expect(llm.provider).toBe('OLLAMA');
    expect(llm.model).toBe('llama3');
    expect(llm.text).toBe('Ollama says hello');
    expect(llm.toolCalls).toEqual([
      { name: 'search', arguments: '{"q":"test"}' },
    ]);
  });

  it('produces a generic draft for non-LLM traffic', () => {
    const parsed: GenericParsed = {
      kind: 'generic',
      method: 'GET',
      path: '/health',
      statusCode: 200,
    };

    const draft = extractExpectationFromCapture(parsed, '/health', {
      httpRequest: { method: 'GET', path: '/health' },
      httpResponse: { statusCode: 200, body: '{"status":"ok"}' },
    });

    expect(draft.kind).toBe('generic');
    const gen = draft as GenericExpectationDraft;
    expect(gen.method).toBe('GET');
    expect(gen.path).toBe('/health');
    expect(gen.responseStatusCode).toBe(200);
    expect(gen.matcherPrecision).toBe('moderate');
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

    expect(draft.kind).toBe('llm');
    const llm = draft as LlmExpectationDraft;
    expect(llm.model).toBe('');
    expect(llm.text).toBe('');
    expect(llm.stopReason).toBe('');
  });
});

describe('extractGenericExpectationFromCapture', () => {
  it('extracts method, path, query, headers, body from captured request', () => {
    const itemValue = {
      httpRequest: {
        method: 'POST',
        path: '/api/users',
        queryStringParameters: [
          { name: 'page', values: ['1'] },
        ],
        headers: [
          { name: 'Content-Type', values: ['application/json'] },
          { name: 'Authorization', values: ['Bearer tok'] },
          { name: 'Host', values: ['example.com'] }, // should be filtered
        ],
        body: { type: 'JSON', json: '{"name":"Alice"}' },
      },
      httpResponse: {
        statusCode: 201,
        headers: [
          { name: 'Content-Type', values: ['application/json'] },
        ],
        body: { type: 'JSON', json: '{"id":42}' },
      },
    };

    const draft = extractGenericExpectationFromCapture(itemValue);

    expect(draft.kind).toBe('generic');
    expect(draft.method).toBe('POST');
    expect(draft.path).toBe('/api/users');
    expect(draft.queryStringParameters).toEqual([{ name: 'page', values: ['1'] }]);
    // Host header should be excluded
    expect(draft.headers.find((h) => h.name === 'Host')).toBeUndefined();
    expect(draft.headers.find((h) => h.name === 'Authorization')).toBeDefined();
    expect(draft.body).toBe('{"name":"Alice"}');
    expect(draft.responseStatusCode).toBe(201);
    expect(draft.responseBody).toBe('{"id":42}');
    expect(draft.matcherPrecision).toBe('moderate');
  });

  it('respects custom default precision', () => {
    const draft = extractGenericExpectationFromCapture({}, 'loose');
    expect(draft.matcherPrecision).toBe('loose');
  });

  it('handles missing httpRequest/httpResponse gracefully', () => {
    const draft = extractGenericExpectationFromCapture({});
    expect(draft.method).toBe('GET');
    expect(draft.path).toBe('/');
    expect(draft.responseStatusCode).toBe(200);
  });

  it('extracts BINARY body by decoding base64Bytes to text', () => {
    const itemValue = {
      httpRequest: {
        method: 'POST',
        path: '/upload',
        body: { type: 'BINARY', base64Bytes: btoa('binary payload') },
      },
      httpResponse: {
        statusCode: 200,
        body: { type: 'BINARY', base64Bytes: btoa('response bytes') },
      },
    };

    const draft = extractGenericExpectationFromCapture(itemValue);
    expect(draft.body).toBe('binary payload');
    expect(draft.responseBody).toBe('response bytes');
  });

  it('falls back to raw base64 string when BINARY decode fails', () => {
    // \xff\xfe is not valid UTF-8 but atob should still work; however,
    // we want to test the catch path, so use non-base64 that atob rejects
    const itemValue = {
      httpRequest: {
        method: 'POST',
        path: '/upload',
        body: { type: 'BINARY', base64Bytes: '!!!not-base64!!!' },
      },
      httpResponse: { statusCode: 200 },
    };

    const draft = extractGenericExpectationFromCapture(itemValue);
    expect(draft.body).toBe('!!!not-base64!!!');
  });

  it('extracts XML body from { type: "XML", xml: "..." } wrapper', () => {
    const xmlContent = '<root><item id="1">Hello</item></root>';
    const itemValue = {
      httpRequest: {
        method: 'POST',
        path: '/soap',
        body: { type: 'XML', xml: xmlContent },
      },
      httpResponse: {
        statusCode: 200,
        body: { type: 'XML', xml: '<response>ok</response>' },
      },
    };

    const draft = extractGenericExpectationFromCapture(itemValue);
    expect(draft.body).toBe(xmlContent);
    expect(draft.responseBody).toBe('<response>ok</response>');
  });
});
