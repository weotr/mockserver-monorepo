/**
 * Pure codegen functions for multi-turn LLM conversation mocks.
 *
 * Produces Java / JSON / MCP representations matching the actual API surface:
 *   - Java:  LlmConversationBuilder from org.mockserver.client
 *   - JSON:  Expectation[] for PUT /mockserver/expectation
 *   - MCP:   create_llm_conversation JSON-RPC tools/call envelope
 */

import type { ProviderName, ToolCallDraft } from './expectationFromCapture';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface IsolationConfig {
  source: 'header' | 'queryParameter' | 'cookie';
  name: string;
}

export interface TurnMatchPredicates {
  turnIndex?: number;
  latestMessageContains?: string;
  latestMessageMatches?: string;
  latestMessageRole?: 'USER' | 'ASSISTANT' | 'TOOL' | 'SYSTEM';
  containsToolResultFor?: string;
}

export interface TurnResponse {
  text: string;
  toolCalls: ToolCallDraft[];
  stopReason: string;
  streaming: boolean;
}

export interface TurnDraft {
  predicates: TurnMatchPredicates;
  response: TurnResponse;
}

export interface ConversationDraft {
  provider: ProviderName;
  path: string;
  model: string;
  isolateBy?: IsolationConfig;
  turns: TurnDraft[];
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function escapeJava(s: string): string {
  return s
    .replace(/\\/g, '\\\\')
    .replace(/"/g, '\\"')
    .replace(/\n/g, '\\n')
    .replace(/\r/g, '\\r')
    .replace(/\t/g, '\\t');
}

// ---------------------------------------------------------------------------
// Java codegen
// ---------------------------------------------------------------------------

/**
 * Produce Java code using LlmConversationBuilder.conversation().
 * Matches the exact API surface from org.mockserver.client.
 */
export function conversationToJava(draft: ConversationDraft): string {
  const lines: string[] = [];
  lines.push('import static org.mockserver.client.Llm.*;');
  lines.push('import org.mockserver.model.Provider;');
  lines.push('');

  // Start builder chain
  lines.push('conversation()');
  lines.push(`    .withPath("${escapeJava(draft.path)}")`);
  lines.push(`    .withProvider(Provider.${draft.provider})`);
  if (draft.model) {
    lines.push(`    .withModel("${escapeJava(draft.model)}")`);
  }
  if (draft.isolateBy) {
    lines.push(`    .isolateBy(${draft.isolateBy.source}("${escapeJava(draft.isolateBy.name)}"))`);
  }

  for (let i = 0; i < draft.turns.length; i++) {
    const turn = draft.turns[i]!;
    lines.push('    .turn()');

    // Predicates
    if (turn.predicates.turnIndex != null) {
      lines.push(`        .whenTurnIndex(${turn.predicates.turnIndex})`);
    }
    if (turn.predicates.latestMessageContains) {
      lines.push(`        .whenLatestMessageContains("${escapeJava(turn.predicates.latestMessageContains)}")`);
    }
    if (turn.predicates.latestMessageMatches) {
      lines.push(`        .whenLatestMessageContains(Pattern.compile("${escapeJava(turn.predicates.latestMessageMatches)}"))`);
    }
    if (turn.predicates.latestMessageRole) {
      lines.push(`        .whenLatestMessageRole(ParsedMessage.Role.${turn.predicates.latestMessageRole})`);
    }
    if (turn.predicates.containsToolResultFor) {
      lines.push(`        .whenContainsToolResultFor("${escapeJava(turn.predicates.containsToolResultFor)}")`);
    }

    // Response
    const completionParts: string[] = ['completion()'];
    if (turn.response.text) {
      completionParts.push(`.withText("${escapeJava(turn.response.text)}")`);
    }
    for (const tc of turn.response.toolCalls) {
      let toolUsePart = `toolUse("${escapeJava(tc.name)}")`;
      if (tc.arguments) {
        toolUsePart += `.withArguments("${escapeJava(tc.arguments)}")`;
      }
      completionParts.push(`.withToolCall(${toolUsePart})`);
    }
    if (turn.response.stopReason) {
      completionParts.push(`.withStopReason("${escapeJava(turn.response.stopReason)}")`);
    }
    if (turn.response.streaming) {
      completionParts.push('.streaming()');
    }

    lines.push(`        .respondingWith(${completionParts.join('\n            ')})`);

    if (i < draft.turns.length - 1) {
      lines.push('    .andThen()');
    }
  }

  lines.push('    .applyTo(mockServerClient);');

  return lines.join('\n');
}

// ---------------------------------------------------------------------------
// JSON codegen (Expectation[] for PUT /mockserver/expectation)
// ---------------------------------------------------------------------------

/**
 * Produce the raw Expectation[] JSON array.  Uses the same scenario-state
 * naming convention as LlmConversationBuilder.build().
 */
export function conversationToJson(draft: ConversationDraft): string {
  const scenarioName = '__llm_conv_<auto>';
  const expectations: Record<string, unknown>[] = [];

  for (let i = 0; i < draft.turns.length; i++) {
    const turn = draft.turns[i]!;
    const scenarioState = i === 0 ? 'Started' : `turn_${i}`;
    const newScenarioState = i < draft.turns.length - 1 ? `turn_${i + 1}` : '__done';

    const completion: Record<string, unknown> = {};
    if (turn.response.text) {
      completion['text'] = turn.response.text;
    }
    if (turn.response.toolCalls.length > 0) {
      completion['toolCalls'] = turn.response.toolCalls.map((tc) => {
        const obj: Record<string, unknown> = { name: tc.name };
        if (tc.arguments) {
          obj['arguments'] = tc.arguments;
        }
        return obj;
      });
    }
    if (turn.response.stopReason) {
      completion['stopReason'] = turn.response.stopReason;
    }
    if (turn.response.streaming) {
      completion['streaming'] = true;
    }

    const predicates: Record<string, unknown> = {};
    if (turn.predicates.turnIndex != null) {
      predicates['turnIndex'] = turn.predicates.turnIndex;
    }
    if (turn.predicates.latestMessageContains) {
      predicates['latestMessageContains'] = turn.predicates.latestMessageContains;
    }
    if (turn.predicates.latestMessageMatches) {
      predicates['latestMessageMatches'] = turn.predicates.latestMessageMatches;
    }
    if (turn.predicates.latestMessageRole) {
      predicates['latestMessageRole'] = turn.predicates.latestMessageRole;
    }
    if (turn.predicates.containsToolResultFor) {
      predicates['containsToolResultFor'] = turn.predicates.containsToolResultFor;
    }

    const llmResponse: Record<string, unknown> = {
      provider: draft.provider,
      scenarioName,
      scenarioState,
      newScenarioState,
    };
    if (draft.model) {
      llmResponse['model'] = draft.model;
    }
    if (Object.keys(completion).length > 0) {
      llmResponse['completion'] = completion;
    }
    if (Object.keys(predicates).length > 0) {
      llmResponse['conversationPredicates'] = predicates;
    }

    expectations.push({
      httpRequest: {
        method: 'POST',
        path: draft.path,
      },
      httpLlmResponse: llmResponse,
    });
  }

  return JSON.stringify(expectations, null, 2);
}

// ---------------------------------------------------------------------------
// MCP codegen (JSON-RPC tools/call envelope for create_llm_conversation)
// ---------------------------------------------------------------------------

/**
 * Produce the MCP `tools/call` arguments for `create_llm_conversation`.
 */
export function conversationToMcpArgs(draft: ConversationDraft): Record<string, unknown> {
  const args: Record<string, unknown> = {
    provider: draft.provider,
    path: draft.path,
  };
  if (draft.model) {
    args['model'] = draft.model;
  }
  if (draft.isolateBy) {
    args['isolateBy'] = {
      source: draft.isolateBy.source,
      name: draft.isolateBy.name,
    };
  }
  args['turns'] = draft.turns.map((turn) => {
    const turnObj: Record<string, unknown> = {};

    // Match predicates
    const match: Record<string, unknown> = {};
    if (turn.predicates.turnIndex != null) {
      match['turnIndex'] = turn.predicates.turnIndex;
    }
    if (turn.predicates.latestMessageContains) {
      match['latestMessageContains'] = turn.predicates.latestMessageContains;
    }
    if (turn.predicates.latestMessageRole) {
      match['latestMessageRole'] = turn.predicates.latestMessageRole;
    }
    if (turn.predicates.containsToolResultFor) {
      match['containsToolResultFor'] = turn.predicates.containsToolResultFor;
    }
    if (Object.keys(match).length > 0) {
      turnObj['match'] = match;
    }

    // Response
    const response: Record<string, unknown> = {};
    if (turn.response.text) {
      response['text'] = turn.response.text;
    }
    if (turn.response.toolCalls.length > 0) {
      response['toolCalls'] = turn.response.toolCalls.map((tc) => {
        const obj: Record<string, unknown> = { name: tc.name };
        if (tc.arguments) {
          obj['arguments'] = tc.arguments;
        }
        return obj;
      });
    }
    if (turn.response.stopReason) {
      response['stopReason'] = turn.response.stopReason;
    }
    if (turn.response.streaming) {
      response['streaming'] = true;
    }
    if (Object.keys(response).length > 0) {
      turnObj['response'] = response;
    }

    return turnObj;
  });

  return args;
}

/**
 * Produce the full JSON-RPC 2.0 envelope for the `create_llm_conversation` tool.
 */
export function conversationToMcpCall(draft: ConversationDraft): string {
  const envelope = {
    jsonrpc: '2.0',
    id: 1,
    method: 'tools/call',
    params: {
      name: 'create_llm_conversation',
      arguments: conversationToMcpArgs(draft),
    },
  };
  return JSON.stringify(envelope, null, 2);
}
