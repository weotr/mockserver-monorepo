/**
 * Pure codegen functions that produce copyable Java / JSON representations
 * of a mock expectation from an ExpectationDraft.
 *
 * Supports two modes:
 * - **LLM mode** (LlmExpectationDraft) — produces `httpLlmResponse`-based
 *   expectations using the Llm.* static imports.
 * - **Generic HTTP mode** (GenericExpectationDraft) — produces standard
 *   `httpResponse`-based expectations.
 *
 * Java codegen follows the exact API surface:
 *   import static org.mockserver.client.Llm.*;
 *   import static org.mockserver.model.HttpRequest.request;
 *   import static org.mockserver.model.HttpLlmResponse.llmResponse;
 *
 * JSON codegen follows the httpLlmResponse.json / httpResponse schema.
 */

import type {
  ExpectationDraft,
  LlmExpectationDraft,
  GenericExpectationDraft,
  ToolCallDraft,
  HeaderDraft,
  QueryParamDraft,
} from './expectationFromCapture';

// ---------------------------------------------------------------------------
// JSON codegen — LLM expectations
// ---------------------------------------------------------------------------

/**
 * Build the raw JSON expectation object for an LLM draft
 * (matches PUT /mockserver/expectation with httpLlmResponse).
 */
export function llmExpectationToJsonObject(draft: LlmExpectationDraft): Record<string, unknown> {
  const completion: Record<string, unknown> = {};
  if (draft.text) {
    completion['text'] = draft.text;
  }
  if (draft.toolCalls.length > 0) {
    completion['toolCalls'] = draft.toolCalls.map((tc) => {
      const obj: Record<string, unknown> = { name: tc.name };
      if (tc.arguments) {
        obj['arguments'] = tc.arguments;
      }
      return obj;
    });
  }
  if (draft.stopReason) {
    completion['stopReason'] = draft.stopReason;
  }
  if (draft.streaming) {
    completion['streaming'] = true;
  }

  const llmResponse: Record<string, unknown> = {
    provider: draft.provider,
  };
  if (draft.model) {
    llmResponse['model'] = draft.model;
  }
  if (Object.keys(completion).length > 0) {
    llmResponse['completion'] = completion;
  }

  return {
    httpRequest: {
      method: 'POST',
      path: draft.path,
    },
    httpLlmResponse: llmResponse,
  };
}

// ---------------------------------------------------------------------------
// JSON codegen — Generic HTTP expectations
// ---------------------------------------------------------------------------

function headersToJson(headers: HeaderDraft[]): Record<string, unknown>[] {
  return headers.map((h) => ({ name: h.name, values: h.values }));
}

function queryParamsToJson(params: QueryParamDraft[]): Record<string, unknown>[] {
  return params.map((p) => ({ name: p.name, values: p.values }));
}

/**
 * Build the raw JSON expectation object for a generic HTTP draft
 * (matches PUT /mockserver/expectation with httpResponse).
 */
export function genericExpectationToJsonObject(draft: GenericExpectationDraft): Record<string, unknown> {
  const httpRequest: Record<string, unknown> = {
    method: draft.method,
    path: draft.path,
  };

  // Add fields based on matcher precision
  if (draft.matcherPrecision !== 'loose') {
    if (draft.queryStringParameters.length > 0) {
      httpRequest['queryStringParameters'] = queryParamsToJson(draft.queryStringParameters);
    }
    if (draft.body) {
      httpRequest['body'] = draft.body;
    }
  }
  if (draft.matcherPrecision === 'exact') {
    if (draft.headers.length > 0) {
      httpRequest['headers'] = headersToJson(draft.headers);
    }
  }

  const httpResponse: Record<string, unknown> = {
    statusCode: draft.responseStatusCode,
  };
  if (draft.responseHeaders.length > 0) {
    httpResponse['headers'] = headersToJson(draft.responseHeaders);
  }
  if (draft.responseBody) {
    httpResponse['body'] = draft.responseBody;
  }

  return { httpRequest, httpResponse };
}

/**
 * Build the raw JSON expectation object (matches PUT /mockserver/expectation).
 * Dispatches to LLM or generic codegen based on draft kind.
 */
export function expectationToJsonObject(draft: ExpectationDraft): Record<string, unknown> {
  if (draft.kind === 'llm') return llmExpectationToJsonObject(draft);
  return genericExpectationToJsonObject(draft);
}

/**
 * Produce a pretty-printed JSON string of the expectation.
 */
export function expectationToJson(draft: ExpectationDraft): string {
  return JSON.stringify(expectationToJsonObject(draft), null, 2);
}

// ---------------------------------------------------------------------------
// Java codegen (using the Llm.* static imports)
// ---------------------------------------------------------------------------

