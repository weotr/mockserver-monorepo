/**
 * Pure codegen functions that produce copyable Java / JSON representations
 * of a single LLM mock expectation from an ExpectationDraft.
 *
 * Java codegen follows the exact API surface:
 *   import static org.mockserver.client.Llm.*;
 *   import static org.mockserver.model.HttpRequest.request;
 *   import static org.mockserver.model.HttpLlmResponse.llmResponse;
 *
 * JSON codegen follows the httpLlmResponse.json schema.
 */

import type { ExpectationDraft, ToolCallDraft } from './expectationFromCapture';

// ---------------------------------------------------------------------------
// JSON codegen (raw Expectation JSON for initializationJsonPath files)
// ---------------------------------------------------------------------------

/**
 * Build the raw JSON expectation object (matches PUT /mockserver/expectation).
 */
export function expectationToJsonObject(draft: ExpectationDraft): Record<string, unknown> {
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
 * Produce Java code using the Llm.* static factories and HttpLlmResponse builder.
 */
export function expectationToJava(draft: ExpectationDraft): string {
  const lines: string[] = [];

  lines.push('import static org.mockserver.client.Llm.*;');
  lines.push('import static org.mockserver.model.HttpRequest.request;');
  lines.push('import static org.mockserver.model.HttpLlmResponse.llmResponse;');
  lines.push('import static org.mockserver.mock.Expectation.when;');
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

// ---------------------------------------------------------------------------
// MCP tool call body (for mock_llm_completion)
// ---------------------------------------------------------------------------

/**
 * Produce the MCP `tools/call` arguments for the `mock_llm_completion` tool.
 */
export function expectationToMcpArgs(draft: ExpectationDraft): Record<string, unknown> {
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
