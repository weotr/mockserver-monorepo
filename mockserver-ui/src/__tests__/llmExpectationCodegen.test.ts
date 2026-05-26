import { describe, it, expect } from 'vitest';
import {
  expectationToJson,
  expectationToJava,
  expectationToMcpArgs,
  expectationToJsonObject,
} from '../lib/llmExpectationCodegen';
import type { ExpectationDraft } from '../lib/expectationFromCapture';

function baseDraft(): ExpectationDraft {
  return {
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
    const draft: ExpectationDraft = {
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
