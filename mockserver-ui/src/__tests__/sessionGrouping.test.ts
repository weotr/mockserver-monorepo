import { describe, it, expect } from 'vitest';
import { groupBySession, parseIsolationSource, shortenScenarioName } from '../lib/sessionGrouping';
import type { JsonListItem } from '../types';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function makeAnthropicRequest(key: string, headers?: Array<{ name: string; values: string[] }>, queryParams?: unknown, cookies?: unknown): JsonListItem {
  return {
    key,
    value: {
      httpRequest: {
        method: 'POST',
        path: '/v1/messages',
        headers: headers ?? [{ name: 'host', values: ['api.anthropic.com'] }],
        ...(queryParams ? { queryStringParameters: queryParams } : {}),
        ...(cookies ? { cookies } : {}),
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
            model: 'claude-sonnet-4-20250514',
            content: [{ type: 'text', text: 'Hi!' }],
            usage: { input_tokens: 10, output_tokens: 5 },
            stop_reason: 'end_turn',
          }),
        },
      },
    },
  };
}

function makeExpectation(scenarioName: string): JsonListItem {
  return {
    key: `exp-${scenarioName}`,
    value: {
      // scenarioName lives at the top level of the expectation payload,
      // matching the real MockServer active-expectation shape.
      scenarioName,
      scenarioState: 'Started',
      newScenarioState: 'turn_1',
      httpLlmResponse: {
        provider: 'ANTHROPIC',
        model: 'claude-sonnet-4-20250514',
        conversationPredicates: { turnIndex: 0 },
        completion: { text: 'Hello!', stopReason: 'end_turn' },
      },
    },
  };
}

function makeGenericRequest(key: string): JsonListItem {
  return {
    key,
    value: {
      httpRequest: {
        method: 'GET',
        path: '/api/health',
      },
      httpResponse: {
        statusCode: 200,
      },
    },
  };
}

// ---------------------------------------------------------------------------
// parseIsolationSource
// ---------------------------------------------------------------------------

describe('parseIsolationSource', () => {
  it('parses header-keyed isolation from scenario name', () => {
    const result = parseIsolationSource('__llm_conv_myConv__iso=header:x-agent-id');
    expect(result).toEqual({
      scenarioName: '__llm_conv_myConv__iso=header:x-agent-id',
      baseName: '__llm_conv_myConv',
      sourceType: 'header',
      sourceKey: 'x-agent-id',
    });
  });

  it('parses query_parameter-keyed isolation', () => {
    const result = parseIsolationSource('__llm_conv_chat__iso=query_parameter:session_id');
    expect(result).toEqual({
      scenarioName: '__llm_conv_chat__iso=query_parameter:session_id',
      baseName: '__llm_conv_chat',
      sourceType: 'query_parameter',
      sourceKey: 'session_id',
    });
  });

  it('parses cookie-keyed isolation', () => {
    const result = parseIsolationSource('conv1__iso=cookie:sid');
    expect(result).toEqual({
      scenarioName: 'conv1__iso=cookie:sid',
      baseName: 'conv1',
      sourceType: 'cookie',
      sourceKey: 'sid',
    });
  });

  it('returns null for scenario name without isolation', () => {
    expect(parseIsolationSource('__llm_conv_myConv')).toBeNull();
  });

  it('returns null for empty string', () => {
    expect(parseIsolationSource('')).toBeNull();
  });
});

// ---------------------------------------------------------------------------
// shortenScenarioName
// ---------------------------------------------------------------------------

describe('shortenScenarioName', () => {
  it('strips __llm_conv_ prefix and __iso= suffix', () => {
    expect(shortenScenarioName('__llm_conv_myConv__iso=header:x-agent-id')).toBe('myConv');
  });

  it('strips __llm_conv_ prefix only when no isolation suffix', () => {
    expect(shortenScenarioName('__llm_conv_myConv')).toBe('myConv');
  });

  it('returns original name when no prefixes', () => {
    expect(shortenScenarioName('plain_name')).toBe('plain_name');
  });
});

// ---------------------------------------------------------------------------
// groupBySession — header-keyed
// ---------------------------------------------------------------------------