function escapeJava(s: string): string {
  return s
    .replace(/\\/g, '\\\\')
    .replace(/"/g, '\\"')
    .replace(/\n/g, '\\n')
    .replace(/\r/g, '\\r')
    .replace(/\t/g, '\\t');
}

function toolCallToJava(tc: ToolCallDraft): string {
  let s = `toolUse("${escapeJava(tc.name)}")`;
  if (tc.arguments) {
    s += `.withArguments("${escapeJava(tc.arguments)}")`;
  }
  return s;
}

/**
 * Produce Java code for an LLM expectation.
 */
function llmExpectationToJava(draft: LlmExpectationDraft): string {
  const lines: string[] = [];

  lines.push('import static org.mockserver.client.Llm.*;');
  lines.push('import static org.mockserver.model.HttpRequest.request;');
  lines.push('import static org.mockserver.model.HttpLlmResponse.llmResponse;');
  lines.push('import static org.mockserver.mock.Expectation.when;');
  lines.push('import org.mockserver.model.Provider;');
  lines.push('');

  // Build completion chain
  const completionParts: string[] = ['completion()'];
  if (draft.text) {
    completionParts.push(`.withText("${escapeJava(draft.text)}")`);
  }
  for (const tc of draft.toolCalls) {
    completionParts.push(`.withToolCall(${toolCallToJava(tc)})`);
  }
  if (draft.stopReason) {
    completionParts.push(`.withStopReason("${escapeJava(draft.stopReason)}")`);
  }
  if (draft.streaming) {
    completionParts.push('.streaming()');
  }

  // Build llmResponse chain
  const responseParts: string[] = ['llmResponse()'];
  responseParts.push(`.withProvider(Provider.${draft.provider})`);
  if (draft.model) {
    responseParts.push(`.withModel("${escapeJava(draft.model)}")`);
  }
  responseParts.push(
    `.withCompletion(\n        ${completionParts.join('\n            ')}\n    )`,
  );

  // Build full expectation
  lines.push('when(');
  lines.push(`    request().withMethod("POST").withPath("${escapeJava(draft.path)}")`);
  lines.push(').thenRespondWithLlm(');
  lines.push(`    ${responseParts.join('\n        ')}`);
  lines.push(');');

  return lines.join('\n');
}

/**
 * Produce Java code for a generic HTTP expectation.
 */
function genericExpectationToJava(draft: GenericExpectationDraft): string {
  const lines: string[] = [];

  lines.push('import static org.mockserver.model.HttpRequest.request;');
  lines.push('import static org.mockserver.model.HttpResponse.response;');
  lines.push('import static org.mockserver.mock.Expectation.when;');
  lines.push('');

  // Build request chain
  const requestParts: string[] = [`request().withMethod("${escapeJava(draft.method)}").withPath("${escapeJava(draft.path)}")`];
  if (draft.matcherPrecision !== 'loose') {
    for (const qp of draft.queryStringParameters) {
      const values = qp.values.map((v) => `"${escapeJava(v)}"`).join(', ');
      requestParts.push(`.withQueryStringParameter("${escapeJava(qp.name)}", ${values})`);
    }
    if (draft.body) {
      requestParts.push(`.withBody("${escapeJava(draft.body)}")`);
    }
  }
  if (draft.matcherPrecision === 'exact') {
    for (const h of draft.headers) {
      const values = h.values.map((v) => `"${escapeJava(v)}"`).join(', ');
      requestParts.push(`.withHeader("${escapeJava(h.name)}", ${values})`);
    }
  }

  // Build response chain
  const responseParts: string[] = [`response().withStatusCode(${draft.responseStatusCode})`];
  for (const h of draft.responseHeaders) {
    const values = h.values.map((v) => `"${escapeJava(v)}"`).join(', ');
    responseParts.push(`.withHeader("${escapeJava(h.name)}", ${values})`);
  }
  if (draft.responseBody) {
    responseParts.push(`.withBody("${escapeJava(draft.responseBody)}")`);
  }

  lines.push('when(');
  lines.push(`    ${requestParts.join('\n        ')}`);
  lines.push(').thenRespond(');
  lines.push(`    ${responseParts.join('\n        ')}`);
  lines.push(');');

  return lines.join('\n');
}

/**
 * Produce Java code using the appropriate builder chain.
 */
export function expectationToJava(draft: ExpectationDraft): string {
  if (draft.kind === 'llm') return llmExpectationToJava(draft);
  return genericExpectationToJava(draft);
}

// ---------------------------------------------------------------------------
// MCP tool call body (for mock_llm_completion) — LLM only
// ---------------------------------------------------------------------------

/**
 * Produce the MCP `tools/call` arguments for the `mock_llm_completion` tool.
 * Only applicable to LLM drafts.
 */
export function expectationToMcpArgs(draft: LlmExpectationDraft): Record<string, unknown> {
  const args: Record<string, unknown> = {
    provider: draft.provider,
    path: draft.path,
  };
  if (draft.model) {
    args['model'] = draft.model;
  }
  if (draft.text) {
    args['text'] = draft.text;
  }
  if (draft.toolCalls.length > 0) {
    args['toolCalls'] = draft.toolCalls.map((tc) => {
      const obj: Record<string, unknown> = { name: tc.name };
      if (tc.arguments) {
        obj['arguments'] = tc.arguments;
      }
      return obj;
    });
  }
  if (draft.stopReason) {
    args['stopReason'] = draft.stopReason;
  }
  if (draft.streaming) {
    args['streaming'] = true;
  }
  return args;
}

