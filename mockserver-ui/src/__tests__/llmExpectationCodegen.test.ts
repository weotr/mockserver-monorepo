import { describe, it, expect } from 'vitest';
import {
  expectationToJson,
  expectationToJava,
  expectationToMcpArgs,
  expectationToJsonObject,
  genericExpectationToJsonObject,
} from '../lib/llmExpectationCodegen';
import type { LlmExpectationDraft, GenericExpectationDraft } from '../lib/expectationFromCapture';

function baseDraft(): LlmExpectationDraft {
  return {
    kind: 'llm',
    path: '/v1/messages',
    provider: 'ANTHROPIC',
    model: 'claude-sonnet-4-20250514',
    text: 'Hello world',
    toolCalls: [],
    stopReason: 'end_turn',
    streaming: false,
  };
}

describe('expectationToJsonObject', () => {
  it('produces correct structure with text', () => {
    const draft = baseDraft();
    const obj = expectationToJsonObject(draft);

    expect(obj).toEqual({
      httpRequest: {
        method: 'POST',
        path: '/v1/messages',
      },
      httpLlmResponse: {
        provider: 'ANTHROPIC',
        model: 'claude-sonnet-4-20250514',
        completion: {
          text: 'Hello world',
          stopReason: 'end_turn',
        },
      },
    });
  });

  it('includes tool calls when present', () => {
    const draft = baseDraft();
    draft.toolCalls = [
      { name: 'search', arguments: '{"q":"test"}' },
      { name: 'execute' },
    ];
    const obj = expectationToJsonObject(draft);
    const completion = (obj['httpLlmResponse'] as Record<string, unknown>)['completion'] as Record<string, unknown>;

    expect(completion['toolCalls']).toEqual([
      { name: 'search', arguments: '{"q":"test"}' },
      { name: 'execute' },
    ]);
  });

  it('includes streaming when true', () => {
    const draft = baseDraft();
    draft.streaming = true;
    const obj = expectationToJsonObject(draft);
    const completion = (obj['httpLlmResponse'] as Record<string, unknown>)['completion'] as Record<string, unknown>;

    expect(completion['streaming']).toBe(true);
  });

  it('omits empty model', () => {
    const draft = baseDraft();
    draft.model = '';
    const obj = expectationToJsonObject(draft);
    const llm = obj['httpLlmResponse'] as Record<string, unknown>;

    expect(llm).not.toHaveProperty('model');
  });

  it('omits completion when no fields populated', () => {
    const draft: LlmExpectationDraft = {
      kind: 'llm',
      path: '/v1/messages',
      provider: 'OPENAI',
      model: '',
      text: '',
      toolCalls: [],
      stopReason: '',
      streaming: false,
    };
    const obj = expectationToJsonObject(draft);
    const llm = obj['httpLlmResponse'] as Record<string, unknown>;

    expect(llm).not.toHaveProperty('completion');
  });
});

describe('expectationToJson', () => {
  it('produces valid JSON string', () => {
    const draft = baseDraft();
    const json = expectationToJson(draft);
    const parsed = JSON.parse(json);

    expect(parsed).toHaveProperty('httpRequest');
    expect(parsed).toHaveProperty('httpLlmResponse');
  });

  it('is pretty-printed with 2-space indent', () => {
    const json = expectationToJson(baseDraft());
    expect(json).toContain('\n  ');
  });
});

describe('expectationToJava', () => {
  it('includes required static imports', () => {
    const java = expectationToJava(baseDraft());

    expect(java).toContain('import static org.mockserver.client.Llm.*;');
    expect(java).toContain('import static org.mockserver.model.HttpRequest.request;');
    expect(java).toContain('import static org.mockserver.model.HttpLlmResponse.llmResponse;');
    expect(java).toContain('import static org.mockserver.mock.Expectation.when;');
  });

  it('generates correct builder chain for text response', () => {
    const java = expectationToJava(baseDraft());

    expect(java).toContain('.withProvider(Provider.ANTHROPIC)');
    expect(java).toContain('.withModel("claude-sonnet-4-20250514")');
    expect(java).toContain('completion()');
    expect(java).toContain('.withText("Hello world")');
    expect(java).toContain('.withStopReason("end_turn")');
    expect(java).toContain('request().withMethod("POST").withPath("/v1/messages")');
    expect(java).toContain('.thenRespondWithLlm(');
  });

  it('generates tool calls correctly', () => {
    const draft = baseDraft();
    draft.toolCalls = [{ name: 'search', arguments: '{"q":"test"}' }];
    const java = expectationToJava(draft);

    expect(java).toContain('toolUse("search").withArguments("{\\"q\\":\\"test\\"}")');
    expect(java).toContain('.withToolCall(');
  });

  it('includes streaming() when streaming is true', () => {
    const draft = baseDraft();
    draft.streaming = true;
    const java = expectationToJava(draft);

    expect(java).toContain('.streaming()');
  });

  it('escapes special characters in text', () => {
    const draft = baseDraft();
    draft.text = 'Line 1\nLine "2"';
    const java = expectationToJava(draft);

    expect(java).toContain('.withText("Line 1\\nLine \\"2\\"")');
  });
});

