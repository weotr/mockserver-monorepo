/**
 * Pure function that extracts a mock expectation draft from a captured
 * LLM request/response pair parsed by `llmTraffic.ts`.
 *
 * The resulting draft contains sensible defaults that a user can edit
 * before registering on the server.
 */

import type {
  ParsedTraffic,
  AnthropicParsed,
  OpenAiParsed,
  OpenAiResponsesParsed,
  GeminiParsed,
  OllamaParsed,
} from './llmTraffic';

// ---------------------------------------------------------------------------
// Provider enum (matches Java org.mockserver.model.Provider)
// ---------------------------------------------------------------------------

export const PROVIDERS = [
  'ANTHROPIC',
  'OPENAI',
  'OPENAI_RESPONSES',
  'GEMINI',
  'BEDROCK',
  'AZURE_OPENAI',
  'OLLAMA',
] as const;

export type ProviderName = (typeof PROVIDERS)[number];

// ---------------------------------------------------------------------------
// Expectation draft — the editable shape surfaced in the dialog
// ---------------------------------------------------------------------------

export interface ToolCallDraft {
  name: string;
  arguments?: string;
}

export interface ExpectationDraft {
  path: string;
  provider: ProviderName;
  model: string;
  text: string;
  toolCalls: ToolCallDraft[];
  stopReason: string;
  streaming: boolean;
}

// ---------------------------------------------------------------------------
// Provider detection from ParsedTraffic kind
// ---------------------------------------------------------------------------

function kindToProvider(kind: ParsedTraffic['kind']): ProviderName {
  switch (kind) {
    case 'anthropic':
      return 'ANTHROPIC';
    case 'openai':
      return 'OPENAI';
    case 'openai_responses':
      return 'OPENAI_RESPONSES';
    case 'gemini':
      return 'GEMINI';
    case 'ollama':
      return 'OLLAMA';
    default:
      return 'ANTHROPIC'; // safe fallback
  }
}

// ---------------------------------------------------------------------------
// Per-provider extraction
// ---------------------------------------------------------------------------

function extractAnthropic(parsed: AnthropicParsed): Partial<ExpectationDraft> {
  const textParts: string[] = [];
  const toolCalls: ToolCallDraft[] = [];

  for (const block of parsed.responseContent) {
    if (block.type === 'text' && block.text) {
      textParts.push(block.text);
    }
    if (block.type === 'tool_use' && block.name) {
      const tc: ToolCallDraft = { name: block.name };
      if (block.input != null) {
        tc.arguments = typeof block.input === 'string'
          ? block.input
          : JSON.stringify(block.input);
      }
      toolCalls.push(tc);
    }
  }

  return {
    model: parsed.model ?? '',
    text: textParts.join(''),
    toolCalls,
    stopReason: parsed.stopReason ?? '',
    streaming: parsed.stream,
  };
}

function extractOpenAi(parsed: OpenAiParsed): Partial<ExpectationDraft> {
  const textParts: string[] = [];
  const toolCalls: ToolCallDraft[] = [];
  let stopReason = '';

  for (const choice of parsed.choices) {
    if (choice.message?.content) {
      textParts.push(choice.message.content);
    }
    if (choice.message?.tool_calls) {
      for (const tc of choice.message.tool_calls) {
        const tcObj = tc as Record<string, unknown>;
        const fn = tcObj['function'] as Record<string, unknown> | undefined;
        if (fn && typeof fn['name'] === 'string') {
          const draft: ToolCallDraft = { name: fn['name'] };
          if (typeof fn['arguments'] === 'string') {
            draft.arguments = fn['arguments'];
          }
          toolCalls.push(draft);
        }
      }
    }
    if (choice.finish_reason) {
      stopReason = choice.finish_reason;
    }
  }

  return {
    model: parsed.model ?? '',
    text: textParts.join(''),
    toolCalls,
    stopReason,
    streaming: parsed.stream,
  };
}