describe('groupBySession — header-keyed grouping', () => {
  it('groups requests by header isolation key into separate sessions', () => {
    const expectations = [
      makeExpectation('__llm_conv_chat__iso=header:x-agent-id'),
    ];

    const requests = [
      makeAnthropicRequest('req-1', [
        { name: 'host', values: ['api.anthropic.com'] },
        { name: 'x-agent-id', values: ['agent-A'] },
      ]),
      makeAnthropicRequest('req-2', [
        { name: 'host', values: ['api.anthropic.com'] },
        { name: 'x-agent-id', values: ['agent-B'] },
      ]),
      makeAnthropicRequest('req-3', [
        { name: 'host', values: ['api.anthropic.com'] },
        { name: 'x-agent-id', values: ['agent-A'] },
      ]),
    ];

    const sessions = groupBySession(requests, expectations);

    // Two isolated sessions (agent-A and agent-B)
    expect(sessions).toHaveLength(2);

    const agentA = sessions.find((s) => s.isolationKey === 'agent-A');
    const agentB = sessions.find((s) => s.isolationKey === 'agent-B');

    expect(agentA).toBeDefined();
    expect(agentA!.requests).toHaveLength(2);
    expect(agentA!.scenarioName).toBe('__llm_conv_chat__iso=header:x-agent-id');

    expect(agentB).toBeDefined();
    expect(agentB!.requests).toHaveLength(1);
  });

  it('puts requests without the isolation header into unscoped session', () => {
    const expectations = [
      makeExpectation('__llm_conv_chat__iso=header:x-agent-id'),
    ];

    const requests = [
      makeAnthropicRequest('req-1', [
        { name: 'host', values: ['api.anthropic.com'] },
        { name: 'x-agent-id', values: ['agent-A'] },
      ]),
      // This request has no x-agent-id header
      makeAnthropicRequest('req-2', [
        { name: 'host', values: ['api.anthropic.com'] },
      ]),
    ];

    const sessions = groupBySession(requests, expectations);

    expect(sessions).toHaveLength(2);
    const unscoped = sessions.find((s) => s.scenarioName === '<unscoped>');
    expect(unscoped).toBeDefined();
    expect(unscoped!.requests).toHaveLength(1);
  });
});

// ---------------------------------------------------------------------------
// groupBySession — query_parameter-keyed
// ---------------------------------------------------------------------------

describe('groupBySession — query_parameter-keyed grouping', () => {
  it('groups requests by query parameter isolation key', () => {
    const expectations = [
      makeExpectation('__llm_conv_chat__iso=query_parameter:session_id'),
    ];

    const requests = [
      makeAnthropicRequest('req-1', undefined, [
        { name: 'session_id', values: ['sess-123'] },
      ]),
      makeAnthropicRequest('req-2', undefined, [
        { name: 'session_id', values: ['sess-456'] },
      ]),
    ];

    const sessions = groupBySession(requests, expectations);

    const sess123 = sessions.find((s) => s.isolationKey === 'sess-123');
    const sess456 = sessions.find((s) => s.isolationKey === 'sess-456');

    expect(sess123).toBeDefined();
    expect(sess123!.requests).toHaveLength(1);
    expect(sess456).toBeDefined();
    expect(sess456!.requests).toHaveLength(1);
  });
});

// ---------------------------------------------------------------------------
// groupBySession — cookie-keyed
// ---------------------------------------------------------------------------

describe('groupBySession — cookie-keyed grouping', () => {
  it('groups requests by cookie isolation key', () => {
    const expectations = [
      makeExpectation('conv__iso=cookie:sid'),
    ];

    const requests = [
      makeAnthropicRequest('req-1', undefined, undefined, [
        { name: 'sid', value: 'cookie-A' },
      ]),
      makeAnthropicRequest('req-2', undefined, undefined, [
        { name: 'sid', value: 'cookie-B' },
      ]),
    ];

    const sessions = groupBySession(requests, expectations);

    const cookieA = sessions.find((s) => s.isolationKey === 'cookie-A');
    const cookieB = sessions.find((s) => s.isolationKey === 'cookie-B');

    expect(cookieA).toBeDefined();
    expect(cookieA!.requests).toHaveLength(1);
    expect(cookieB).toBeDefined();
    expect(cookieB!.requests).toHaveLength(1);
  });
});

// ---------------------------------------------------------------------------
// groupBySession — expectation without isolation
// ---------------------------------------------------------------------------

