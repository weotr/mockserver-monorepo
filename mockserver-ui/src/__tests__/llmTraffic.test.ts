import { describe, it, expect } from 'vitest';
import {
  parseTraffic,
  parseSseStream,
  summarizeTraffic,
  getModelLabel,
  getTokenSummary,
  getTimingLabel,
  getTimingBreakdown,
  extractBodyContent,
} from '../lib/llmTraffic';

// ---------------------------------------------------------------------------
// Anthropic non-streaming
// ---------------------------------------------------------------------------

describe('parseTraffic — Anthropic non-streaming', () => {
  it('parses a standard Anthropic Messages API request/response', () => {
    const value = {
      httpRequest: {
        method: 'POST',
        path: '/v1/messages',
        headers: [{ name: 'host', values: ['api.anthropic.com'] }],
        body: {
          type: 'JSON',
          json: JSON.stringify({
            model: 'claude-sonnet-4-20250514',
            max_tokens: 1024,
            stream: false,
            messages: [{ role: 'user', content: 'Hello' }],
            system: 'You are helpful.',
            tools: [{ name: 'get_weather', description: 'Get weather' }],
          }),
        },
      },
      httpResponse: {
        statusCode: 200,
        body: {
          type: 'JSON',
          json: JSON.stringify({
            model: 'claude-sonnet-4-20250514',
            content: [{ type: 'text', text: 'Hi there!' }],
            usage: { input_tokens: 10, output_tokens: 5 },
            stop_reason: 'end_turn',
          }),
        },
      },
    };

    const parsed = parseTraffic(value);
    expect(parsed.kind).toBe('anthropic');
    if (parsed.kind !== 'anthropic') return;

    expect(parsed.model).toBe('claude-sonnet-4-20250514');
    expect(parsed.stream).toBe(false);
    expect(parsed.messages).toHaveLength(1);
    expect(parsed.system).toBe('You are helpful.');
    expect(parsed.tools).toHaveLength(1);
    expect(parsed.maxTokens).toBe(1024);
    expect(parsed.responseContent).toHaveLength(1);
    expect(parsed.responseContent[0]!.text).toBe('Hi there!');
    expect(parsed.usage).toEqual({ input_tokens: 10, output_tokens: 5 });
    expect(parsed.stopReason).toBe('end_turn');
    expect(parsed.sseEvents).toBeNull();
  });

  it('handles already-parsed JSON body objects', () => {
    const value = {
      httpRequest: {
        method: 'POST',
        path: '/v1/messages',
        body: {
          model: 'claude-sonnet-4-20250514',
          messages: [{ role: 'user', content: 'Test' }],
        },
      },
      httpResponse: {
        statusCode: 200,
        body: {
          content: [{ type: 'text', text: 'Response' }],
          usage: { input_tokens: 5, output_tokens: 3 },
          stop_reason: 'end_turn',
        },
      },
    };

    const parsed = parseTraffic(value);
    expect(parsed.kind).toBe('anthropic');
    if (parsed.kind !== 'anthropic') return;

    expect(parsed.model).toBe('claude-sonnet-4-20250514');
    expect(parsed.responseContent).toHaveLength(1);
  });
});

// ---------------------------------------------------------------------------
// Anthropic streaming (SSE)
// ---------------------------------------------------------------------------

