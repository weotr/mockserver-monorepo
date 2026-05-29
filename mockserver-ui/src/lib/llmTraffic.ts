/**
 * Pure parsing helpers for classifying and extracting LLM / MCP traffic
 * from MockServer proxied request/response pairs.
 *
 * All functions are defensive — they never throw on malformed input.
 */

// ---------------------------------------------------------------------------
// SSE types
// ---------------------------------------------------------------------------

export interface SseEvent {
  event?: string;
  data: string;
}

// ---------------------------------------------------------------------------
// Anthropic types
// ---------------------------------------------------------------------------

export interface AnthropicContentBlock {
  type: string;
  text?: string;
  id?: string;
  name?: string;
  input?: unknown;
}

export interface AnthropicUsage {
  input_tokens?: number;
  output_tokens?: number;
}

export interface AnthropicParsed {
  kind: 'anthropic';
  model: string | null;
  stream: boolean;
  messages: unknown[];
  system: unknown | null;
  tools: unknown[] | null;
  maxTokens: number | null;
  responseContent: AnthropicContentBlock[];
  usage: AnthropicUsage | null;
  stopReason: string | null;
  sseEvents: SseEvent[] | null;
  streamed: boolean;
  streamTruncated: boolean;
}

// ---------------------------------------------------------------------------
// OpenAI types
// ---------------------------------------------------------------------------

export interface OpenAiUsage {
  prompt_tokens?: number;
  completion_tokens?: number;
  total_tokens?: number;
}

export interface OpenAiChoice {
  message?: {
    role?: string;
    content?: string | null;
    tool_calls?: unknown[];
  };
  finish_reason?: string | null;
}

export interface OpenAiParsed {
  kind: 'openai';
  model: string | null;
  stream: boolean;
  messages: unknown[];
  tools: unknown[] | null;
  choices: OpenAiChoice[];
  usage: OpenAiUsage | null;
  sseEvents: SseEvent[] | null;
  streamed: boolean;
  streamTruncated: boolean;
}

// ---------------------------------------------------------------------------
// MCP JSON-RPC types
// ---------------------------------------------------------------------------

export interface McpParsed {
  kind: 'mcp';
  method: string | null;
  id: unknown;
  params: unknown | null;
  result: unknown | null;
  error: unknown | null;
  isResponse: boolean;
}

// ---------------------------------------------------------------------------
// OpenAI Responses API types
// ---------------------------------------------------------------------------

export interface OpenAiResponsesParsed {
  kind: 'openai_responses';
  model: string | null;
  stream: boolean;
  input: unknown[];
  tools: unknown[] | null;
  output: unknown[];
  usage: OpenAiUsage | null;
  sseEvents: SseEvent[] | null;
  streamed: boolean;
  streamTruncated: boolean;
}

// ---------------------------------------------------------------------------
// Gemini types
// ---------------------------------------------------------------------------

export interface GeminiParsed {
  kind: 'gemini';
  model: string | null;
  stream: boolean;
  contents: unknown[];
  tools: unknown[] | null;
  candidates: unknown[];
  usage: { promptTokenCount?: number; candidatesTokenCount?: number } | null;
  sseEvents: SseEvent[] | null;
  streamed: boolean;
  streamTruncated: boolean;
}

// ---------------------------------------------------------------------------
// Ollama types
// ---------------------------------------------------------------------------

export interface OllamaParsed {
  kind: 'ollama';
  model: string | null;
  stream: boolean;
  messages: unknown[];
  tools: unknown[] | null;
  responseMessage: unknown | null;
  done: boolean;
  usage: { prompt_eval_count?: number; eval_count?: number } | null;
  sseEvents: SseEvent[] | null;
  streamed: boolean;
  streamTruncated: boolean;
}

// ---------------------------------------------------------------------------
// Generic fallback
// ---------------------------------------------------------------------------

export interface GenericParsed {
  kind: 'generic';
  method: string | null;
  path: string | null;
  statusCode: number | null;
}

// ---------------------------------------------------------------------------
// ConversationPredicates — matches Java ConversationPredicates model
// ---------------------------------------------------------------------------

