import { describe, it, expect } from 'vitest';
import {
  conversationToJava,
  conversationToJson,
  conversationToMcpArgs,
  conversationToMcpCall,
  type ConversationDraft,
} from '../lib/conversationCodegen';

function baseDraft(): ConversationDraft {
  return {
    provider: 'ANTHROPIC',
    path: '/v1/messages',
    model: 'claude-sonnet-4-20250514',
    turns: [
      {
        predicates: { turnIndex: 0 },
        response: {
          text: '',
          toolCalls: [{ name: 'search', arguments: '{"q":"test"}' }],
          stopReason: 'tool_use',
          streaming: false,
        },
      },
      {
        predicates: { containsToolResultFor: 'search' },
        response: {
          text: 'The answer is 42.',
          toolCalls: [],
          stopReason: 'end_turn',
          streaming: false,
        },
      },
    ],
  };
}

describe('conversationToJava', () => {
  it('includes correct import', () => {
    const java = conversationToJava(baseDraft());

    expect(java).toContain('import static org.mockserver.client.Llm.*;');
    expect(java).toContain('import org.mockserver.model.Provider;');
  });

  it('generates conversation() builder chain', () => {
    const java = conversationToJava(baseDraft());

    expect(java).toContain('conversation()');
    expect(java).toContain('.withPath("/v1/messages")');
    expect(java).toContain('.withProvider(Provider.ANTHROPIC)');
    expect(java).toContain('.withModel("claude-sonnet-4-20250514")');
    expect(java).toContain('.applyTo(mockServerClient);');
  });

  it('generates turn predicates', () => {
    const java = conversationToJava(baseDraft());

    expect(java).toContain('.whenTurnIndex(0)');
    expect(java).toContain('.whenContainsToolResultFor("search")');
  });

  it('generates turn responses with tool calls', () => {
    const java = conversationToJava(baseDraft());

    expect(java).toContain('.respondingWith(');
    expect(java).toContain('toolUse("search").withArguments("{\\"q\\":\\"test\\"}")');
    expect(java).toContain('.withStopReason("tool_use")');
  });

  it('chains turns with andThen()', () => {
    const java = conversationToJava(baseDraft());

    expect(java).toContain('.andThen()');
    // The last turn should NOT have andThen
    const lastTurnIndex = java.lastIndexOf('.turn()');
    const lastAndThen = java.lastIndexOf('.andThen()');
    expect(lastAndThen).toBeLessThan(lastTurnIndex);
  });

  it('generates isolation source when present', () => {
    const draft = baseDraft();
    draft.isolateBy = { source: 'header', name: 'x-session-id' };
    const java = conversationToJava(draft);

    expect(java).toContain('.isolateBy(header("x-session-id"))');
  });

  it('generates latestMessageContains predicate', () => {
    const draft = baseDraft();
    draft.turns[0]!.predicates = { latestMessageContains: 'hello' };
    const java = conversationToJava(draft);

    expect(java).toContain('.whenLatestMessageContains("hello")');
  });

  it('generates latestMessageRole predicate', () => {
    const draft = baseDraft();
    draft.turns[0]!.predicates = { latestMessageRole: 'USER' };
    const java = conversationToJava(draft);

    expect(java).toContain('.whenLatestMessageRole(ParsedMessage.Role.USER)');
  });

  it('generates streaming() on response', () => {
    const draft = baseDraft();
    draft.turns[0]!.response.streaming = true;
    const java = conversationToJava(draft);

    expect(java).toContain('.streaming()');
  });
});

describe('conversationToJson', () => {
  it('produces valid JSON array', () => {
    const json = conversationToJson(baseDraft());
    const parsed = JSON.parse(json);

    expect(Array.isArray(parsed)).toBe(true);
    expect(parsed).toHaveLength(2);
  });

  it('includes scenario state transitions', () => {
    const json = conversationToJson(baseDraft());
    const parsed = JSON.parse(json);

    expect(parsed[0].httpLlmResponse.scenarioState).toBe('Started');
    expect(parsed[0].httpLlmResponse.newScenarioState).toBe('turn_1');
    expect(parsed[1].httpLlmResponse.scenarioState).toBe('turn_1');
    expect(parsed[1].httpLlmResponse.newScenarioState).toBe('__done');
  });

  it('includes conversation predicates', () => {
    const json = conversationToJson(baseDraft());
    const parsed = JSON.parse(json);

    expect(parsed[0].httpLlmResponse.conversationPredicates).toEqual({
      turnIndex: 0,
    });
    expect(parsed[1].httpLlmResponse.conversationPredicates).toEqual({
      containsToolResultFor: 'search',
    });
  });

  it('includes completion data', () => {
    const json = conversationToJson(baseDraft());
    const parsed = JSON.parse(json);

    expect(parsed[0].httpLlmResponse.completion.toolCalls).toEqual([
      { name: 'search', arguments: '{"q":"test"}' },
    ]);
    expect(parsed[1].httpLlmResponse.completion.text).toBe('The answer is 42.');
  });

  it('includes httpRequest with POST and path', () => {
    const json = conversationToJson(baseDraft());
    const parsed = JSON.parse(json);

    expect(parsed[0].httpRequest).toEqual({
      method: 'POST',
      path: '/v1/messages',
    });
  });
});

describe('conversationToMcpArgs', () => {
  it('produces correct tool arguments', () => {
    const args = conversationToMcpArgs(baseDraft());

    expect(args['provider']).toBe('ANTHROPIC');
    expect(args['path']).toBe('/v1/messages');
    expect(args['model']).toBe('claude-sonnet-4-20250514');
    expect(Array.isArray(args['turns'])).toBe(true);
  });

  it('includes match predicates in turns', () => {
    const args = conversationToMcpArgs(baseDraft());
    const turns = args['turns'] as Record<string, unknown>[];

    expect(turns[0]!['match']).toEqual({ turnIndex: 0 });
    expect(turns[1]!['match']).toEqual({ containsToolResultFor: 'search' });
  });

  it('includes response in turns', () => {
    const args = conversationToMcpArgs(baseDraft());
    const turns = args['turns'] as Record<string, unknown>[];

    expect((turns[0]!['response'] as Record<string, unknown>)['toolCalls']).toEqual([
      { name: 'search', arguments: '{"q":"test"}' },
    ]);
    expect((turns[0]!['response'] as Record<string, unknown>)['stopReason']).toBe('tool_use');
    expect((turns[1]!['response'] as Record<string, unknown>)['text']).toBe('The answer is 42.');
  });

  it('includes isolation when present', () => {
    const draft = baseDraft();
    draft.isolateBy = { source: 'queryParameter', name: 'session' };
    const args = conversationToMcpArgs(draft);

    expect(args['isolateBy']).toEqual({
      source: 'queryParameter',
      name: 'session',
    });
  });

  it('omits empty model', () => {
    const draft = baseDraft();
    draft.model = '';
    const args = conversationToMcpArgs(draft);

    expect(args).not.toHaveProperty('model');
  });
});

describe('conversationToMcpCall', () => {
  it('produces valid JSON-RPC 2.0 envelope', () => {
    const call = conversationToMcpCall(baseDraft());
    const parsed = JSON.parse(call);

    expect(parsed['jsonrpc']).toBe('2.0');
    expect(parsed['method']).toBe('tools/call');
    expect(parsed['params']['name']).toBe('create_llm_conversation');
    expect(parsed['params']['arguments']).toBeDefined();
    expect(parsed['params']['arguments']['provider']).toBe('ANTHROPIC');
  });
});