describe('parseTraffic — Anthropic streaming SSE', () => {
  const sseBody = [
    'event: message_start',
    'data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","content":[],"model":"claude-sonnet-4-20250514","stop_reason":null,"usage":{"input_tokens":25}}}',
    '',
    'event: content_block_start',
    'data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}',
    '',
    'event: content_block_delta',
    'data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}',
    '',
    'event: content_block_delta',
    'data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" world"}}',
    '',
    'event: content_block_stop',
    'data: {"type":"content_block_stop","index":0}',
    '',
    'event: message_delta',
    'data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":12}}',
    '',
    'event: message_stop',
    'data: {"type":"message_stop"}',
    '',
  ].join('\n');

  it('reassembles streamed Anthropic response from SSE events', () => {
    const value = {
      httpRequest: {
        method: 'POST',
        path: '/v1/messages',
        body: {
          type: 'JSON',
          json: JSON.stringify({
            model: 'claude-sonnet-4-20250514',
            stream: true,
            messages: [{ role: 'user', content: 'Hi' }],
          }),
        },
      },
      httpResponse: {
        statusCode: 200,
        headers: [{ name: 'content-type', values: ['text/event-stream'] }],
        body: { type: 'STRING', string: sseBody },
      },
    };

    const parsed = parseTraffic(value);
    expect(parsed.kind).toBe('anthropic');
    if (parsed.kind !== 'anthropic') return;

    expect(parsed.stream).toBe(true);
    expect(parsed.model).toBe('claude-sonnet-4-20250514');
    expect(parsed.sseEvents).not.toBeNull();
    expect(parsed.sseEvents!.length).toBeGreaterThan(0);
    expect(parsed.responseContent).toHaveLength(1);
    expect(parsed.responseContent[0]!.text).toBe('Hello world');
    expect(parsed.usage).toEqual({ input_tokens: 25, output_tokens: 12 });
    expect(parsed.stopReason).toBe('end_turn');
  });

  it('handles tool_use streaming blocks', () => {
    const toolSse = [
      'event: message_start',
      'data: {"type":"message_start","message":{"id":"msg_2","type":"message","role":"assistant","content":[],"model":"claude-sonnet-4-20250514","stop_reason":null,"usage":{"input_tokens":50}}}',
      '',
      'event: content_block_start',
      'data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"get_weather"}}',
      '',
      'event: content_block_delta',
      'data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"city\\":"}}',
      '',
      'event: content_block_delta',
      'data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"\\"London\\"}"}}',
      '',
      'event: content_block_stop',
      'data: {"type":"content_block_stop","index":0}',
      '',
      'event: message_delta',
      'data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":30}}',
      '',
    ].join('\n');

    const value = {
      httpRequest: {
        method: 'POST',
        path: '/v1/messages',
        body: { type: 'JSON', json: JSON.stringify({ model: 'claude-sonnet-4-20250514', stream: true, messages: [] }) },
      },
      httpResponse: {
        statusCode: 200,
        headers: [{ name: 'content-type', values: ['text/event-stream'] }],
        body: { type: 'STRING', string: toolSse },
      },
    };

    const parsed = parseTraffic(value);
    expect(parsed.kind).toBe('anthropic');
    if (parsed.kind !== 'anthropic') return;

    expect(parsed.responseContent).toHaveLength(1);
    expect(parsed.responseContent[0]!.type).toBe('tool_use');
    expect(parsed.responseContent[0]!.name).toBe('get_weather');
    expect(parsed.responseContent[0]!.input).toEqual({ city: 'London' });
    expect(parsed.stopReason).toBe('tool_use');
  });

  it('detects x-mockserver-streamed and x-mockserver-stream-truncated headers', () => {
    const value = {
      httpRequest: {
        method: 'POST',
        path: '/v1/messages',
        body: { type: 'JSON', json: '{"model":"claude-sonnet-4-20250514","messages":[]}' },
      },
      httpResponse: {
        statusCode: 200,
        headers: [
          { name: 'content-type', values: ['text/event-stream'] },
          { name: 'x-mockserver-streamed', values: ['true'] },
          { name: 'x-mockserver-stream-truncated', values: ['true'] },
        ],
        body: { type: 'STRING', string: 'event: message_start\ndata: {"type":"message_start","message":{"model":"claude-sonnet-4-20250514","content":[],"usage":{"input_tokens":10}}}\n\n' },
      },
    };

    const parsed = parseTraffic(value);
    expect(parsed.kind).toBe('anthropic');
    if (parsed.kind !== 'anthropic') return;
    expect(parsed.streamed).toBe(true);
    expect(parsed.streamTruncated).toBe(true);
  });
});

// ---------------------------------------------------------------------------
// OpenAI non-streaming
// ---------------------------------------------------------------------------

describe('parseTraffic — OpenAI non-streaming', () => {
  it('parses a standard OpenAI Chat Completions request/response', () => {
    const value = {
      httpRequest: {
        method: 'POST',
        path: '/v1/chat/completions',
        headers: [{ name: 'host', values: ['api.openai.com'] }],
        body: {
          type: 'JSON',
          json: JSON.stringify({
            model: 'gpt-4',
            messages: [{ role: 'user', content: 'Hello' }],
            tools: [{ type: 'function', function: { name: 'search' } }],
          }),
        },
      },
      httpResponse: {
        statusCode: 200,
        body: {
          type: 'JSON',
          json: JSON.stringify({
            model: 'gpt-4',
            choices: [
              {
                message: { role: 'assistant', content: 'Hi!' },
                finish_reason: 'stop',
              },
            ],
            usage: { prompt_tokens: 8, completion_tokens: 3, total_tokens: 11 },
          }),
        },
      },
    };

    const parsed = parseTraffic(value);
    expect(parsed.kind).toBe('openai');
    if (parsed.kind !== 'openai') return;

    expect(parsed.model).toBe('gpt-4');
    expect(parsed.messages).toHaveLength(1);
    expect(parsed.tools).toHaveLength(1);
    expect(parsed.choices).toHaveLength(1);
    expect(parsed.choices[0]!.message?.content).toBe('Hi!');
    expect(parsed.usage).toEqual({ prompt_tokens: 8, completion_tokens: 3, total_tokens: 11 });
  });
});

// ---------------------------------------------------------------------------
// OpenAI streaming
// ---------------------------------------------------------------------------