function extractOpenAiResponses(parsed: OpenAiResponsesParsed): Partial<ExpectationDraft> {
  const textParts: string[] = [];
  const toolCalls: ToolCallDraft[] = [];

  for (const item of parsed.output) {
    const obj = item as Record<string, unknown>;
    if (obj['type'] === 'message') {
      const content = obj['content'];
      if (Array.isArray(content)) {
        for (const c of content) {
          const co = c as Record<string, unknown>;
          if (co['type'] === 'output_text' && typeof co['text'] === 'string') {
            textParts.push(co['text']);
          }
        }
      }
    }
    if (obj['type'] === 'function_call') {
      const name = obj['name'];
      if (typeof name === 'string') {
        const draft: ToolCallDraft = { name };
        if (typeof obj['arguments'] === 'string') {
          draft.arguments = obj['arguments'];
        }
        toolCalls.push(draft);
      }
    }
  }

  return {
    model: parsed.model ?? '',
    text: textParts.join(''),
    toolCalls,
    stopReason: '',
    streaming: parsed.stream,
  };
}

function extractGemini(parsed: GeminiParsed): Partial<ExpectationDraft> {
  const textParts: string[] = [];
  const toolCalls: ToolCallDraft[] = [];

  for (const candidate of parsed.candidates) {
    const cObj = candidate as Record<string, unknown>;
    const content = cObj['content'] as Record<string, unknown> | undefined;
    if (content && Array.isArray(content['parts'])) {
      for (const part of content['parts'] as Record<string, unknown>[]) {
        if (typeof part['text'] === 'string') {
          textParts.push(part['text']);
        }
        const fnCall = part['functionCall'] as Record<string, unknown> | undefined;
        if (fnCall && typeof fnCall['name'] === 'string') {
          const draft: ToolCallDraft = { name: fnCall['name'] };
          if (fnCall['args'] != null) {
            draft.arguments = typeof fnCall['args'] === 'string'
              ? fnCall['args']
              : JSON.stringify(fnCall['args']);
          }
          toolCalls.push(draft);
        }
      }
    }
  }

  return {
    model: parsed.model ?? '',
    text: textParts.join(''),
    toolCalls,
    stopReason: '',
    streaming: parsed.stream,
  };
}

function extractOllama(parsed: OllamaParsed): Partial<ExpectationDraft> {
  let text = '';
  const toolCalls: ToolCallDraft[] = [];

  if (parsed.responseMessage != null) {
    const msg = parsed.responseMessage as Record<string, unknown>;
    if (typeof msg['content'] === 'string') {
      text = msg['content'];
    }
    if (Array.isArray(msg['tool_calls'])) {
      for (const tc of msg['tool_calls'] as Record<string, unknown>[]) {
        const fn = tc['function'] as Record<string, unknown> | undefined;
        if (fn && typeof fn['name'] === 'string') {
          const draft: ToolCallDraft = { name: fn['name'] };
          if (fn['arguments'] != null) {
            draft.arguments = typeof fn['arguments'] === 'string'
              ? fn['arguments']
              : JSON.stringify(fn['arguments']);
          }
          toolCalls.push(draft);
        }
      }
    }
  }

  return {
    model: parsed.model ?? '',
    text,
    toolCalls,
    stopReason: '',
    streaming: parsed.stream,
  };
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

/**
 * Returns `true` if the parsed traffic is LLM traffic (not generic or MCP)
 * and can therefore be converted into a mock expectation.
 */
export function isLlmTraffic(parsed: ParsedTraffic): boolean {
  return (
    parsed.kind === 'anthropic' ||
    parsed.kind === 'openai' ||
    parsed.kind === 'openai_responses' ||
    parsed.kind === 'gemini' ||
    parsed.kind === 'ollama'
  );
}

/**
 * Extract an editable expectation draft from a captured and parsed
 * LLM request/response pair.
 *
 * @param parsed   The parsed traffic from `parseTraffic()`.
 * @param path     The captured request path.
 * @returns An `ExpectationDraft` with defaults populated from the capture.
 */
export function extractExpectationFromCapture(
  parsed: ParsedTraffic,
  path: string,
): ExpectationDraft {
  const provider = kindToProvider(parsed.kind);
  let extracted: Partial<ExpectationDraft> = {};

  switch (parsed.kind) {
    case 'anthropic':
      extracted = extractAnthropic(parsed);
      break;
    case 'openai':
      extracted = extractOpenAi(parsed);
      break;
    case 'openai_responses':
      extracted = extractOpenAiResponses(parsed);
      break;
    case 'gemini':
      extracted = extractGemini(parsed);
      break;
    case 'ollama':
      extracted = extractOllama(parsed);
      break;
  }

  return {
    path,
    provider,
    model: extracted.model ?? '',
    text: extracted.text ?? '',
    toolCalls: extracted.toolCalls ?? [],
    stopReason: extracted.stopReason ?? '',
    streaming: extracted.streaming ?? false,
  };
}
