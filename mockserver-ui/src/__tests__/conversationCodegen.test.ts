import { describe, it, expect } from 'vitest';
import {
  conversationToJava,
  conversationToJson,
  conversationToMcpArgs,
  conversationToMcpCall,
  draftFromScenarioExpectations,
  hasRangeErrors,
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

  it('emits timeToFirstToken as Delay with TimeUnit import', () => {
    const draft = baseDraft();
    draft.turns[0]!.response.streaming = true;
    draft.turns[0]!.response.streamingPhysics = { timeToFirstToken: 200, tokensPerSecond: 50 };
    const java = conversationToJava(draft);

    expect(java).toContain('timeToFirstToken(200L, TimeUnit.MILLISECONDS)');
    expect(java).toContain('import java.util.concurrent.TimeUnit;');
    // tokensPerSecond is a plain number, not a Delay
    expect(java).toContain('tokensPerSecond(50)');
    // fragments are passed as varargs to withStreamingPhysics, not chained
    expect(java).toContain('.withStreamingPhysics(timeToFirstToken(200L, TimeUnit.MILLISECONDS), tokensPerSecond(50))');
  });
});

describe('conversationToJson', () => {
  it('produces valid JSON array', () => {
    const json = conversationToJson(baseDraft());
    const parsed = JSON.parse(json);

    expect(Array.isArray(parsed)).toBe(true);
    expect(parsed).toHaveLength(2);
  });

  it('includes scenario state transitions as top-level expectation fields', () => {
    const json = conversationToJson(baseDraft());
    const parsed = JSON.parse(json);

    // scenarioName/scenarioState/newScenarioState are top-level Expectation fields, not
    // nested inside httpLlmResponse (the server rejects unknown httpLlmResponse properties).
    expect(parsed[0].httpLlmResponse).not.toHaveProperty('scenarioState');
    expect(parsed[0].scenarioState).toBe('Started');
    expect(parsed[0].newScenarioState).toBe('turn_1');
    expect(parsed[1].scenarioState).toBe('turn_1');
    expect(parsed[1].newScenarioState).toBe('__done');
    expect(typeof parsed[0].scenarioName).toBe('string');
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

  it('emits timeToFirstToken as a Delay object (not a plain number)', () => {
    const draft = baseDraft();
    draft.turns[0]!.response.streaming = true;
    draft.turns[0]!.response.streamingPhysics = { timeToFirstToken: 200, tokensPerSecond: 50, jitter: 0.1 };
    const parsed = JSON.parse(conversationToJson(draft));
    const sp = parsed[0].httpLlmResponse.completion.streamingPhysics;

    expect(sp.timeToFirstToken).toEqual({ timeUnit: 'MILLISECONDS', value: 200 });
    // tokensPerSecond and jitter are plain numbers
    expect(sp.tokensPerSecond).toBe(50);
    expect(sp.jitter).toBe(0.1);
  });

  it('emits outputSchema as a raw string, not a parsed object', () => {
    const draft = baseDraft();
    draft.turns[0]!.response.outputSchema = '{"type":"object","properties":{"name":{"type":"string"}}}';
    const parsed = JSON.parse(conversationToJson(draft));
    const schema = parsed[0].httpLlmResponse.completion.outputSchema;

    expect(typeof schema).toBe('string');
    expect(schema).toBe('{"type":"object","properties":{"name":{"type":"string"}}}');
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

  it('emits timeToFirstToken as a Delay object in MCP args', () => {
    const draft = baseDraft();
    draft.turns[0]!.response.streaming = true;
    draft.turns[0]!.response.streamingPhysics = { timeToFirstToken: 300 };
    const args = conversationToMcpArgs(draft);
    const turns = args['turns'] as Record<string, unknown>[];
    const response = turns[0]!['response'] as Record<string, unknown>;
    const sp = response['streamingPhysics'] as Record<string, unknown>;

    expect(sp['timeToFirstToken']).toEqual({ timeUnit: 'MILLISECONDS', value: 300 });
  });

  it('emits outputSchema as a raw string in MCP args', () => {
    const draft = baseDraft();
    draft.turns[0]!.response.outputSchema = '{"type":"object"}';
    const args = conversationToMcpArgs(draft);
    const turns = args['turns'] as Record<string, unknown>[];
    const response = turns[0]!['response'] as Record<string, unknown>;

    expect(typeof response['outputSchema']).toBe('string');
    expect(response['outputSchema']).toBe('{"type":"object"}');
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

describe('prompt normalisation', () => {
  function normalisedDraft(): ConversationDraft {
    const draft = baseDraft();
    draft.turns[0]!.predicates = {
      latestMessageContains: 'search the catalogue',
      normalization: {
        collapseWhitespace: true,
        lowercase: true,
        sortJsonKeys: true,
        dropBuiltInVolatileFields: true,
        dropVolatileFields: ['requestId'],
      },
    };
    return draft;
  }

  it('emits withNormalization in Java', () => {
    const java = conversationToJava(normalisedDraft());
    expect(java).toContain('.withNormalization(');
    expect(java).toContain('.withLowercase(true)');
    expect(java).toContain('.withDropBuiltInVolatileFields(true)');
    expect(java).toContain('java.util.Arrays.asList("requestId")');
  });

  it('emits normalization object in JSON predicates', () => {
    const json = JSON.parse(conversationToJson(normalisedDraft()));
    const norm = json[0].httpLlmResponse.conversationPredicates.normalization;
    expect(norm.lowercase).toBe(true);
    expect(norm.dropVolatileFields).toEqual(['requestId']);
  });

  it('emits normalization object in MCP match', () => {
    const args = conversationToMcpArgs(normalisedDraft());
    const turns = args['turns'] as Array<Record<string, unknown>>;
    const match = turns[0]!['match'] as Record<string, unknown>;
    const norm = match['normalization'] as Record<string, unknown>;
    expect(norm['sortJsonKeys']).toBe(true);
    expect(norm['dropBuiltInVolatileFields']).toBe(true);
  });

  it('round-trips through draftFromScenarioExpectations', () => {
    const json = JSON.parse(conversationToJson(normalisedDraft())) as Array<Record<string, unknown>>;
    const { draft } = draftFromScenarioExpectations(
      json.map((value, i) => ({ key: `k${i}`, value })),
    );
    const norm = draft.turns[0]!.predicates.normalization;
    expect(norm).toBeDefined();
    expect(norm!.lowercase).toBe(true);
    expect(norm!.dropVolatileFields).toEqual(['requestId']);
  });
});

describe('latestMessageMatches (regex predicate)', () => {
  function regexDraft(): ConversationDraft {
    const draft = baseDraft();
    draft.turns[0]!.predicates = { latestMessageMatches: 'weather.*paris' };
    return draft;
  }

  it('emits latestMessageMatches in MCP match', () => {
    const args = conversationToMcpArgs(regexDraft());
    const turns = args['turns'] as Array<Record<string, unknown>>;
    const match = turns[0]!['match'] as Record<string, unknown>;
    expect(match['latestMessageMatches']).toBe('weather.*paris');
  });

  it('emits whenLatestMessageMatches in Java', () => {
    const java = conversationToJava(regexDraft());
    expect(java).toContain('.whenLatestMessageMatches(Pattern.compile("weather.*paris"))');
  });

  it('round-trips latestMessageMatches through draftFromScenarioExpectations', () => {
    const json = JSON.parse(conversationToJson(regexDraft())) as Array<Record<string, unknown>>;
    const { draft } = draftFromScenarioExpectations(
      json.map((value, i) => ({ key: `k${i}`, value })),
    );
    expect(draft.turns[0]!.predicates.latestMessageMatches).toBe('weather.*paris');
  });
});

describe('semanticMatchAgainst (opt-in fuzzy match)', () => {
  function semanticDraft(): ConversationDraft {
    const draft = baseDraft();
    draft.turns[0]!.predicates = { semanticMatchAgainst: 'asking about the weather' };
    return draft;
  }

  it('emits whenSemanticMatch in Java', () => {
    expect(conversationToJava(semanticDraft())).toContain('.whenSemanticMatch("asking about the weather")');
  });

  it('emits semanticMatchAgainst in JSON predicates and MCP match', () => {
    const json = JSON.parse(conversationToJson(semanticDraft()));
    expect(json[0].httpLlmResponse.conversationPredicates.semanticMatchAgainst).toBe('asking about the weather');
    const args = conversationToMcpArgs(semanticDraft());
    const match = (args['turns'] as Array<Record<string, unknown>>)[0]!['match'] as Record<string, unknown>;
    expect(match['semanticMatchAgainst']).toBe('asking about the weather');
  });

  it('round-trips through draftFromScenarioExpectations', () => {
    const json = JSON.parse(conversationToJson(semanticDraft())) as Array<Record<string, unknown>>;
    const { draft } = draftFromScenarioExpectations(json.map((value, i) => ({ key: `k${i}`, value })));
    expect(draft.turns[0]!.predicates.semanticMatchAgainst).toBe('asking about the weather');
  });
});

describe('chaos profile', () => {
  function chaosDraft(): ConversationDraft {
    const draft = baseDraft();
    draft.turns[0]!.chaos = {
      errorStatus: 429,
      retryAfter: '30',
      errorProbability: 1.0,
      truncateMode: 'MID_STREAM',
      truncateAtFraction: 0.5,
      malformedSse: true,
      seed: 7,
    };
    return draft;
  }

  it('emits withChaos in Java', () => {
    const java = conversationToJava(chaosDraft());
    expect(java).toContain('.withChaos(');
    expect(java).toContain('.withErrorStatus(429)');
    expect(java).toContain('.withTruncateMode(org.mockserver.model.LlmChaosProfile.TruncateMode.MID_STREAM)');
    expect(java).toContain('.withSeed(7L)');
  });

  it('emits chaos object in JSON httpLlmResponse', () => {
    const json = JSON.parse(conversationToJson(chaosDraft()));
    const chaos = json[0].httpLlmResponse.chaos;
    expect(chaos.errorStatus).toBe(429);
    expect(chaos.malformedSse).toBe(true);
  });

  it('emits chaos object in MCP turn', () => {
    const args = conversationToMcpArgs(chaosDraft());
    const turns = args['turns'] as Array<Record<string, unknown>>;
    const chaos = turns[0]!['chaos'] as Record<string, unknown>;
    expect(chaos['errorStatus']).toBe(429);
    expect(chaos['truncateMode']).toBe('MID_STREAM');
  });

  it('round-trips chaos through draftFromScenarioExpectations', () => {
    const json = JSON.parse(conversationToJson(chaosDraft())) as Array<Record<string, unknown>>;
    const { draft } = draftFromScenarioExpectations(
      json.map((value, i) => ({ key: `k${i}`, value })),
    );
    expect(draft.turns[0]!.chaos?.errorStatus).toBe(429);
    expect(draft.turns[0]!.chaos?.malformedSse).toBe(true);
  });

  it('omits NONE truncateMode from wire output', () => {
    const draft = baseDraft();
    draft.turns[0]!.chaos = { truncateMode: 'NONE', errorStatus: 500 };
    const args = conversationToMcpArgs(draft);
    const turns = args['turns'] as Array<Record<string, unknown>>;
    const chaos = turns[0]!['chaos'] as Record<string, unknown>;
    expect(chaos['truncateMode']).toBeUndefined();
    expect(chaos['errorStatus']).toBe(500);
  });
});

describe('timeToFirstToken Delay round-trip', () => {
  function streamingDraft(): ConversationDraft {
    const draft = baseDraft();
    draft.turns[0]!.response.streaming = true;
    draft.turns[0]!.response.streamingPhysics = { timeToFirstToken: 200, tokensPerSecond: 50, jitter: 0.1 };
    return draft;
  }

  it('round-trips timeToFirstToken Delay through draftFromScenarioExpectations', () => {
    const json = JSON.parse(conversationToJson(streamingDraft())) as Array<Record<string, unknown>>;
    const { draft } = draftFromScenarioExpectations(
      json.map((value, i) => ({ key: `k${i}`, value })),
    );
    expect(draft.turns[0]!.response.streamingPhysics?.timeToFirstToken).toBe(200);
    expect(draft.turns[0]!.response.streamingPhysics?.tokensPerSecond).toBe(50);
    expect(draft.turns[0]!.response.streamingPhysics?.jitter).toBe(0.1);
  });
});

describe('outputSchema as string', () => {
  it('round-trips outputSchema string through draftFromScenarioExpectations', () => {
    const draft = baseDraft();
    draft.turns[0]!.response.outputSchema = '{"type":"object","properties":{"name":{"type":"string"}}}';
    const json = JSON.parse(conversationToJson(draft)) as Array<Record<string, unknown>>;
    const { draft: reloaded } = draftFromScenarioExpectations(
      json.map((value, i) => ({ key: `k${i}`, value })),
    );
    expect(reloaded.turns[0]!.response.outputSchema).toBe('{"type":"object","properties":{"name":{"type":"string"}}}');
  });

  it('does not JSON.parse outputSchema in the wire format', () => {
    const draft = baseDraft();
    draft.turns[0]!.response.outputSchema = '{"type":"object"}';
    // JSON path
    const parsed = JSON.parse(conversationToJson(draft));
    expect(typeof parsed[0].httpLlmResponse.completion.outputSchema).toBe('string');
    // MCP path
    const args = conversationToMcpArgs(draft);
    const turns = args['turns'] as Array<Record<string, unknown>>;
    const response = turns[0]!['response'] as Record<string, unknown>;
    expect(typeof response['outputSchema']).toBe('string');
  });
});

describe('outputSchema in Java codegen', () => {
  it('emits withOutputSchema in Java when outputSchema is set', () => {
    const draft = baseDraft();
    draft.turns[0]!.response.outputSchema = '{"type":"object","properties":{"name":{"type":"string"}}}';
    const java = conversationToJava(draft);
    expect(java).toContain('.withOutputSchema("{\\"type\\":\\"object\\",\\"properties\\":{\\"name\\":{\\"type\\":\\"string\\"}}}")');
  });

  it('omits withOutputSchema in Java when outputSchema is not set', () => {
    const java = conversationToJava(baseDraft());
    expect(java).not.toContain('withOutputSchema');
  });

  it('Java / JSON / MCP all emit outputSchema consistently', () => {
    const draft = baseDraft();
    draft.turns[0]!.response.outputSchema = '{"type":"object"}';
    const java = conversationToJava(draft);
    const json = JSON.parse(conversationToJson(draft));
    const args = conversationToMcpArgs(draft);
    const turns = args['turns'] as Array<Record<string, unknown>>;
    const response = turns[0]!['response'] as Record<string, unknown>;

    // Java emits withOutputSchema
    expect(java).toContain('.withOutputSchema("{\\"type\\":\\"object\\"}")');
    // JSON emits outputSchema string
    expect(json[0].httpLlmResponse.completion.outputSchema).toBe('{"type":"object"}');
    // MCP emits outputSchema string
    expect(response['outputSchema']).toBe('{"type":"object"}');
  });
});

describe('conversationToMcpArgs with existingIds (edit flow)', () => {
  it('includes ids when existingIds length matches turn count (in-place update)', () => {
    const draft = baseDraft(); // 2 turns
    const ids = ['id-1', 'id-2'];
    const args = conversationToMcpArgs(draft, ids);
    expect(args['ids']).toEqual(['id-1', 'id-2']);
  });

  it('omits ids when existingIds is undefined (new conversation)', () => {
    const args = conversationToMcpArgs(baseDraft());
    expect(args).not.toHaveProperty('ids');
  });

  it('omits ids when existingIds is empty', () => {
    const args = conversationToMcpArgs(baseDraft(), []);
    expect(args).not.toHaveProperty('ids');
  });

  it('includes ids even when lengths differ (caller decides whether to pass them)', () => {
    // When the LlmConversationForm detects a turn-count mismatch, it passes
    // undefined for existingIds and clears the old expectations separately.
    // The conversationToMcpArgs function itself is agnostic — it includes whatever
    // ids the caller passes. This test documents that the function trusts the caller.
    const draft = baseDraft(); // 2 turns
    const ids = ['id-1']; // only 1 id
    const args = conversationToMcpArgs(draft, ids);
    expect(args['ids']).toEqual(['id-1']);
  });
});

describe('hasRangeErrors', () => {
  it('returns false for valid turns', () => {
    expect(hasRangeErrors(baseDraft().turns)).toBe(false);
  });

  it('returns true when tokensPerSecond is out of range (server: 1–10000)', () => {
    const draft = baseDraft();
    draft.turns[0]!.response.streaming = true;
    draft.turns[0]!.response.streamingPhysics = { tokensPerSecond: 0 };
    expect(hasRangeErrors(draft.turns)).toBe(true);

    draft.turns[0]!.response.streamingPhysics = { tokensPerSecond: 10001 };
    expect(hasRangeErrors(draft.turns)).toBe(true);

    draft.turns[0]!.response.streamingPhysics = { tokensPerSecond: 50 };
    expect(hasRangeErrors(draft.turns)).toBe(false);
  });

  it('returns true when jitter is out of range (server: 0.0–1.0)', () => {
    const draft = baseDraft();
    draft.turns[0]!.response.streaming = true;
    draft.turns[0]!.response.streamingPhysics = { jitter: -0.1 };
    expect(hasRangeErrors(draft.turns)).toBe(true);

    draft.turns[0]!.response.streamingPhysics = { jitter: 1.5 };
    expect(hasRangeErrors(draft.turns)).toBe(true);

    draft.turns[0]!.response.streamingPhysics = { jitter: 0.5 };
    expect(hasRangeErrors(draft.turns)).toBe(false);
  });

  it('returns true for NaN values in numeric fields (e.g. from a partially-typed input)', () => {
    const draft = baseDraft();
    draft.turns[0]!.response.streaming = true;
    draft.turns[0]!.response.streamingPhysics = { jitter: NaN };
    expect(hasRangeErrors(draft.turns)).toBe(true);

    draft.turns[0]!.response.streamingPhysics = { tokensPerSecond: NaN };
    expect(hasRangeErrors(draft.turns)).toBe(true);

    draft.turns[0]!.response.streamingPhysics = undefined;
    draft.turns[0]!.chaos = { truncateAtFraction: NaN };
    expect(hasRangeErrors(draft.turns)).toBe(true);
  });

  it('returns true when chaos errorStatus is out of range (server: 100–599)', () => {
    const draft = baseDraft();
    draft.turns[0]!.chaos = { errorStatus: 99 };
    expect(hasRangeErrors(draft.turns)).toBe(true);

    draft.turns[0]!.chaos = { errorStatus: 600 };
    expect(hasRangeErrors(draft.turns)).toBe(true);

    draft.turns[0]!.chaos = { errorStatus: 429 };
    expect(hasRangeErrors(draft.turns)).toBe(false);
  });

  it('returns true when chaos errorProbability is out of range (server: 0.0–1.0)', () => {
    const draft = baseDraft();
    draft.turns[0]!.chaos = { errorProbability: -0.5 };
    expect(hasRangeErrors(draft.turns)).toBe(true);

    draft.turns[0]!.chaos = { errorProbability: 1.1 };
    expect(hasRangeErrors(draft.turns)).toBe(true);

    draft.turns[0]!.chaos = { errorProbability: 0.5 };
    expect(hasRangeErrors(draft.turns)).toBe(false);
  });

  it('returns true when chaos truncateAtFraction is out of range (server: 0.0–1.0)', () => {
    const draft = baseDraft();
    draft.turns[0]!.chaos = { truncateAtFraction: -0.1 };
    expect(hasRangeErrors(draft.turns)).toBe(true);

    draft.turns[0]!.chaos = { truncateAtFraction: 1.5 };
    expect(hasRangeErrors(draft.turns)).toBe(true);

    draft.turns[0]!.chaos = { truncateAtFraction: 0.8 };
    expect(hasRangeErrors(draft.turns)).toBe(false);
  });

  it('does not flag streaming physics errors when streaming is off', () => {
    const draft = baseDraft();
    draft.turns[0]!.response.streaming = false;
    draft.turns[0]!.response.streamingPhysics = { tokensPerSecond: 0 };
    expect(hasRangeErrors(draft.turns)).toBe(false);
  });
});