describe('parseTraffic — OpenAI streaming SSE', () => {
  it('reassembles streamed OpenAI response from SSE events', () => {
    const sseBody = [
      'data: {"id":"chatcmpl-1","object":"chat.completion.chunk","model":"gpt-4","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}',
      '',
      'data: {"id":"chatcmpl-1","object":"chat.completion.chunk","model":"gpt-4","choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":null}]}',
      '',
      'data: {"id":"chatcmpl-1","object":"chat.completion.chunk","model":"gpt-4","choices":[{"index":0,"delta":{"content":" world"},"finish_reason":null}]}',
      '',
      'data: {"id":"chatcmpl-1","object":"chat.completion.chunk","model":"gpt-4","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}',
      '',
      'data: [DONE]',
      '',
    ].join('\n');

    const value = {
      httpRequest: {
        method: 'POST',
        path: '/v1/chat/completions',
        body: {
          type: 'JSON',
          json: JSON.stringify({ model: 'gpt-4', stream: true, messages: [{ role: 'user', content: 'Hi' }] }),
        },
      },
      httpResponse: {
        statusCode: 200,
        headers: [{ name: 'content-type', values: ['text/event-stream'] }],
        body: { type: 'STRING', string: sseBody },
      },
    };

    const parsed = parseTraffic(value);
    expect(parsed.kind).toBe('openai');
    if (parsed.kind !== 'openai') return;

    expect(parsed.model).toBe('gpt-4');
    expect(parsed.sseEvents).not.toBeNull();
    expect(parsed.choices).toHaveLength(1);
    expect(parsed.choices[0]!.message?.content).toBe('Hello world');
    expect(parsed.choices[0]!.finish_reason).toBe('stop');
  });
});

// ---------------------------------------------------------------------------
// MCP JSON-RPC
// ---------------------------------------------------------------------------

describe('parseTraffic — MCP JSON-RPC', () => {
  it('detects MCP request with method and params', () => {
    const value = {
      httpRequest: {
        method: 'POST',
        path: '/mcp',
        body: {
          type: 'JSON',
          json: JSON.stringify({
            jsonrpc: '2.0',
            method: 'tools/list',
            id: 1,
            params: {},
          }),
        },
      },
      httpResponse: {
        statusCode: 200,
        body: {
          type: 'JSON',
          json: JSON.stringify({
            jsonrpc: '2.0',
            id: 1,
            result: { tools: [{ name: 'read_file' }] },
          }),
        },
      },
    };

    const parsed = parseTraffic(value);
    expect(parsed.kind).toBe('mcp');
    if (parsed.kind !== 'mcp') return;

    expect(parsed.method).toBe('tools/list');
    expect(parsed.id).toBe(1);
    expect(parsed.params).toEqual({});
    expect(parsed.result).toEqual({ tools: [{ name: 'read_file' }] });
  });

  it('detects MCP JSON-RPC even on generic path', () => {
    const value = {
      httpRequest: {
        method: 'POST',
        path: '/api/rpc',
        body: JSON.stringify({
          jsonrpc: '2.0',
          method: 'resources/read',
          id: 42,
          params: { uri: 'file:///test.txt' },
        }),
      },
      httpResponse: {
        statusCode: 200,
        body: JSON.stringify({
          jsonrpc: '2.0',
          id: 42,
          result: { contents: [{ text: 'hello' }] },
        }),
      },
    };

    const parsed = parseTraffic(value);
    expect(parsed.kind).toBe('mcp');
    if (parsed.kind !== 'mcp') return;
    expect(parsed.method).toBe('resources/read');
  });
});

// ---------------------------------------------------------------------------
// Generic / fallback
// ---------------------------------------------------------------------------

describe('parseTraffic — generic fallback', () => {
  it('returns generic for unrecognized requests', () => {
    const value = {
      httpRequest: {
        method: 'GET',
        path: '/api/health',
      },
      httpResponse: {
        statusCode: 200,
      },
    };

    const parsed = parseTraffic(value);
    expect(parsed.kind).toBe('generic');
    if (parsed.kind !== 'generic') return;
    expect(parsed.method).toBe('GET');
    expect(parsed.path).toBe('/api/health');
    expect(parsed.statusCode).toBe(200);
  });

  it('handles completely empty value gracefully', () => {
    const parsed = parseTraffic({});
    expect(parsed.kind).toBe('generic');
  });

  it('handles null-ish bodies without throwing', () => {
    const value = {
      httpRequest: { method: 'POST', path: '/v1/messages', body: null },
      httpResponse: { statusCode: 500, body: null },
    };
    const parsed = parseTraffic(value);
    expect(parsed.kind).toBe('anthropic');
    if (parsed.kind !== 'anthropic') return;
    expect(parsed.model).toBeNull();
    expect(parsed.messages).toEqual([]);
  });

  it('handles malformed JSON string bodies without throwing', () => {
    const value = {
      httpRequest: {
        method: 'POST',
        path: '/v1/messages',
        body: { type: 'STRING', string: 'this is not json {{{' },
      },
      httpResponse: {
        statusCode: 200,
        body: { type: 'STRING', string: 'also not json' },
      },
    };
    const parsed = parseTraffic(value);
    expect(parsed.kind).toBe('anthropic');
    if (parsed.kind !== 'anthropic') return;
    expect(parsed.model).toBeNull();
  });
});

// ---------------------------------------------------------------------------
// parseSseStream
// ---------------------------------------------------------------------------