export interface PromptNormalization {
  collapseWhitespace?: boolean;
  lowercase?: boolean;
  sortJsonKeys?: boolean;
  dropBuiltInVolatileFields?: boolean;
  dropVolatileFields?: string[];
}

export interface ConversationPredicates {
  turnIndex?: number;
  latestMessageContains?: string;
  latestMessageMatches?: string;
  latestMessageRole?: 'USER' | 'ASSISTANT' | 'TOOL' | 'SYSTEM';
  containsToolResultFor?: string;
  normalization?: PromptNormalization;
}

// ---------------------------------------------------------------------------
// Discriminated union
// ---------------------------------------------------------------------------

export type ParsedTraffic =
  | AnthropicParsed
  | OpenAiParsed
  | OpenAiResponsesParsed
  | GeminiParsed
  | OllamaParsed
  | McpParsed
  | GenericParsed;

// ---------------------------------------------------------------------------
// Summary for master list display
// ---------------------------------------------------------------------------

export interface TrafficSummary {
  host: string | null;
  method: string | null;
  path: string | null;
  statusCode: number | null;
  parsed: ParsedTraffic;
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

function safeParseJson(input: unknown): unknown {
  if (typeof input === 'string') {
    try {
      return JSON.parse(input);
    } catch {
      return undefined;
    }
  }
  if (typeof input === 'object' && input !== null) {
    return input;
  }
  return undefined;
}

function getString(obj: unknown, key: string): string | null {
  if (typeof obj !== 'object' || obj === null) return null;
  const val = (obj as Record<string, unknown>)[key];
  return typeof val === 'string' ? val : null;
}

function getNumber(obj: unknown, key: string): number | null {
  if (typeof obj !== 'object' || obj === null) return null;
  const val = (obj as Record<string, unknown>)[key];
  return typeof val === 'number' ? val : null;
}

function getBoolean(obj: unknown, key: string): boolean | null {
  if (typeof obj !== 'object' || obj === null) return null;
  const val = (obj as Record<string, unknown>)[key];
  return typeof val === 'boolean' ? val : null;
}

function getArray(obj: unknown, key: string): unknown[] | null {
  if (typeof obj !== 'object' || obj === null) return null;
  const val = (obj as Record<string, unknown>)[key];
  return Array.isArray(val) ? val : null;
}

function getObject(obj: unknown, key: string): Record<string, unknown> | null {
  if (typeof obj !== 'object' || obj === null) return null;
  const val = (obj as Record<string, unknown>)[key];
  if (typeof val === 'object' && val !== null && !Array.isArray(val)) {
    return val as Record<string, unknown>;
  }
  return null;
}

/**
 * Extract a header value from MockServer's header format.
 * Headers can be either an object `{ name: [values] }` or an array
 * `[{ name: "x", values: ["y"] }]`.
 */
function getHeaderValue(headers: unknown, headerName: string): string | null {
  if (!headers) return null;
  const lowerName = headerName.toLowerCase();

  if (Array.isArray(headers)) {
    for (const h of headers) {
      if (typeof h === 'object' && h !== null) {
        const entry = h as Record<string, unknown>;
        const name = entry['name'];
        if (typeof name === 'string' && name.toLowerCase() === lowerName) {
          const values = entry['values'];
          if (Array.isArray(values) && values.length > 0) {
            return String(values[0]);
          }
        }
      }
    }
    return null;
  }

  if (typeof headers === 'object' && headers !== null) {
    const map = headers as Record<string, unknown>;
    for (const key of Object.keys(map)) {
      if (key.toLowerCase() === lowerName) {
        const val = map[key];
        if (Array.isArray(val) && val.length > 0) return String(val[0]);
        if (typeof val === 'string') return val;
      }
    }
  }

  return null;
}

// ---------------------------------------------------------------------------
// SSE parsing
// ---------------------------------------------------------------------------

export function parseSseStream(text: string): SseEvent[] {
  const events: SseEvent[] = [];
  const lines = text.split('\n');
  let currentEvent: string | undefined;
  let currentData: string[] = [];

  for (const line of lines) {
    if (line.startsWith('event:')) {
      currentEvent = line.slice(6).trim();
    } else if (line.startsWith('data:')) {
      currentData.push(line.slice(5).trimStart());
    } else if (line.trim() === '' && currentData.length > 0) {
      events.push({
        event: currentEvent,
        data: currentData.join('\n'),
      });
      currentEvent = undefined;
      currentData = [];
    }
  }

  // Handle trailing data without a final blank line
  if (currentData.length > 0) {
    events.push({
      event: currentEvent,
      data: currentData.join('\n'),
    });
  }

  return events;
}

// ---------------------------------------------------------------------------
// Anthropic SSE reassembly
// ---------------------------------------------------------------------------

interface ReassembledAnthropic {
  content: AnthropicContentBlock[];
  usage: AnthropicUsage;
  model: string | null;
  stopReason: string | null;
}

function reassembleAnthropicSse(events: SseEvent[]): ReassembledAnthropic {
  const result: ReassembledAnthropic = {
    content: [],
    usage: {},
    model: null,
    stopReason: null,
  };

  const textParts: Map<number, string[]> = new Map();
  const toolInputParts: Map<number, string[]> = new Map();

  for (const evt of events) {
    const parsed = safeParseJson(evt.data);
    if (!parsed || typeof parsed !== 'object') continue;
    const data = parsed as Record<string, unknown>;

    if (evt.event === 'message_start') {
      const message = getObject(data, 'message');
      if (message) {
        result.model = getString(message, 'model');
        const usage = getObject(message, 'usage');
        if (usage) {
          const inputTokens = getNumber(usage, 'input_tokens');
          if (inputTokens !== null) result.usage.input_tokens = inputTokens;
        }
      }
    } else if (evt.event === 'content_block_start') {
      const block = getObject(data, 'content_block');
      const index = getNumber(data, 'index');
      if (block && index !== null) {
        const type = getString(block, 'type') ?? 'text';
        const contentBlock: AnthropicContentBlock = { type };
        if (type === 'tool_use') {
          contentBlock.id = getString(block, 'id') ?? undefined;
          contentBlock.name = getString(block, 'name') ?? undefined;
        }
        // Ensure the content array is large enough
        while (result.content.length <= index) {
          result.content.push({ type: 'text' });
        }
        result.content[index] = contentBlock;
      }
    } else if (evt.event === 'content_block_delta') {
      const delta = getObject(data, 'delta');
      const index = getNumber(data, 'index');
      if (delta && index !== null) {
        const deltaType = getString(delta, 'type');
        if (deltaType === 'text_delta') {
          const text = getString(delta, 'text');
          if (text !== null) {
            if (!textParts.has(index)) textParts.set(index, []);
            textParts.get(index)!.push(text);
          }
        } else if (deltaType === 'input_json_delta') {
          const partial = getString(delta, 'partial_json');
          if (partial !== null) {
            if (!toolInputParts.has(index)) toolInputParts.set(index, []);
            toolInputParts.get(index)!.push(partial);
          }
        }
      }
    } else if (evt.event === 'message_delta') {
      const delta = getObject(data, 'delta');
      if (delta) {
        result.stopReason = getString(delta, 'stop_reason');
      }
      const usage = getObject(data, 'usage');
      if (usage) {
        const outputTokens = getNumber(usage, 'output_tokens');
        if (outputTokens !== null) result.usage.output_tokens = outputTokens;
      }
    }
  }

  // Apply accumulated text and tool input
  for (const [index, parts] of textParts) {
    if (result.content[index]) {
      result.content[index] = { ...result.content[index]!, text: parts.join('') };
    }
  }
  for (const [index, parts] of toolInputParts) {
    if (result.content[index]) {
      const joinedJson = parts.join('');
      const parsedInput = safeParseJson(joinedJson);
      result.content[index] = { ...result.content[index]!, input: parsedInput ?? joinedJson };
    }
  }

  return result;
}

// ---------------------------------------------------------------------------
// Detection helpers
// ---------------------------------------------------------------------------

function isAnthropicPath(path: string | null): boolean {
  return path !== null && path.includes('/v1/messages');
}

function isOpenAiPath(path: string | null): boolean {
  return path !== null && path.includes('/chat/completions');
}

function isOpenAiResponsesPath(path: string | null): boolean {
  return path !== null && /\/v1\/responses/.test(path);
}

function isGeminiPath(path: string | null): boolean {
  // Gemini uses /v1beta/ (literal beta) or /v1/ followed by a model name that
  // starts with "gemini-". The earlier `/v1(beta)?/` form misclassified generic
  // /v1/models/* paths from other providers as Gemini.
  if (path === null) return false;
  return /\/v1beta\/models\/[^/]+:(generateContent|streamGenerateContent)/.test(path)
    || /\/v1\/models\/gemini-[^/]+:(generateContent|streamGenerateContent)/.test(path);
}

function isBedrockPath(path: string | null): boolean {
  return path !== null && /\/model\/anthropic\./.test(path) && path.includes('/invoke');
}

function isAzureOpenAiPath(path: string | null): boolean {
  return path !== null && /\/openai\/deployments\//.test(path) && path.includes('/chat/completions');
}

function isOllamaPath(path: string | null): boolean {
  // Anchor to `/api/chat` as a complete path segment so we don't misclassify
  // `/api/chatbot`, `/api/chats`, or any generic chat endpoint.
  if (path === null) return false;
  return /(^|\/)api\/chat(?:\/?$|\?)/.test(path);
}

function isMcpJsonRpc(body: unknown): boolean {
  if (typeof body !== 'object' || body === null) return false;
  const obj = body as Record<string, unknown>;
  return obj['jsonrpc'] === '2.0' && (typeof obj['method'] === 'string' || 'result' in obj || 'error' in obj);
}

function isStreamResponse(responseHeaders: unknown, responseBody: unknown): boolean {
  const contentType = getHeaderValue(responseHeaders, 'content-type');
  if (contentType && contentType.includes('text/event-stream')) return true;

  // Check if body looks like SSE
  if (typeof responseBody === 'string') {
    const firstLines = responseBody.slice(0, 500);
    return firstLines.includes('event:') && firstLines.includes('data:');
  }

  return false;
}

function hasStreamingHeaders(responseHeaders: unknown): { streamed: boolean; truncated: boolean } {
  const streamed = getHeaderValue(responseHeaders, 'x-mockserver-streamed') === 'true';
  const truncated = getHeaderValue(responseHeaders, 'x-mockserver-stream-truncated') === 'true';
  return { streamed, truncated };
}

// ---------------------------------------------------------------------------
// Main parse function
// ---------------------------------------------------------------------------

function parseAnthropicRequest(
  requestBody: unknown,
  responseBody: unknown,
  responseHeaders: unknown,
): AnthropicParsed {
  const req = safeParseJson(requestBody) as Record<string, unknown> | undefined;
  const { streamed, truncated } = hasStreamingHeaders(responseHeaders);
  const isStream = isStreamResponse(responseHeaders, responseBody);

  const result: AnthropicParsed = {
    kind: 'anthropic',
    model: req ? getString(req, 'model') : null,
    stream: req ? (getBoolean(req, 'stream') ?? false) : false,
    messages: req ? (getArray(req, 'messages') ?? []) : [],
    system: req ? (req['system'] ?? null) : null,
    tools: req ? (getArray(req, 'tools') ?? null) : null,
    maxTokens: req ? (getNumber(req, 'max_tokens') ?? null) : null,
    responseContent: [],
    usage: null,
    stopReason: null,
    sseEvents: null,
    streamed,
    streamTruncated: truncated,
  };

  if (isStream && typeof responseBody === 'string') {
    const events = parseSseStream(responseBody);
    result.sseEvents = events;
    const reassembled = reassembleAnthropicSse(events);
    result.responseContent = reassembled.content;
    result.usage = reassembled.usage;
    result.stopReason = reassembled.stopReason;
    if (reassembled.model && !result.model) {
      result.model = reassembled.model;
    }
  } else {
    const res = safeParseJson(responseBody) as Record<string, unknown> | undefined;
    if (res) {
      result.responseContent = (getArray(res, 'content') ?? []) as AnthropicContentBlock[];
      result.usage = getObject(res, 'usage') as AnthropicUsage | null;
      result.stopReason = getString(res, 'stop_reason');
      if (!result.model) {
        result.model = getString(res, 'model');
      }
    }
  }

  return result;
}

function parseOpenAiRequest(
  requestBody: unknown,
  responseBody: unknown,
  responseHeaders: unknown,
): OpenAiParsed {
  const req = safeParseJson(requestBody) as Record<string, unknown> | undefined;
  const { streamed, truncated } = hasStreamingHeaders(responseHeaders);
  const isStream = isStreamResponse(responseHeaders, responseBody);

  const result: OpenAiParsed = {
    kind: 'openai',
    model: req ? getString(req, 'model') : null,
    stream: req ? (getBoolean(req, 'stream') ?? false) : false,
    messages: req ? (getArray(req, 'messages') ?? []) : [],
    tools: req ? (getArray(req, 'tools') ?? null) : null,
    choices: [],
    usage: null,
    sseEvents: null,
    streamed,
    streamTruncated: truncated,
  };

  if (isStream && typeof responseBody === 'string') {
    const events = parseSseStream(responseBody);
    result.sseEvents = events;
    // Reassemble streamed OpenAI response
    const contentParts: string[] = [];
    const toolCalls: Map<number, { id?: string; type?: string; function?: { name: string; arguments: string } }> = new Map();
    let finishReason: string | null = null;
    let model: string | null = null;

    for (const evt of events) {
      if (evt.data === '[DONE]') continue;
      const parsed = safeParseJson(evt.data) as Record<string, unknown> | undefined;
      if (!parsed) continue;
      if (!model) model = getString(parsed, 'model');
      const choices = getArray(parsed, 'choices');
      if (choices) {
        for (const choice of choices) {
          const choiceObj = choice as Record<string, unknown>;
          const delta = getObject(choiceObj, 'delta');
          if (delta) {
            const content = getString(delta, 'content');
            if (content !== null) contentParts.push(content);
            const toolCallsArr = getArray(delta, 'tool_calls');
            if (toolCallsArr) {
              for (const tc of toolCallsArr) {
                const tcObj = tc as Record<string, unknown>;
                const index = getNumber(tcObj, 'index') ?? 0;
                const existing = toolCalls.get(index) ?? {};
                const id = getString(tcObj, 'id');
                if (id) existing.id = id;
                const type = getString(tcObj, 'type');
                if (type) existing.type = type;
                const fn = getObject(tcObj, 'function');
                if (fn) {
                  if (!existing.function) {
                    existing.function = { name: '', arguments: '' };
                  }
                  const name = getString(fn, 'name');
                  if (name) existing.function.name = name;
                  const args = getString(fn, 'arguments');
                  if (args !== null) existing.function.arguments += args;
                }
                toolCalls.set(index, existing);
              }
            }
          }
          const fr = getString(choiceObj, 'finish_reason');
          if (fr) finishReason = fr;
        }
      }
      const usage = getObject(parsed, 'usage');
      if (usage) {
        result.usage = usage as OpenAiUsage;
      }
    }

    if (model && !result.model) result.model = model;

    const reassembledToolCalls = toolCalls.size > 0
      ? Array.from(toolCalls.entries())
          .sort(([a], [b]) => a - b)
          .map(([, tc]) => tc)
      : undefined;

    result.choices = [{
      message: {
        role: 'assistant',
        content: contentParts.length > 0 ? contentParts.join('') : null,
        tool_calls: reassembledToolCalls,
      },
      finish_reason: finishReason,
    }];
  } else {
    const res = safeParseJson(responseBody) as Record<string, unknown> | undefined;
    if (res) {
      result.choices = (getArray(res, 'choices') ?? []) as OpenAiChoice[];
      result.usage = getObject(res, 'usage') as OpenAiUsage | null;
      if (!result.model) {
        result.model = getString(res, 'model');
      }
    }
  }

  return result;
}

function parseMcpRequest(requestBody: unknown, responseBody: unknown): McpParsed {
  const req = safeParseJson(requestBody) as Record<string, unknown> | undefined;
  const res = safeParseJson(responseBody) as Record<string, unknown> | undefined;

  // Could be a request or a response in JSON-RPC
  const isResponse = req ? !('method' in req) : false;
  const primary = isResponse ? res : req;
  const secondary = isResponse ? req : res;

  return {
    kind: 'mcp',
    method: primary ? getString(primary, 'method') : null,
    id: primary ? primary['id'] ?? null : null,
    params: primary ? (primary['params'] ?? null) : null,
    result: secondary ? (secondary['result'] ?? null) : (primary ? (primary['result'] ?? null) : null),
    error: secondary ? (secondary['error'] ?? null) : (primary ? (primary['error'] ?? null) : null),
    isResponse,
  };
}

// ---------------------------------------------------------------------------
// OpenAI Responses API parser
// ---------------------------------------------------------------------------

function parseOpenAiResponsesRequest(
  requestBody: unknown,
  responseBody: unknown,
  responseHeaders: unknown,
): OpenAiResponsesParsed {
  const req = safeParseJson(requestBody) as Record<string, unknown> | undefined;
  const { streamed, truncated } = hasStreamingHeaders(responseHeaders);

  const result: OpenAiResponsesParsed = {
    kind: 'openai_responses',
    model: req ? getString(req, 'model') : null,
    stream: req ? (getBoolean(req, 'stream') ?? false) : false,
    input: req ? (getArray(req, 'input') ?? []) : [],
    tools: req ? (getArray(req, 'tools') ?? null) : null,
    output: [],
    usage: null,
    sseEvents: null,
    streamed,
    streamTruncated: truncated,
  };

  const res = safeParseJson(responseBody) as Record<string, unknown> | undefined;
  if (res) {
    result.output = getArray(res, 'output') ?? [];
    result.usage = getObject(res, 'usage') as OpenAiUsage | null;
    if (!result.model) {
      result.model = getString(res, 'model');
    }
  }

  return result;
}

// ---------------------------------------------------------------------------
// Gemini parser
// ---------------------------------------------------------------------------

function parseGeminiRequest(
  requestBody: unknown,
  responseBody: unknown,
  responseHeaders: unknown,
): GeminiParsed {
  const req = safeParseJson(requestBody) as Record<string, unknown> | undefined;
  const { streamed, truncated } = hasStreamingHeaders(responseHeaders);

  const result: GeminiParsed = {
    kind: 'gemini',
    model: null,
    stream: false,
    contents: req ? (getArray(req, 'contents') ?? []) : [],
    tools: req ? (getArray(req, 'tools') ?? null) : null,
    candidates: [],
    usage: null,
    sseEvents: null,
    streamed,
    streamTruncated: truncated,
  };

  const res = safeParseJson(responseBody) as Record<string, unknown> | undefined;
  if (res) {
    result.candidates = getArray(res, 'candidates') ?? [];
    const usageMeta = getObject(res, 'usageMetadata');
    if (usageMeta) {
      result.usage = {
        promptTokenCount: getNumber(usageMeta, 'promptTokenCount') ?? undefined,
        candidatesTokenCount: getNumber(usageMeta, 'candidatesTokenCount') ?? undefined,
      };
    }
    if (!result.model) {
      result.model = getString(res, 'modelVersion') ?? getString(res, 'model');
    }
  }

  return result;
}

// ---------------------------------------------------------------------------
// Ollama parser
// ---------------------------------------------------------------------------

function parseOllamaRequest(
  requestBody: unknown,
  responseBody: unknown,
  responseHeaders: unknown,
): OllamaParsed {
  const req = safeParseJson(requestBody) as Record<string, unknown> | undefined;
  const { streamed, truncated } = hasStreamingHeaders(responseHeaders);

  const result: OllamaParsed = {
    kind: 'ollama',
    model: req ? getString(req, 'model') : null,
    stream: req ? (getBoolean(req, 'stream') ?? false) : false,
    messages: req ? (getArray(req, 'messages') ?? []) : [],
    tools: req ? (getArray(req, 'tools') ?? null) : null,
    responseMessage: null,
    done: false,
    usage: null,
    sseEvents: null,
    streamed,
    streamTruncated: truncated,
  };

  const res = safeParseJson(responseBody) as Record<string, unknown> | undefined;
  if (res) {
    result.responseMessage = res['message'] ?? null;
    result.done = getBoolean(res, 'done') ?? false;
    if (!result.model) {
      result.model = getString(res, 'model');
    }
    const promptEval = getNumber(res, 'prompt_eval_count');
    const evalCount = getNumber(res, 'eval_count');
    if (promptEval !== null || evalCount !== null) {
      result.usage = {
        prompt_eval_count: promptEval ?? undefined,
        eval_count: evalCount ?? undefined,
      };
    }
  }

  return result;
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

/**
 * Parse a proxied request/response pair into a typed ParsedTraffic object.
 *
 * @param value - The `JsonListItem.value` from the store's `proxiedRequests`.
 *   Expected shape: `{ httpRequest: {...}, httpResponse: {...} }`.
 */
export function parseTraffic(value: Record<string, unknown>): ParsedTraffic {
  try {
    const httpRequest = getObject(value, 'httpRequest');
    const httpResponse = getObject(value, 'httpResponse');

    const path = httpRequest ? getString(httpRequest, 'path') : null;
    const requestBody = httpRequest ? (getObject(httpRequest, 'body') ?? httpRequest['body']) : null;
    const responseBody = httpResponse ? (getObject(httpResponse, 'body') ?? httpResponse['body']) : null;
    const responseHeaders = httpResponse ? httpResponse['headers'] : null;

    // Extract body string or object — MockServer can encode body as { type, string } or { type, json }
    const reqBodyContent = extractBodyContent(requestBody);
    const resBodyContent = extractBodyContent(responseBody);

    if (isAnthropicPath(path)) {
      return parseAnthropicRequest(reqBodyContent, resBodyContent, responseHeaders);
    }

    // Azure OpenAI uses the same wire format as OpenAI Chat Completions but
    // has a distinctive path (/openai/deployments/…/chat/completions).
    // Check before generic OpenAI path to avoid ambiguity.
    if (isAzureOpenAiPath(path)) {
      return parseOpenAiRequest(reqBodyContent, resBodyContent, responseHeaders);
    }

    // Bedrock Anthropic uses the native Anthropic wire shape.
    if (isBedrockPath(path)) {
      return parseAnthropicRequest(reqBodyContent, resBodyContent, responseHeaders);
    }

    // OpenAI Responses API (/v1/responses) — must be checked before
    // generic OpenAI chat completions path.
    if (isOpenAiResponsesPath(path)) {
      return parseOpenAiResponsesRequest(reqBodyContent, resBodyContent, responseHeaders);
    }

    if (isOpenAiPath(path)) {
      return parseOpenAiRequest(reqBodyContent, resBodyContent, responseHeaders);
    }

    if (isGeminiPath(path)) {
      return parseGeminiRequest(reqBodyContent, resBodyContent, responseHeaders);
    }

    if (isOllamaPath(path)) {
      return parseOllamaRequest(reqBodyContent, resBodyContent, responseHeaders);
    }

    // Check MCP on request body
    const parsedReqBody = safeParseJson(reqBodyContent);
    if (parsedReqBody && isMcpJsonRpc(parsedReqBody)) {
      return parseMcpRequest(reqBodyContent, resBodyContent);
    }

    // Check MCP on response body
    const parsedResBody = safeParseJson(resBodyContent);
    if (parsedResBody && isMcpJsonRpc(parsedResBody)) {
      return parseMcpRequest(reqBodyContent, resBodyContent);
    }

    const statusCode = httpResponse ? getNumber(httpResponse, 'statusCode') : null;
    const method = httpRequest ? getString(httpRequest, 'method') : null;

    return { kind: 'generic', method, path, statusCode };
  } catch {
    return { kind: 'generic', method: null, path: null, statusCode: null };
  }
}

/**
 * Extract the actual body content from MockServer's body representation.
 * MockServer bodies can be:
 * - A plain string
 * - An object with { type: "STRING", string: "..." }
 * - An object with { type: "JSON", json: "..." } or { type: "JSON", json: {...} }
 * - An object with { type: "BINARY", base64Bytes: "..." }
 * - An object directly (already parsed JSON)
 */
export function extractBodyContent(body: unknown): unknown {
  if (typeof body === 'string') return body;
  if (typeof body !== 'object' || body === null) return body;

  const obj = body as Record<string, unknown>;

  // MockServer body wrapper: { type: "STRING"|"JSON"|"BINARY", string|json|base64Bytes: ... }
  if ('type' in obj) {
    if ('string' in obj) return obj['string'];
    if ('json' in obj) return obj['json'];
    if (obj['type'] === 'BINARY' && typeof obj['base64Bytes'] === 'string') {
      try {
        const bytes = atob(obj['base64Bytes'] as string);
        return new TextDecoder().decode(
          Uint8Array.from(bytes, (c) => c.charCodeAt(0)),
        );
      } catch {
        // Fall through to return the original object if decoding fails
        return body;
      }
    }
  }

  // Already a plain object (e.g., already-parsed JSON body)
  return body;
}

/**
 * Build a summary for the master list from a proxied request item.
 */
export function summarizeTraffic(value: Record<string, unknown>): TrafficSummary {
  const httpRequest = getObject(value, 'httpRequest');
  const httpResponse = getObject(value, 'httpResponse');

  const method = httpRequest ? getString(httpRequest, 'method') : null;
  const path = httpRequest ? getString(httpRequest, 'path') : null;
  const statusCode = httpResponse ? getNumber(httpResponse, 'statusCode') : null;

  // Try to extract host from headers
  let host: string | null = null;
  if (httpRequest) {
    const headers = httpRequest['headers'];
    host = getHeaderValue(headers, 'host');
    if (!host) {
      // Try the Host header with capital H
      host = getHeaderValue(headers, 'Host');
    }
  }

  const parsed = parseTraffic(value);

  return {
    host,
    method,
    path,
    statusCode,
    parsed,
  };
}

/**
 * Get a display label for the model from parsed traffic.
 */
export function getModelLabel(parsed: ParsedTraffic): string | null {
  if (
    parsed.kind === 'anthropic' ||
    parsed.kind === 'openai' ||
    parsed.kind === 'openai_responses' ||
    parsed.kind === 'gemini' ||
    parsed.kind === 'ollama'
  ) {
    return parsed.model;
  }
  return null;
}

/**
 * Get token usage summary string.
 */
export function getTokenSummary(parsed: ParsedTraffic): string | null {
  if (parsed.kind === 'anthropic' && parsed.usage) {
    const parts: string[] = [];
    if (parsed.usage.input_tokens != null) parts.push(`${parsed.usage.input_tokens} in`);
    if (parsed.usage.output_tokens != null) parts.push(`${parsed.usage.output_tokens} out`);
    return parts.length > 0 ? parts.join(' / ') : null;
  }
  if ((parsed.kind === 'openai' || parsed.kind === 'openai_responses') && parsed.usage) {
    const parts: string[] = [];
    if (parsed.usage.prompt_tokens != null) parts.push(`${parsed.usage.prompt_tokens} in`);
    if (parsed.usage.completion_tokens != null) parts.push(`${parsed.usage.completion_tokens} out`);
    return parts.length > 0 ? parts.join(' / ') : null;
  }
  if (parsed.kind === 'gemini' && parsed.usage) {
    const parts: string[] = [];
    if (parsed.usage.promptTokenCount != null) parts.push(`${parsed.usage.promptTokenCount} in`);
    if (parsed.usage.candidatesTokenCount != null) parts.push(`${parsed.usage.candidatesTokenCount} out`);
    return parts.length > 0 ? parts.join(' / ') : null;
  }
  if (parsed.kind === 'ollama' && parsed.usage) {
    const parts: string[] = [];
    if (parsed.usage.prompt_eval_count != null) parts.push(`${parsed.usage.prompt_eval_count} in`);
    if (parsed.usage.eval_count != null) parts.push(`${parsed.usage.eval_count} out`);
    return parts.length > 0 ? parts.join(' / ') : null;
  }
  return null;
}
