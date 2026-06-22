/**
 * Client-side filter utilities for action type and LLM provider dimensions.
 * Applied to activeExpectations and other items in the UI.
 */

import type { StringBodyMatcher } from '../types';

/**
 * Build the request-body matcher for the "Body contains" filter.
 *
 * It must do a substring (contains) match, not full-body equality: the filter
 * object is serialized straight onto the WebSocket as the HttpRequest matcher,
 * and the server's BodyDTODeserializer turns a bare string into a StringBody
 * with `subString=false` (exact match) — which would contradict the "Body
 * contains" label. Emitting the explicit STRING matcher with `subString: true`
 * makes the server build a contains-matching StringBody instead.
 */
export function buildBodyMatcher(text: string): StringBodyMatcher {
  return { type: 'STRING', string: text, subString: true };
}

/**
 * Reference implementation of how the server's STRING body matcher decides a
 * match, used as a behavioural oracle in tests. `subString: true` → contains;
 * `subString: false` → exact equality. Mirrors StringBody on the server.
 */
export function matchesStringBody(matcher: StringBodyMatcher, actualBody: string): boolean {
  return matcher.subString ? actualBody.includes(matcher.string) : actualBody === matcher.string;
}

export const ACTION_TYPES = [
  'httpResponse',
  'httpForward',
  'httpLlmResponse',
  'httpSseResponse',
  'httpError',
  'httpClassCallback',
  'httpObjectCallback',
] as const;

export const LLM_PROVIDERS = [
  'ANTHROPIC',
  'OPENAI',
  'OPENAI_RESPONSES',
  'GEMINI',
  'BEDROCK',
  'AZURE_OPENAI',
  'OLLAMA',
] as const;

export const PROVIDER_DISPLAY: Record<string, string> = {
  ANTHROPIC: 'Anthropic',
  OPENAI: 'OpenAI',
  OPENAI_RESPONSES: 'OpenAI Responses',
  GEMINI: 'Gemini',
  BEDROCK: 'Bedrock',
  AZURE_OPENAI: 'Azure OpenAI',
  OLLAMA: 'Ollama',
};

/**
 * Determine the action type of an expectation value.
 * Returns the first action key found (e.g. 'httpResponse', 'httpLlmResponse').
 */
export function getActionType(value: Record<string, unknown>): string | null {
  for (const key of ACTION_TYPES) {
    if (key in value) return key;
  }
  return null;
}

/**
 * Get the LLM provider from an expectation value, if it has httpLlmResponse.
 */
export function getLlmProvider(value: Record<string, unknown>): string | null {
  const llm = value['httpLlmResponse'] as Record<string, unknown> | undefined;
  if (!llm) return null;
  return (llm['provider'] as string | undefined) ?? null;
}

/**
 * Apply client-side action-type + LLM-provider filters to a list of items.
 * Semantics: AND across dimensions, OR within a dimension.
 * Empty filter array means "no constraint" (pass all).
 */
export function applyClientFilters<T extends { value: Record<string, unknown> }>(
  items: T[],
  actionTypes: string[],
  llmProviders: string[],
): T[] {
  if (actionTypes.length === 0 && llmProviders.length === 0) return items;

  return items.filter((item) => {
    if (actionTypes.length > 0) {
      const action = getActionType(item.value);
      if (!action || !actionTypes.includes(action)) return false;
    }
    if (llmProviders.length > 0) {
      const provider = getLlmProvider(item.value);
      if (!provider || !llmProviders.includes(provider)) return false;
    }
    return true;
  });
}