describe('parseSseStream', () => {
  it('parses well-formed SSE with event and data lines', () => {
    const text = 'event: ping\ndata: {"type":"ping"}\n\nevent: message\ndata: {"text":"hi"}\n\n';
    const events = parseSseStream(text);
    expect(events).toHaveLength(2);
    expect(events[0]!.event).toBe('ping');
    expect(events[0]!.data).toBe('{"type":"ping"}');
    expect(events[1]!.event).toBe('message');
  });

  it('handles data-only events (no event: line)', () => {
    const text = 'data: {"chunk":1}\n\ndata: {"chunk":2}\n\n';
    const events = parseSseStream(text);
    expect(events).toHaveLength(2);
    expect(events[0]!.event).toBeUndefined();
    expect(events[0]!.data).toBe('{"chunk":1}');
  });

  it('handles multiline data', () => {
    const text = 'data: line1\ndata: line2\n\n';
    const events = parseSseStream(text);
    expect(events).toHaveLength(1);
    expect(events[0]!.data).toBe('line1\nline2');
  });

  it('handles trailing data without final blank line', () => {
    const text = 'data: final';
    const events = parseSseStream(text);
    expect(events).toHaveLength(1);
    expect(events[0]!.data).toBe('final');
  });

  it('returns empty array for empty string', () => {
    expect(parseSseStream('')).toEqual([]);
  });
});

// ---------------------------------------------------------------------------
// summarizeTraffic
// ---------------------------------------------------------------------------

describe('summarizeTraffic', () => {
  it('extracts host, method, path, status, and parsed kind', () => {
    const value = {
      httpRequest: {
        method: 'POST',
        path: '/v1/messages',
        headers: [{ name: 'host', values: ['api.anthropic.com'] }],
        body: { type: 'JSON', json: '{"model":"claude-sonnet-4-20250514","messages":[]}' },
      },
      httpResponse: {
        statusCode: 200,
        body: { type: 'JSON', json: '{"content":[],"usage":{"input_tokens":1,"output_tokens":1}}' },
      },
    };

    const summary = summarizeTraffic(value);
    expect(summary.host).toBe('api.anthropic.com');
    expect(summary.method).toBe('POST');
    expect(summary.path).toBe('/v1/messages');
    expect(summary.statusCode).toBe(200);
    expect(summary.parsed.kind).toBe('anthropic');
  });
});

// ---------------------------------------------------------------------------
// getModelLabel / getTokenSummary
// ---------------------------------------------------------------------------

describe('getModelLabel', () => {
  it('returns model for anthropic', () => {
    const parsed = parseTraffic({
      httpRequest: { method: 'POST', path: '/v1/messages', body: { type: 'JSON', json: '{"model":"claude-sonnet-4-20250514","messages":[]}' } },
      httpResponse: { statusCode: 200, body: { type: 'JSON', json: '{"content":[],"usage":{}}' } },
    });
    expect(getModelLabel(parsed)).toBe('claude-sonnet-4-20250514');
  });

  it('returns null for generic', () => {
    const parsed = parseTraffic({ httpRequest: { method: 'GET', path: '/health' }, httpResponse: { statusCode: 200 } });
    expect(getModelLabel(parsed)).toBeNull();
  });
});

describe('getTokenSummary', () => {
  it('formats Anthropic token usage', () => {
    const parsed = parseTraffic({
      httpRequest: { method: 'POST', path: '/v1/messages', body: { type: 'JSON', json: '{"model":"x","messages":[]}' } },
      httpResponse: { statusCode: 200, body: { type: 'JSON', json: '{"content":[],"usage":{"input_tokens":100,"output_tokens":50}}' } },
    });
    expect(getTokenSummary(parsed)).toBe('100 in / 50 out');
  });

  it('formats OpenAI token usage', () => {
    const parsed = parseTraffic({
      httpRequest: { method: 'POST', path: '/v1/chat/completions', body: { type: 'JSON', json: '{"model":"gpt-4","messages":[]}' } },
      httpResponse: { statusCode: 200, body: { type: 'JSON', json: '{"choices":[],"usage":{"prompt_tokens":20,"completion_tokens":10}}' } },
    });
    expect(getTokenSummary(parsed)).toBe('20 in / 10 out');
  });

  it('returns null for generic', () => {
    const parsed = parseTraffic({ httpRequest: { method: 'GET', path: '/health' }, httpResponse: { statusCode: 200 } });
    expect(getTokenSummary(parsed)).toBeNull();
  });
});

// ---------------------------------------------------------------------------
// Headers in object format (not array)
// ---------------------------------------------------------------------------

describe('parseTraffic — headers as object map', () => {
  it('detects streaming from content-type header in object format', () => {
    const sseBody = 'event: ping\ndata: {"type":"ping"}\n\n';
    const value = {
      httpRequest: {
        method: 'POST',
        path: '/v1/messages',
        body: { type: 'JSON', json: '{"model":"claude-sonnet-4-20250514","stream":true,"messages":[]}' },
      },
      httpResponse: {
        statusCode: 200,
        headers: { 'content-type': ['text/event-stream'] },
        body: { type: 'STRING', string: sseBody },
      },
    };

    const parsed = parseTraffic(value);
    expect(parsed.kind).toBe('anthropic');
    if (parsed.kind !== 'anthropic') return;
    expect(parsed.sseEvents).not.toBeNull();
  });
});

// ---------------------------------------------------------------------------
// extractBodyContent — BINARY body decoding
// ---------------------------------------------------------------------------