describe('expectationToMcpArgs', () => {
  it('produces correct MCP tool arguments', () => {
    const draft = baseDraft();
    const args = expectationToMcpArgs(draft);

    expect(args).toEqual({
      provider: 'ANTHROPIC',
      path: '/v1/messages',
      model: 'claude-sonnet-4-20250514',
      text: 'Hello world',
      stopReason: 'end_turn',
    });
  });

  it('includes toolCalls when present', () => {
    const draft = baseDraft();
    draft.toolCalls = [{ name: 'search', arguments: '{}' }];
    const args = expectationToMcpArgs(draft);

    expect(args['toolCalls']).toEqual([{ name: 'search', arguments: '{}' }]);
  });

  it('includes streaming when true', () => {
    const draft = baseDraft();
    draft.streaming = true;
    const args = expectationToMcpArgs(draft);

    expect(args['streaming']).toBe(true);
  });

  it('omits empty model', () => {
    const draft = baseDraft();
    draft.model = '';
    const args = expectationToMcpArgs(draft);

    expect(args).not.toHaveProperty('model');
  });

  it('omits empty text and stopReason', () => {
    const draft = baseDraft();
    draft.text = '';
    draft.stopReason = '';
    const args = expectationToMcpArgs(draft);

    expect(args).not.toHaveProperty('text');
    expect(args).not.toHaveProperty('stopReason');
  });
});

// ---------------------------------------------------------------------------
// Generic HTTP codegen
// ---------------------------------------------------------------------------

function baseGenericDraft(): GenericExpectationDraft {
  return {
    kind: 'generic',
    method: 'GET',
    path: '/api/health',
    queryStringParameters: [],
    headers: [],
    body: '',
    responseStatusCode: 200,
    responseHeaders: [{ name: 'Content-Type', values: ['application/json'] }],
    responseBody: '{"status":"ok"}',
    matcherPrecision: 'moderate',
  };
}

describe('genericExpectationToJsonObject', () => {
  it('produces httpRequest + httpResponse structure', () => {
    const draft = baseGenericDraft();
    const obj = genericExpectationToJsonObject(draft);

    expect(obj).toHaveProperty('httpRequest');
    expect(obj).toHaveProperty('httpResponse');
    expect(obj).not.toHaveProperty('httpLlmResponse');

    const req = obj['httpRequest'] as Record<string, unknown>;
    expect(req['method']).toBe('GET');
    expect(req['path']).toBe('/api/health');

    const res = obj['httpResponse'] as Record<string, unknown>;
    expect(res['statusCode']).toBe(200);
    expect(res['body']).toBe('{"status":"ok"}');
  });

  it('loose precision includes only method and path', () => {
    const draft = baseGenericDraft();
    draft.matcherPrecision = 'loose';
    draft.body = '{"data":true}';
    draft.queryStringParameters = [{ name: 'q', values: ['test'] }];
    draft.headers = [{ name: 'Accept', values: ['*/*'] }];

    const obj = genericExpectationToJsonObject(draft);
    const req = obj['httpRequest'] as Record<string, unknown>;

    expect(req['method']).toBe('GET');
    expect(req['path']).toBe('/api/health');
    expect(req).not.toHaveProperty('body');
    expect(req).not.toHaveProperty('queryStringParameters');
    expect(req).not.toHaveProperty('headers');
  });

  it('moderate precision includes query and body but not headers', () => {
    const draft = baseGenericDraft();
    draft.matcherPrecision = 'moderate';
    draft.body = '{"data":true}';
    draft.queryStringParameters = [{ name: 'q', values: ['test'] }];
    draft.headers = [{ name: 'Accept', values: ['*/*'] }];

    const obj = genericExpectationToJsonObject(draft);
    const req = obj['httpRequest'] as Record<string, unknown>;

    expect(req['body']).toBe('{"data":true}');
    expect(req['queryStringParameters']).toEqual([{ name: 'q', values: ['test'] }]);
    expect(req).not.toHaveProperty('headers');
  });

  it('exact precision includes headers, query, and body', () => {
    const draft = baseGenericDraft();
    draft.matcherPrecision = 'exact';
    draft.body = '{"data":true}';
    draft.queryStringParameters = [{ name: 'q', values: ['test'] }];
    draft.headers = [{ name: 'Accept', values: ['*/*'] }];

    const obj = genericExpectationToJsonObject(draft);
    const req = obj['httpRequest'] as Record<string, unknown>;

    expect(req['body']).toBe('{"data":true}');
    expect(req['queryStringParameters']).toEqual([{ name: 'q', values: ['test'] }]);
    expect(req['headers']).toEqual([{ name: 'Accept', values: ['*/*'] }]);
  });
});

