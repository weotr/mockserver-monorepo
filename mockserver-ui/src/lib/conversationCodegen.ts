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

/**
 * Opt-in, deterministic normalisation applied to the latest-message text (and
 * the latestMessageContains value) before the text predicates are evaluated, so
 * cosmetic differences in dynamically-assembled agent prompts do not block a
 * match. Mirrors org.mockserver.model.NormalizationOptions.
 */
export interface NormalizationDraft {
  collapseWhitespace?: boolean;
  lowercase?: boolean;
  sortJsonKeys?: boolean;
  dropBuiltInVolatileFields?: boolean;
  dropVolatileFields?: string[];
}

export interface TurnMatchPredicates {
  turnIndex?: number;
  latestMessageContains?: string;
  latestMessageMatches?: string;
  latestMessageRole?: 'USER' | 'ASSISTANT' | 'TOOL' | 'SYSTEM';
  containsToolResultFor?: string;
  normalization?: NormalizationDraft;
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

/**
 * Build the wire `normalization` object for JSON / MCP, or undefined when the
 * draft carries nothing meaningful. Booleans are emitted only when defined so
 * the backend defaults (collapseWhitespace / sortJsonKeys on) apply otherwise.
 */
function normalizationToObject(n: NormalizationDraft | undefined): Record<string, unknown> | undefined {
  if (!n) return undefined;
  const obj: Record<string, unknown> = {};
  if (n.collapseWhitespace != null) obj['collapseWhitespace'] = n.collapseWhitespace;
  if (n.lowercase != null) obj['lowercase'] = n.lowercase;
  if (n.sortJsonKeys != null) obj['sortJsonKeys'] = n.sortJsonKeys;
  if (n.dropBuiltInVolatileFields != null) obj['dropBuiltInVolatileFields'] = n.dropBuiltInVolatileFields;
  if (n.dropVolatileFields && n.dropVolatileFields.length > 0) obj['dropVolatileFields'] = n.dropVolatileFields;
  return Object.keys(obj).length > 0 ? obj : undefined;
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
  // Generated regex predicates use java.util.regex.Pattern.
  if (draft.turns.some((t) => t.predicates.latestMessageMatches)) {
    lines.push('import java.util.regex.Pattern;');
  }
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
      lines.push(`        .whenLatestMessageMatches(Pattern.compile("${escapeJava(turn.predicates.latestMessageMatches)}"))`);
    }
    if (turn.predicates.latestMessageRole) {
      lines.push(`        .whenLatestMessageRole(ParsedMessage.Role.${turn.predicates.latestMessageRole})`);
    }
    if (turn.predicates.containsToolResultFor) {
      lines.push(`        .whenContainsToolResultFor("${escapeJava(turn.predicates.containsToolResultFor)}")`);
    }
    const normObj = normalizationToObject(turn.predicates.normalization);
    if (normObj) {
      const n = turn.predicates.normalization!;
      const normParts: string[] = ['org.mockserver.model.NormalizationOptions.normalizationOptions()'];
      if (n.collapseWhitespace != null) normParts.push(`.withCollapseWhitespace(${n.collapseWhitespace})`);
      if (n.lowercase != null) normParts.push(`.withLowercase(${n.lowercase})`);
      if (n.sortJsonKeys != null) normParts.push(`.withSortJsonKeys(${n.sortJsonKeys})`);
      if (n.dropBuiltInVolatileFields != null) normParts.push(`.withDropBuiltInVolatileFields(${n.dropBuiltInVolatileFields})`);
      if (n.dropVolatileFields && n.dropVolatileFields.length > 0) {
        const list = n.dropVolatileFields.map((f) => `"${escapeJava(f)}"`).join(', ');
        normParts.push(`.withDropVolatileFields(java.util.Arrays.asList(${list}))`);
      }
      lines.push(`        .withNormalization(${normParts.join('')})`);
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
    const jsonNorm = normalizationToObject(turn.predicates.normalization);
    if (jsonNorm) {
      predicates['normalization'] = jsonNorm;
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
 *
 * Optional `existingIds` lets callers upsert an existing conversation in
 * place rather than allocating fresh expectation IDs — used by the
 * dashboard's "edit existing conversation" flow. The IDs are passed
 * positionally (ids[i] is applied to the i-th generated expectation).
 */
export function conversationToMcpArgs(
  draft: ConversationDraft,
  existingIds?: string[],
): Record<string, unknown> {
  const args: Record<string, unknown> = {
    provider: draft.provider,
    path: draft.path,
  };
  if (existingIds && existingIds.length > 0) {
    args['ids'] = existingIds;
  }
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
    if (turn.predicates.latestMessageMatches) {
      match['latestMessageMatches'] = turn.predicates.latestMessageMatches;
    }
    if (turn.predicates.latestMessageRole) {
      match['latestMessageRole'] = turn.predicates.latestMessageRole;
    }
    if (turn.predicates.containsToolResultFor) {
      match['containsToolResultFor'] = turn.predicates.containsToolResultFor;
    }
    const mcpNorm = normalizationToObject(turn.predicates.normalization);
    if (mcpNorm) {
      match['normalization'] = mcpNorm;
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

// ---------------------------------------------------------------------------
// Reverse direction: rebuild a ConversationDraft from the active expectations
// belonging to a conversation scenarioName. Used by the wizard's "load
// existing" picker so the user can edit a previously-registered conversation
// instead of starting fresh.
// ---------------------------------------------------------------------------

const SCENARIO_STATE_ORDER = (state: string): number => {
  if (state === 'Started') return -1;
  const m = /^turn_(\d+)$/.exec(state);
  if (m) return parseInt(m[1]!, 10);
  return 1_000_000;
};

interface ScenarioExpectationLike {
  key: string;
  value: Record<string, unknown>;
}

export interface DraftFromScenarioResult {
  draft: ConversationDraft;
  ids: string[];
}

const PROVIDER_NAMES: readonly ProviderName[] = [
  'ANTHROPIC',
  'OPENAI',
  'OPENAI_RESPONSES',
  'GEMINI',
  'BEDROCK',
  'AZURE_OPENAI',
  'OLLAMA',
];

function parseIsolationFromScenarioName(scenarioName: string): IsolationConfig | undefined {
  // Wire format: `<base>__iso=<source>:<name>` where source is one of
  // header / query_parameter / cookie. The MCP tool expects the camelCase
  // queryParameter variant.
  const m = /__iso=(header|query_parameter|cookie):(.+)$/.exec(scenarioName);
  if (!m) return undefined;
  const wireSource = m[1]!;
  const source: IsolationConfig['source'] = wireSource === 'query_parameter' ? 'queryParameter' : (wireSource as IsolationConfig['source']);
  return { source, name: m[2]! };
}

/**
 * Group active expectations by scenarioName and return distinct LLM
 * conversation scenarios (≥ 1 expectation with httpLlmResponse +
 * scenarioName) along with the underlying items, ordered by scenario
 * state.
 */
export function listConversationScenarios(items: ScenarioExpectationLike[]): Array<{
  scenarioName: string;
  shortName: string;
  expectations: ScenarioExpectationLike[];
}> {
  const groups = new Map<string, ScenarioExpectationLike[]>();
  for (const item of items) {
    const scenarioName = item.value['scenarioName'];
    if (typeof scenarioName !== 'string') continue;
    const llm = item.value['httpLlmResponse'];
    if (!llm) continue;
    const arr = groups.get(scenarioName) ?? [];
    arr.push(item);
    groups.set(scenarioName, arr);
  }
  return Array.from(groups.entries()).map(([scenarioName, expectations]) => {
    const sorted = [...expectations].sort((a, b) => {
      const aState = (a.value['scenarioState'] as string | undefined) ?? '';
      const bState = (b.value['scenarioState'] as string | undefined) ?? '';
      return SCENARIO_STATE_ORDER(aState) - SCENARIO_STATE_ORDER(bState);
    });
    const shortName = scenarioName.replace(/^__llm_conv_/, '').replace(/__iso=.*$/, '');
    return { scenarioName, shortName, expectations: sorted };
  });
}

function pickProvider(raw: unknown): ProviderName {
  if (typeof raw === 'string') {
    const upper = raw.toUpperCase() as ProviderName;
    if (PROVIDER_NAMES.includes(upper)) return upper;
  }
  return 'ANTHROPIC';
}

function parseNormalization(raw: unknown): NormalizationDraft | undefined {
  if (raw == null || typeof raw !== 'object') return undefined;
  const r = raw as Record<string, unknown>;
  const draft: NormalizationDraft = {};
  if (typeof r['collapseWhitespace'] === 'boolean') draft.collapseWhitespace = r['collapseWhitespace'];
  if (typeof r['lowercase'] === 'boolean') draft.lowercase = r['lowercase'];
  if (typeof r['sortJsonKeys'] === 'boolean') draft.sortJsonKeys = r['sortJsonKeys'];
  if (typeof r['dropBuiltInVolatileFields'] === 'boolean') draft.dropBuiltInVolatileFields = r['dropBuiltInVolatileFields'];
  if (Array.isArray(r['dropVolatileFields'])) {
    draft.dropVolatileFields = (r['dropVolatileFields'] as unknown[]).filter((f): f is string => typeof f === 'string');
  }
  return Object.keys(draft).length > 0 ? draft : undefined;
}

/**
 * Convert a group of expectations sharing a scenarioName back into a
 * ConversationDraft plus the ordered list of expectation IDs. Lossy by
 * design — predicates beyond turnIndex / latestMessageContains /
 * latestMessageMatches / latestMessageRole / containsToolResultFor (plus
 * optional normalization) are dropped, since the wizard only exposes those
 * match predicates.
 */
export function draftFromScenarioExpectations(
  expectations: ScenarioExpectationLike[],
): DraftFromScenarioResult {
  if (expectations.length === 0) {
    throw new Error('draftFromScenarioExpectations requires at least one expectation');
  }
  const first = expectations[0]!;
  const firstReq = (first.value['httpRequest'] as Record<string, unknown> | undefined) ?? {};
  const firstLlm = (first.value['httpLlmResponse'] as Record<string, unknown> | undefined) ?? {};
  const scenarioName = (first.value['scenarioName'] as string | undefined) ?? '';

  const path = typeof firstReq['path'] === 'string'
    ? (firstReq['path'] as string)
    : '';
  const model = typeof firstLlm['model'] === 'string' ? (firstLlm['model'] as string) : '';
  const provider = pickProvider(firstLlm['provider']);
  const isolateBy = parseIsolationFromScenarioName(scenarioName);

  const turns: TurnDraft[] = expectations.map((exp) => {
    const llm = (exp.value['httpLlmResponse'] as Record<string, unknown> | undefined) ?? {};
    const predicates = (llm['conversationPredicates'] as Record<string, unknown> | undefined) ?? {};
    const completion = (llm['completion'] as Record<string, unknown> | undefined) ?? {};
    const toolCalls = Array.isArray(completion['toolCalls'])
      ? (completion['toolCalls'] as Array<Record<string, unknown>>).map((tc) => ({
          name: typeof tc['name'] === 'string' ? (tc['name'] as string) : '',
          arguments: typeof tc['arguments'] === 'string'
            ? (tc['arguments'] as string)
            : tc['arguments'] != null ? JSON.stringify(tc['arguments']) : '',
        }))
      : [];
    return {
      predicates: {
        turnIndex: typeof predicates['turnIndex'] === 'number' ? (predicates['turnIndex'] as number) : undefined,
        latestMessageContains: typeof predicates['latestMessageContains'] === 'string'
          ? (predicates['latestMessageContains'] as string) : undefined,
        latestMessageMatches: typeof predicates['latestMessageMatches'] === 'string'
          ? (predicates['latestMessageMatches'] as string) : undefined,
        latestMessageRole: typeof predicates['latestMessageRole'] === 'string'
          ? (predicates['latestMessageRole'] as 'USER' | 'ASSISTANT' | 'TOOL' | 'SYSTEM') : undefined,
        containsToolResultFor: typeof predicates['containsToolResultFor'] === 'string'
          ? (predicates['containsToolResultFor'] as string) : undefined,
        normalization: parseNormalization(predicates['normalization']),
      },
      response: {
        text: typeof completion['text'] === 'string' ? (completion['text'] as string) : '',
        toolCalls,
        stopReason: typeof completion['stopReason'] === 'string' ? (completion['stopReason'] as string) : '',
        streaming: llm['streaming'] === true,
      },
    };
  });

  return {
    draft: { provider, path, model, isolateBy, turns },
    ids: expectations.map((e) => (typeof e.value['id'] === 'string' ? (e.value['id'] as string) : e.key)),
  };
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