describe('extractBodyContent', () => {
  it('decodes a BINARY body with base64Bytes to a UTF-8 string', () => {
    // "Hello, world!" base64-encoded
    const base64 = btoa('Hello, world!');
    const body = { type: 'BINARY', base64Bytes: base64 };
    const result = extractBodyContent(body);
    expect(result).toBe('Hello, world!');
  });

  it('decodes a BINARY body containing JSON to a parseable string', () => {
    const jsonStr = '{"model":"claude-sonnet-4-20250514","content":[{"type":"text","text":"Hi"}]}';
    const base64 = btoa(jsonStr);
    const body = { type: 'BINARY', base64Bytes: base64 };
    const result = extractBodyContent(body);
    expect(result).toBe(jsonStr);
    expect(JSON.parse(result as string)).toEqual({
      model: 'claude-sonnet-4-20250514',
      content: [{ type: 'text', text: 'Hi' }],
    });
  });

  it('decodes a BINARY body containing SSE event stream text', () => {
    const sseText = 'event: message_start\ndata: {"type":"message_start"}\n\n';
    const base64 = btoa(sseText);
    const body = { type: 'BINARY', base64Bytes: base64 };
    const result = extractBodyContent(body);
    expect(result).toBe(sseText);
  });

  it('returns the original object if base64 decoding fails', () => {
    const body = { type: 'BINARY', base64Bytes: '!!!invalid-base64!!!' };
    const result = extractBodyContent(body);
    // Should fall back to returning the original object
    expect(result).toBe(body);
  });

  it('returns the original object for BINARY without base64Bytes', () => {
    const body = { type: 'BINARY' };
    const result = extractBodyContent(body);
    expect(result).toBe(body);
  });

  it('still handles STRING bodies correctly', () => {
    const body = { type: 'STRING', string: 'hello' };
    expect(extractBodyContent(body)).toBe('hello');
  });

  it('still handles JSON bodies correctly', () => {
    const body = { type: 'JSON', json: '{"key":"value"}' };
    expect(extractBodyContent(body)).toBe('{"key":"value"}');
  });

  it('passes through plain strings', () => {
    expect(extractBodyContent('plain text')).toBe('plain text');
  });

  it('passes through null and undefined', () => {
    expect(extractBodyContent(null)).toBeNull();
    expect(extractBodyContent(undefined)).toBeUndefined();
  });
});

// ---------------------------------------------------------------------------
// parseTraffic — BINARY response body integration
// ---------------------------------------------------------------------------

describe('parseTraffic — BINARY response body', () => {
  it('parses an Anthropic response with BINARY body type', () => {
    const responseJson = JSON.stringify({
      model: 'claude-sonnet-4-20250514',
      content: [{ type: 'text', text: 'Hello from binary!' }],
      usage: { input_tokens: 5, output_tokens: 4 },
      stop_reason: 'end_turn',
    });
    const value = {
      httpRequest: {
        method: 'POST',
        path: '/v1/messages',
        body: {
          type: 'JSON',
          json: JSON.stringify({
            model: 'claude-sonnet-4-20250514',
            messages: [{ role: 'user', content: 'Hi' }],
          }),
        },
      },
      httpResponse: {
        statusCode: 200,
        body: {
          type: 'BINARY',
          base64Bytes: btoa(responseJson),
        },
      },
    };

    const parsed = parseTraffic(value);
    expect(parsed.kind).toBe('anthropic');
    if (parsed.kind !== 'anthropic') return;
    expect(parsed.responseContent).toHaveLength(1);
    expect(parsed.responseContent[0]!.text).toBe('Hello from binary!');
    expect(parsed.usage).toEqual({ input_tokens: 5, output_tokens: 4 });
    expect(parsed.stopReason).toBe('end_turn');
  });
});

// ---------------------------------------------------------------------------
// OpenAI Responses API (/v1/responses)
// ---------------------------------------------------------------------------

describe('parseTraffic — OpenAI Responses API', () => {
  it('parses a standard /v1/responses request/response', () => {
    const value = {
      httpRequest: {
        method: 'POST',
        path: '/v1/responses',
        body: {
          type: 'JSON',
          json: JSON.stringify({
            model: 'gpt-4.1',
            input: [
              { role: 'user', content: 'What is 2+2?' },
            ],
            tools: [{ type: 'function', name: 'calculator' }],
          }),
        },
      },
      httpResponse: {
        statusCode: 200,
        body: {
          type: 'JSON',
          json: JSON.stringify({
            model: 'gpt-4.1',
            output: [
              { type: 'message', content: [{ type: 'output_text', text: '4' }] },
            ],
            usage: { prompt_tokens: 10, completion_tokens: 1 },
          }),
        },
      },
    };

    const parsed = parseTraffic(value);
    expect(parsed.kind).toBe('openai_responses');
    if (parsed.kind !== 'openai_responses') return;

    expect(parsed.model).toBe('gpt-4.1');
    expect(parsed.input).toHaveLength(1);
    expect(parsed.tools).toHaveLength(1);
    expect(parsed.output).toHaveLength(1);
    expect(parsed.usage).toEqual({ prompt_tokens: 10, completion_tokens: 1 });
  });

  it('parses a response with function_call output items', () => {
    const value = {
      httpRequest: {
        method: 'POST',
        path: '/v1/responses',
        body: {
          type: 'JSON',
          json: JSON.stringify({
            model: 'gpt-4.1',
            input: [{ role: 'user', content: 'Search for cats' }],
          }),
        },
      },
      httpResponse: {
        statusCode: 200,
        body: {
          type: 'JSON',
          json: JSON.stringify({
            model: 'gpt-4.1',
            output: [
              { type: 'function_call', name: 'search', arguments: '{"q":"cats"}' },
            ],
          }),
        },
      },
    };

    const parsed = parseTraffic(value);
    expect(parsed.kind).toBe('openai_responses');
    if (parsed.kind !== 'openai_responses') return;
    expect(parsed.output).toHaveLength(1);
    const outputItem = parsed.output[0] as Record<string, unknown>;
    expect(outputItem['type']).toBe('function_call');
    expect(outputItem['name']).toBe('search');
  });
});