describe('generic expectationToJava', () => {
  it('uses HttpResponse imports (not LLM)', () => {
    const java = expectationToJava(baseGenericDraft());

    expect(java).toContain('import static org.mockserver.model.HttpResponse.response;');
    expect(java).toContain('import static org.mockserver.model.HttpRequest.request;');
    expect(java).not.toContain('Llm');
    expect(java).not.toContain('llmResponse');
  });

  it('generates correct builder chain', () => {
    const java = expectationToJava(baseGenericDraft());

    expect(java).toContain('request().withMethod("GET").withPath("/api/health")');
    expect(java).toContain('.thenRespond(');
    expect(java).toContain('response().withStatusCode(200)');
    expect(java).toContain('.withBody("{\\"status\\":\\"ok\\"}")');
  });

  it('includes headers in exact mode', () => {
    const draft = baseGenericDraft();
    draft.matcherPrecision = 'exact';
    draft.headers = [{ name: 'Accept', values: ['application/json'] }];
    const java = expectationToJava(draft);

    expect(java).toContain('.withHeader("Accept", "application/json")');
  });

  it('omits body and headers in loose mode', () => {
    const draft = baseGenericDraft();
    draft.matcherPrecision = 'loose';
    draft.body = 'something';
    draft.headers = [{ name: 'Accept', values: ['*/*'] }];
    const java = expectationToJava(draft);

    // Request should only have method and path
    const requestLine = java.split('.thenRespond(')[0]!;
    expect(requestLine).not.toContain('.withBody(');
    expect(requestLine).not.toContain('.withHeader(');
  });
});

describe('generic multi-value query params and headers in Java codegen', () => {
  it('emits separate quoted args for multi-value query params', () => {
    const draft = baseGenericDraft();
    draft.matcherPrecision = 'moderate';
    draft.queryStringParameters = [
      { name: 'page[]', values: ['1', '2', '3'] },
    ];
    const java = expectationToJava(draft);

    expect(java).toContain('.withQueryStringParameter("page[]", "1", "2", "3")');
    // Must NOT join into a single string
    expect(java).not.toContain('"1, 2, 3"');
  });

  it('emits separate quoted args for multi-value request headers in exact mode', () => {
    const draft = baseGenericDraft();
    draft.matcherPrecision = 'exact';
    draft.headers = [
      { name: 'Accept', values: ['text/html', 'application/json'] },
    ];
    const java = expectationToJava(draft);

    expect(java).toContain('.withHeader("Accept", "text/html", "application/json")');
    expect(java).not.toContain('"text/html, application/json"');
  });

  it('emits separate quoted args for multi-value response headers', () => {
    const draft = baseGenericDraft();
    draft.responseHeaders = [
      { name: 'Set-Cookie', values: ['a=1', 'b=2'] },
    ];
    const java = expectationToJava(draft);

    expect(java).toContain('.withHeader("Set-Cookie", "a=1", "b=2")');
    expect(java).not.toContain('"a=1, b=2"');
  });

  it('escapes special chars in each value separately', () => {
    const draft = baseGenericDraft();
    draft.matcherPrecision = 'moderate';
    draft.queryStringParameters = [
      { name: 'q', values: ['hello "world"', 'foo\nbar'] },
    ];
    const java = expectationToJava(draft);

    expect(java).toContain('.withQueryStringParameter("q", "hello \\"world\\"", "foo\\nbar")');
  });
});

describe('generic expectationToJson', () => {
  it('produces valid JSON for generic draft', () => {
    const json = expectationToJson(baseGenericDraft());
    const parsed = JSON.parse(json);

    expect(parsed).toHaveProperty('httpRequest');
    expect(parsed).toHaveProperty('httpResponse');
    expect(parsed).not.toHaveProperty('httpLlmResponse');
  });
});