describe('groupBySession — expectation without isolation', () => {
  it('groups unscoped LLM requests by host when no isolation is configured', () => {
    const expectations = [
      makeExpectation('__llm_conv_chat'), // no __iso= suffix
    ];

    const requests = [
      makeAnthropicRequest('req-1'),
      makeAnthropicRequest('req-2'),
    ];

    const sessions = groupBySession(requests, expectations);

    // All go to unscoped since no isolation source matches, but grouped
    // by host (proxy-aware fallback)
    expect(sessions).toHaveLength(1);
    expect(sessions[0]!.scenarioName).toBe('<unscoped>');
    // The host header is the isolation key fallback
    expect(sessions[0]!.isolationKey).toBe('api.anthropic.com');
    expect(sessions[0]!.requests).toHaveLength(2);
  });
});

// ---------------------------------------------------------------------------
// groupBySession — proxy-aware fallback grouping by host
// ---------------------------------------------------------------------------

describe('groupBySession — proxy-aware host grouping', () => {
  it('groups unscoped proxy traffic by upstream host', () => {
    // No isolation expectations — pure proxy mode
    const expectations: JsonListItem[] = [];

    const requests = [
      makeAnthropicRequest('req-1', [{ name: 'host', values: ['api.anthropic.com'] }]),
      makeAnthropicRequest('req-2', [{ name: 'host', values: ['api.openai.com'] }]),
      makeAnthropicRequest('req-3', [{ name: 'host', values: ['api.anthropic.com'] }]),
    ];

    // Patch the second request to look like OpenAI traffic
    (requests[1]!.value['httpRequest'] as Record<string, unknown>)['path'] = '/v1/chat/completions';

    const sessions = groupBySession(requests, expectations);

    // Two unscoped sessions grouped by host
    expect(sessions).toHaveLength(2);

    const anthropic = sessions.find((s) => s.isolationKey === 'api.anthropic.com');
    const openai = sessions.find((s) => s.isolationKey === 'api.openai.com');

    expect(anthropic).toBeDefined();
    expect(anthropic!.scenarioName).toBe('<unscoped>');
    expect(anthropic!.requests).toHaveLength(2);

    expect(openai).toBeDefined();
    expect(openai!.scenarioName).toBe('<unscoped>');
    expect(openai!.requests).toHaveLength(1);
  });

  it('puts requests without host header into plain unscoped session', () => {
    const expectations: JsonListItem[] = [];

    // Request with no host header at all
    const requests = [
      makeAnthropicRequest('req-1', []), // empty headers
    ];

    const sessions = groupBySession(requests, expectations);

    expect(sessions).toHaveLength(1);
    expect(sessions[0]!.scenarioName).toBe('<unscoped>');
    expect(sessions[0]!.isolationKey).toBe('<unscoped>');
    expect(sessions[0]!.requests).toHaveLength(1);
  });
});

// ---------------------------------------------------------------------------
// groupBySession — non-LLM traffic is excluded
// ---------------------------------------------------------------------------

describe('groupBySession — non-LLM traffic excluded', () => {
  it('ignores non-LLM requests (generic HTTP)', () => {
    const expectations = [
      makeExpectation('__llm_conv_chat__iso=header:x-agent-id'),
    ];

    const requests = [
      makeAnthropicRequest('req-1', [
        { name: 'host', values: ['api.anthropic.com'] },
        { name: 'x-agent-id', values: ['agent-A'] },
      ]),
      makeGenericRequest('req-2'),
    ];

    const sessions = groupBySession(requests, expectations);

    // Only 1 session with the LLM request; generic excluded
    expect(sessions).toHaveLength(1);
    expect(sessions[0]!.requests).toHaveLength(1);
  });
});

// ---------------------------------------------------------------------------
// groupBySession — empty inputs
// ---------------------------------------------------------------------------

describe('groupBySession — empty inputs', () => {
  it('returns empty array when no proxied requests', () => {
    const sessions = groupBySession([], []);
    expect(sessions).toHaveLength(0);
  });

  it('returns empty array when no LLM traffic exists', () => {
    const sessions = groupBySession([makeGenericRequest('req-1')], []);
    expect(sessions).toHaveLength(0);
  });
});