// ---------------------------------------------------------------------------
// Gemini (generateContent)
// ---------------------------------------------------------------------------

describe('parseTraffic — Gemini', () => {
  it('parses a standard Gemini generateContent request/response', () => {
    const value = {
      httpRequest: {
        method: 'POST',
        path: '/v1beta/models/gemini-2.5-pro:generateContent',
        body: {
          type: 'JSON',
          json: JSON.stringify({
            contents: [
              {
                role: 'user',
                parts: [{ text: 'Hello' }],
              },
            ],
            tools: [{ functionDeclarations: [{ name: 'search' }] }],
          }),
        },
      },
      httpResponse: {
        statusCode: 200,
        body: {
          type: 'JSON',
          json: JSON.stringify({
            candidates: [
              {
                content: {
                  role: 'model',
                  parts: [{ text: 'Hi there!' }],
                },
                finishReason: 'STOP',
              },
            ],
            usageMetadata: { promptTokenCount: 5, candidatesTokenCount: 3 },
            modelVersion: 'gemini-2.5-pro',
          }),
        },
      },
    };

    const parsed = parseTraffic(value);
    expect(parsed.kind).toBe('gemini');
    if (parsed.kind !== 'gemini') return;

    expect(parsed.model).toBe('gemini-2.5-pro');
    expect(parsed.contents).toHaveLength(1);
    expect(parsed.tools).toHaveLength(1);
    expect(parsed.candidates).toHaveLength(1);
    expect(parsed.usage).toEqual({ promptTokenCount: 5, candidatesTokenCount: 3 });
  });

  it('handles v1 (non-beta) path', () => {
    const value = {
      httpRequest: {
        method: 'POST',
        path: '/v1/models/gemini-2.5-flash:generateContent',
        body: {
          type: 'JSON',
          json: JSON.stringify({ contents: [{ role: 'user', parts: [{ text: 'Hi' }] }] }),
        },
      },
      httpResponse: {
        statusCode: 200,
        body: { type: 'JSON', json: '{"candidates":[]}' },
      },
    };

    const parsed = parseTraffic(value);
    expect(parsed.kind).toBe('gemini');
  });

  it('handles functionCall and functionResponse parts', () => {
    const value = {
      httpRequest: {
        method: 'POST',
        path: '/v1beta/models/gemini-2.5-pro:generateContent',
        body: {
          type: 'JSON',
          json: JSON.stringify({
            contents: [
              { role: 'user', parts: [{ text: 'Weather?' }] },
              {
                role: 'model',
                parts: [{ functionCall: { name: 'get_weather', args: { city: 'London' } } }],
              },
              {
                role: 'function',
                parts: [{ functionResponse: { name: 'get_weather', response: { temp: '20C' } } }],
              },
            ],
          }),
        },
      },
      httpResponse: {
        statusCode: 200,
        body: {
          type: 'JSON',
          json: JSON.stringify({
            candidates: [{ content: { parts: [{ text: 'It is 20C in London.' }] } }],
          }),
        },
      },
    };

    const parsed = parseTraffic(value);
    expect(parsed.kind).toBe('gemini');
    if (parsed.kind !== 'gemini') return;
    expect(parsed.contents).toHaveLength(3);
    expect(parsed.candidates).toHaveLength(1);
  });
});

// ---------------------------------------------------------------------------
// Bedrock (Anthropic-on-Bedrock)
// ---------------------------------------------------------------------------

describe('parseTraffic — Bedrock', () => {
  it('parses a Bedrock Anthropic invoke request using the Anthropic wire format', () => {
    const value = {
      httpRequest: {
        method: 'POST',
        path: '/model/anthropic.claude-3-5-sonnet-20241022-v2:0/invoke',
        body: {
          type: 'JSON',
          json: JSON.stringify({
            model: 'anthropic.claude-3-5-sonnet-20241022-v2:0',
            max_tokens: 1024,
            messages: [{ role: 'user', content: 'Hello from Bedrock' }],
          }),
        },
      },
      httpResponse: {
        statusCode: 200,
        body: {
          type: 'JSON',
          json: JSON.stringify({
            content: [{ type: 'text', text: 'Hello from Claude on Bedrock!' }],
            usage: { input_tokens: 12, output_tokens: 8 },
            stop_reason: 'end_turn',
          }),
        },
      },
    };

    const parsed = parseTraffic(value);
    // Bedrock uses the Anthropic parser, so kind is 'anthropic'
    expect(parsed.kind).toBe('anthropic');
    if (parsed.kind !== 'anthropic') return;
    expect(parsed.responseContent).toHaveLength(1);
    expect(parsed.responseContent[0]!.text).toBe('Hello from Claude on Bedrock!');
    expect(parsed.usage).toEqual({ input_tokens: 12, output_tokens: 8 });
  });
});

