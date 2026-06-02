/**
 * Regression coverage for bugs found by adversarial review of the LLM-mock code generation
 * (llmExpectationCodegen.ts + conversationCodegen.ts):
 *
 *  - single-expectation Java referenced the Provider enum without importing it.
 *  - conversation Java referenced ParsedMessage.Role without importing ParsedMessage.
 *  - conversation Java emitted Double-typed chaos args as int literals (no autobox).
 *  - conversation JSON nested scenarioName/scenarioState/newScenarioState inside
 *    httpLlmResponse; they are top-level Expectation fields and the server (additionalProperties
 *    :false + validation) rejects the payload otherwise.
 *  - the round-trip parser read streaming from the top level instead of inside completion.
 */
import { describe, it, expect } from 'vitest';
import { expectationToJava } from '../lib/llmExpectationCodegen';
import type { ExpectationDraft } from '../lib/expectationFromCapture';
import {
  conversationToJava,
  conversationToJson,
  draftFromScenarioExpectations,
  type ConversationDraft,
} from '../lib/conversationCodegen';

function singleDraft(overrides?: Partial<ExpectationDraft>): ExpectationDraft {
  return {
    provider: 'ANTHROPIC', path: '/v1/messages', model: 'claude-sonnet-4-20250514',
    text: 'hi', toolCalls: [], stopReason: 'end_turn', streaming: false, ...overrides,
  };
}

function convDraft(overrides?: Partial<ConversationDraft>): ConversationDraft {
  return {
    provider: 'ANTHROPIC', path: '/v1/messages', model: 'claude-sonnet-4-20250514',
    turns: [{
      predicates: { turnIndex: 0 },
      response: { text: 'hello', toolCalls: [], stopReason: 'end_turn', streaming: false },
    }],
    ...overrides,
  };
}

describe('single-expectation Java imports the Provider enum', () => {
  it('emits import org.mockserver.model.Provider', () => {
    const java = expectationToJava(singleDraft());
    expect(java).toContain('.withProvider(Provider.ANTHROPIC)');
    expect(java).toContain('import org.mockserver.model.Provider;');
  });
});

describe('conversation Java imports ParsedMessage only when a role predicate is used', () => {
  it('imports ParsedMessage when a latestMessageRole predicate is present', () => {
    const d = convDraft();
    d.turns[0]!.predicates.latestMessageRole = 'USER';
    const java = conversationToJava(d);
    expect(java).toContain('.whenLatestMessageRole(ParsedMessage.Role.USER)');
    expect(java).toContain('import org.mockserver.llm.ParsedMessage;');
  });

  it('omits the ParsedMessage import when no role predicate is used', () => {
    expect(conversationToJava(convDraft())).not.toContain('import org.mockserver.llm.ParsedMessage;');
  });
});

describe('conversation Java renders Double chaos args as decimal literals', () => {
  it('emits whole-number errorProbability/truncateAtFraction with a decimal point', () => {
    const d = convDraft();
    d.turns[0]!.chaos = { errorProbability: 1, truncateMode: 'MID_STREAM', truncateAtFraction: 1 };
    const java = conversationToJava(d);
    expect(java).toContain('.withErrorProbability(1.0)');
    expect(java).toContain('.withTruncateAtFraction(1.0)');
    // a genuine fraction is passed through unchanged
    d.turns[0]!.chaos = { errorProbability: 0.3 };
    expect(conversationToJava(d)).toContain('.withErrorProbability(0.3)');
  });
});

describe('conversation Java formats nested builders across indented lines', () => {
  it('breaks withChaos / withNormalization onto multiple lines rather than one long call', () => {
    const d = convDraft();
    d.turns[0]!.predicates.normalization = { collapseWhitespace: true, lowercase: true };
    d.turns[0]!.chaos = { errorStatus: 503, seed: 7 };
    const java = conversationToJava(d);
    // factory call sits on its own line after the opening paren, not inline
    expect(java).toMatch(/\.withChaos\(\s*\n\s*org\.mockserver\.model\.LlmChaosProfile\.llmChaosProfile\(\)/);
    expect(java).toMatch(/\.withNormalization\(\s*\n\s*org\.mockserver\.model\.NormalizationOptions\.normalizationOptions\(\)/);
    expect(java).toMatch(/\.respondingWith\(\s*\n\s*completion\(\)/);
    // no generated line should be an overlong single-call blob
    expect(java.split('\n').every((l) => l.length <= 120)).toBe(true);
  });
});

describe('conversation JSON places scenario fields at the expectation top level', () => {
  it('does not nest scenarioName/scenarioState/newScenarioState in httpLlmResponse', () => {
    const parsed = JSON.parse(conversationToJson(convDraft({
      turns: [
        { predicates: { turnIndex: 0 }, response: { text: 'a', toolCalls: [], stopReason: '', streaming: false } },
        { predicates: { turnIndex: 1 }, response: { text: 'b', toolCalls: [], stopReason: '', streaming: false } },
      ],
    })));
    for (const exp of parsed) {
      expect(exp.httpLlmResponse).not.toHaveProperty('scenarioName');
      expect(exp.httpLlmResponse).not.toHaveProperty('scenarioState');
      expect(exp.httpLlmResponse).not.toHaveProperty('newScenarioState');
      expect(typeof exp.scenarioName).toBe('string');
    }
    expect(parsed[0].scenarioState).toBe('Started');
    expect(parsed[1].newScenarioState).toBe('__done');
  });
});

describe('streaming round-trips through draftFromScenarioExpectations', () => {
  it('recovers streaming:true from the completion object', () => {
    const d = convDraft();
    d.turns[0]!.response.streaming = true;
    const json = JSON.parse(conversationToJson(d)) as Record<string, unknown>[];
    // sanity: the generator writes streaming inside completion
    const llm = json[0]!['httpLlmResponse'] as Record<string, unknown>;
    expect((llm['completion'] as Record<string, unknown>)['streaming']).toBe(true);
    // and the parser recovers it
    const { draft } = draftFromScenarioExpectations(json.map((value, i) => ({ key: `k${i}`, value })));
    expect(draft.turns[0]!.response.streaming).toBe(true);
  });
});