// ---------------------------------------------------------------------------
// Azure OpenAI
// ---------------------------------------------------------------------------

describe('parseTraffic — Azure OpenAI', () => {
  it('parses an Azure OpenAI Chat Completions request using the OpenAI wire format', () => {
    const value = {
      httpRequest: {
        method: 'POST',
        path: '/openai/deployments/gpt-4/chat/completions',
        body: {
          type: 'JSON',
          json: JSON.stringify({
            model: 'gpt-4',
            messages: [{ role: 'user', content: 'Hello from Azure' }],
          }),
        },
      },
      httpResponse: {
        statusCode: 200,
        body: {
          type: 'JSON',
          json: JSON.stringify({
            model: 'gpt-4',
            choices: [{ message: { role: 'assistant', content: 'Hello from Azure OpenAI!' }, finish_reason: 'stop' }],
            usage: { prompt_tokens: 8, completion_tokens: 5 },
          }),
        },
      },
    };

    const parsed = parseTraffic(value);
    // Azure OpenAI uses the OpenAI parser, so kind is 'openai'
    expect(parsed.kind).toBe('openai');
    if (parsed.kind !== 'openai') return;
    expect(parsed.model).toBe('gpt-4');
    expect(parsed.choices).toHaveLength(1);
    expect(parsed.choices[0]!.message?.content).toBe('Hello from Azure OpenAI!');
  });
});

// ---------------------------------------------------------------------------
// Ollama (/api/chat)
// ---------------------------------------------------------------------------

describe('parseTraffic — Ollama', () => {
  it('parses a standard Ollama /api/chat request/response', () => {
    const value = {
      httpRequest: {
        method: 'POST',
        path: '/api/chat',
        body: {
          type: 'JSON',
          json: JSON.stringify({
            model: 'llama3',
            stream: false,
            messages: [
              { role: 'user', content: 'Hello' },
            ],
          }),
        },
      },
      httpResponse: {
        statusCode: 200,
        body: {
          type: 'JSON',
          json: JSON.stringify({
            model: 'llama3',
            message: { role: 'assistant', content: 'Hi there!' },
            done: true,
            prompt_eval_count: 15,
            eval_count: 8,
          }),
        },
      },
    };

    const parsed = parseTraffic(value);
    expect(parsed.kind).toBe('ollama');
    if (parsed.kind !== 'ollama') return;

    expect(parsed.model).toBe('llama3');
    expect(parsed.messages).toHaveLength(1);
    expect(parsed.done).toBe(true);
    expect(parsed.responseMessage).toEqual({ role: 'assistant', content: 'Hi there!' });
    expect(parsed.usage).toEqual({ prompt_eval_count: 15, eval_count: 8 });
  });

  it('handles tool_calls in Ollama messages', () => {
    const value = {
      httpRequest: {
        method: 'POST',
        path: '/api/chat',
        body: {
          type: 'JSON',
          json: JSON.stringify({
            model: 'llama3',
            messages: [
              { role: 'user', content: 'What is the weather?' },
            ],
            tools: [{ type: 'function', function: { name: 'get_weather' } }],
          }),
        },
      },
      httpResponse: {
        statusCode: 200,
        body: {
          type: 'JSON',
          json: JSON.stringify({
            model: 'llama3',
            message: {
              role: 'assistant',
              content: '',
              tool_calls: [{ function: { name: 'get_weather', arguments: { city: 'London' } } }],
            },
            done: true,
          }),
        },
      },
    };

    const parsed = parseTraffic(value);
    expect(parsed.kind).toBe('ollama');
    if (parsed.kind !== 'ollama') return;
    expect(parsed.tools).toHaveLength(1);
    const respMsg = parsed.responseMessage as Record<string, unknown>;
    const toolCalls = respMsg['tool_calls'] as unknown[];
    expect(toolCalls).toHaveLength(1);
  });
});

// ---------------------------------------------------------------------------
// getTokenSummary — new providers
// ---------------------------------------------------------------------------

describe('getTokenSummary — new providers', () => {
  it('formats Gemini token usage', () => {
    const value = {
      httpRequest: {
        method: 'POST',
        path: '/v1beta/models/gemini-2.5-pro:generateContent',
        body: { type: 'JSON', json: '{"contents":[]}' },
      },
      httpResponse: {
        statusCode: 200,
        body: { type: 'JSON', json: '{"candidates":[],"usageMetadata":{"promptTokenCount":20,"candidatesTokenCount":10}}' },
      },
    };
    const parsed = parseTraffic(value);
    expect(getTokenSummary(parsed)).toBe('20 in / 10 out');
  });

  it('formats Ollama token usage', () => {
    const value = {
      httpRequest: {
        method: 'POST',
        path: '/api/chat',
        body: { type: 'JSON', json: '{"model":"llama3","messages":[]}' },
      },
      httpResponse: {
        statusCode: 200,
        body: { type: 'JSON', json: '{"model":"llama3","done":true,"prompt_eval_count":50,"eval_count":25}' },
      },
    };
    const parsed = parseTraffic(value);
    expect(getTokenSummary(parsed)).toBe('50 in / 25 out');
  });

  it('formats OpenAI Responses token usage', () => {
    const value = {
      httpRequest: {
        method: 'POST',
        path: '/v1/responses',
        body: { type: 'JSON', json: '{"model":"gpt-4.1","input":[]}' },
      },
      httpResponse: {
        statusCode: 200,
        body: { type: 'JSON', json: '{"model":"gpt-4.1","output":[],"usage":{"prompt_tokens":10,"completion_tokens":5}}' },
      },
    };
    const parsed = parseTraffic(value);
    expect(getTokenSummary(parsed)).toBe('10 in / 5 out');
  });
});

// ---------------------------------------------------------------------------
// Per-request timing extraction
// ---------------------------------------------------------------------------

describe('summarizeTraffic — timing extraction', () => {
  it('extracts timing from httpResponse.timing for proxied requests', () => {
    const value = {
      httpRequest: {
        method: 'GET',
        path: '/api/data',
      },
      httpResponse: {
        statusCode: 200,
        timing: {
          connectionTimeInMillis: 12,
          timeToFirstByteInMillis: 85,
          totalTimeInMillis: 142,
          requestStartedMillis: 1700000000000,
          connectionEstablishedMillis: 1700000000012,
          responseReceivedMillis: 1700000000142,
        },
      },
    };

    const summary = summarizeTraffic(value);
    expect(summary.timing).not.toBeNull();
    expect(summary.timing!.connectionTimeInMillis).toBe(12);
    expect(summary.timing!.timeToFirstByteInMillis).toBe(85);
    expect(summary.timing!.totalTimeInMillis).toBe(142);
    expect(summary.timing!.requestStartedMillis).toBe(1700000000000);
    expect(summary.timing!.connectionEstablishedMillis).toBe(1700000000012);
    expect(summary.timing!.responseReceivedMillis).toBe(1700000000142);
  });

  it('returns null timing when httpResponse has no timing field', () => {
    const value = {
      httpRequest: {
        method: 'POST',
        path: '/v1/messages',
        body: {
          type: 'JSON',
          json: JSON.stringify({
            model: 'claude-sonnet-4-20250514',
            messages: [{ role: 'user', content: 'Hello' }],
          }),
        },
      },
      httpResponse: {
        statusCode: 200,
        body: {
          type: 'JSON',
          json: JSON.stringify({
            content: [{ type: 'text', text: 'Hi' }],
          }),
        },
      },
    };

    const summary = summarizeTraffic(value);
    expect(summary.timing).toBeNull();
  });

  it('handles partial timing fields (only totalTimeInMillis)', () => {
    const value = {
      httpRequest: { method: 'GET', path: '/health' },
      httpResponse: {
        statusCode: 200,
        timing: {
          totalTimeInMillis: 50,
        },
      },
    };

    const summary = summarizeTraffic(value);
    expect(summary.timing).not.toBeNull();
    expect(summary.timing!.totalTimeInMillis).toBe(50);
    expect(summary.timing!.connectionTimeInMillis).toBeNull();
    expect(summary.timing!.timeToFirstByteInMillis).toBeNull();
  });
});

describe('getTimingLabel', () => {
  it('returns total time as compact label', () => {
    expect(getTimingLabel({
      connectionTimeInMillis: 12,
      timeToFirstByteInMillis: 85,
      totalTimeInMillis: 142,
      requestStartedMillis: null,
      connectionEstablishedMillis: null,
      responseReceivedMillis: null,
    })).toBe('142ms');
  });

  it('returns null when timing is null', () => {
    expect(getTimingLabel(null)).toBeNull();
  });

  it('returns null when totalTimeInMillis is null', () => {
    expect(getTimingLabel({
      connectionTimeInMillis: 12,
      timeToFirstByteInMillis: null,
      totalTimeInMillis: null,
      requestStartedMillis: null,
      connectionEstablishedMillis: null,
      responseReceivedMillis: null,
    })).toBeNull();
  });
});

describe('getTimingBreakdown', () => {
  it('returns full breakdown with all fields', () => {
    expect(getTimingBreakdown({
      connectionTimeInMillis: 12,
      timeToFirstByteInMillis: 85,
      totalTimeInMillis: 142,
      requestStartedMillis: null,
      connectionEstablishedMillis: null,
      responseReceivedMillis: null,
    })).toBe('connect 12ms / TTFB 85ms / total 142ms');
  });

  it('returns partial breakdown with only total', () => {
    expect(getTimingBreakdown({
      connectionTimeInMillis: null,
      timeToFirstByteInMillis: null,
      totalTimeInMillis: 50,
      requestStartedMillis: null,
      connectionEstablishedMillis: null,
      responseReceivedMillis: null,
    })).toBe('total 50ms');
  });

  it('returns null when timing is null', () => {
    expect(getTimingBreakdown(null)).toBeNull();
  });
});
