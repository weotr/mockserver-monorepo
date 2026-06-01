/**
 * Tests for the new action types added to standardCodegen:
 * - SSE response (httpSseResponse)
 * - Binary response (binaryResponse)
 * - DNS response (dnsResponse)
 * - Forward template (httpForwardTemplate)
 * - Forward class callback (httpForwardClassCallback)
 * - gRPC stream response (grpcStreamResponse)
 *
 * Each test asserts the JSON shape matches the MockServer Java DTO discriminator.
 */
import { describe, it, expect } from 'vitest';
import {
  buildExpectationJson,
  standardToJava,
  standardToCurl,
  type StandardMatcher,
  type StandardActionPayload,
} from '../lib/standardCodegen';

function baseMatcher(overrides?: Partial<StandardMatcher>): StandardMatcher {
  return {
    id: '',
    method: 'POST',
    path: '/api/test',
    headers: '',
    queryString: '',
    cookies: '',
    pathParams: '',
    body: '',
    bodyBinary: false,
    bodyMatcherType: 'string',
    secure: false,
    priority: 0,
    times: 0,
    ...overrides,
  };
}

// ---------------------------------------------------------------------------
// SSE response — JSON key: httpSseResponse
// Verified against HttpSseResponseDTO: statusCode, headers, events[], closeConnection
// SseEventDTO fields: event, data, id, retry, delay
// ---------------------------------------------------------------------------

describe('buildExpectationJson SSE response', () => {
  it('emits httpSseResponse with events', () => {
    const action: StandardActionPayload = {
      type: 'sse',
      sse: {
        statusCode: 200,
        headers: 'Cache-Control: no-cache',
        events: [
          { event: 'message', data: '{"update":1}', id: '1', retry: '3000' },
          { event: 'heartbeat', data: '', id: '', retry: '' },
        ],
        closeConnection: true,
      },
    };
    const result = buildExpectationJson(baseMatcher(), action);
    const sse = result['httpSseResponse'] as Record<string, unknown>;
    expect(sse).toBeDefined();
    expect(sse['statusCode']).toBe(200);
    expect(sse['closeConnection']).toBe(true);
    expect(sse['headers']).toEqual({ 'Cache-Control': ['no-cache'] });

    const events = sse['events'] as Record<string, unknown>[];
    // Both events pass the filter: first has data+event, second has event only
    expect(events).toHaveLength(2);
    expect(events[0]).toEqual({
      event: 'message',
      data: '{"update":1}',
      id: '1',
      retry: 3000,
    });
    expect(events[1]).toEqual({ event: 'heartbeat' });
  });

  it('emits httpSseResponse without optional fields when empty', () => {
    const action: StandardActionPayload = {
      type: 'sse',
      sse: {
        statusCode: 0,
        headers: '',
        events: [{ event: '', data: 'hello', id: '', retry: '' }],
        closeConnection: false,
      },
    };
    const result = buildExpectationJson(baseMatcher(), action);
    const sse = result['httpSseResponse'] as Record<string, unknown>;
    expect(sse).toBeDefined();
    expect(sse['headers']).toBeUndefined();
    expect(sse['closeConnection']).toBeUndefined();
    const events = sse['events'] as Record<string, unknown>[];
    expect(events[0]).toEqual({ data: 'hello' });
  });
});

// ---------------------------------------------------------------------------
// Binary response — JSON key: binaryResponse
// Verified against BinaryResponseDTO: binaryData (byte[] → base64)
// ---------------------------------------------------------------------------

describe('buildExpectationJson binary response', () => {
  it('emits binaryResponse with base64 data', () => {
    const action: StandardActionPayload = {
      type: 'binary_response',
      binaryResponse: { binaryData: 'SGVsbG8sIFdvcmxkIQ==' },
    };
    const result = buildExpectationJson(baseMatcher(), action);
    const bin = result['binaryResponse'] as Record<string, unknown>;
    expect(bin).toBeDefined();
    expect(bin['binaryData']).toBe('SGVsbG8sIFdvcmxkIQ==');
  });

  it('emits binaryResponse without binaryData when empty', () => {
    const action: StandardActionPayload = {
      type: 'binary_response',
      binaryResponse: { binaryData: '  ' },
    };
    const result = buildExpectationJson(baseMatcher(), action);
    const bin = result['binaryResponse'] as Record<string, unknown>;
    expect(bin).toBeDefined();
    expect(bin['binaryData']).toBeUndefined();
  });
});

// ---------------------------------------------------------------------------
// DNS response — JSON key: dnsResponse
// Verified against DnsResponseDTO: responseCode (enum), answerRecords[]
// ---------------------------------------------------------------------------

describe('buildExpectationJson DNS response', () => {
  it('emits dnsResponse with responseCode and answerRecords', () => {
    const records = JSON.stringify([{ name: 'example.com', type: 'A', value: '127.0.0.1' }]);
    const action: StandardActionPayload = {
      type: 'dns_response',
      dnsResponse: { responseCode: 'NXDOMAIN', answerRecords: records },
    };
    const result = buildExpectationJson(baseMatcher(), action);
    const dns = result['dnsResponse'] as Record<string, unknown>;
    expect(dns).toBeDefined();
    expect(dns['responseCode']).toBe('NXDOMAIN');
    expect(dns['answerRecords']).toEqual([{ name: 'example.com', type: 'A', value: '127.0.0.1' }]);
  });

  it('emits dnsResponse with only responseCode when records are empty', () => {
    const action: StandardActionPayload = {
      type: 'dns_response',
      dnsResponse: { responseCode: 'SERVFAIL', answerRecords: '' },
    };
    const result = buildExpectationJson(baseMatcher(), action);
    const dns = result['dnsResponse'] as Record<string, unknown>;
    expect(dns).toBeDefined();
    expect(dns['responseCode']).toBe('SERVFAIL');
    expect(dns['answerRecords']).toBeUndefined();
  });
});

// ---------------------------------------------------------------------------
// Forward template — JSON key: httpForwardTemplate
// Verified against HttpTemplateDTO: templateType, template
// ---------------------------------------------------------------------------

describe('buildExpectationJson forward template', () => {
  it('emits httpForwardTemplate with templateType and template', () => {
    const action: StandardActionPayload = {
      type: 'forward_template',
      forwardTemplate: {
        templateType: 'JAVASCRIPT',
        template: 'return { "path": "/upstream" + request.path };',
      },
    };
    const result = buildExpectationJson(baseMatcher(), action);
    const ft = result['httpForwardTemplate'] as Record<string, unknown>;
    expect(ft).toBeDefined();
    expect(ft['templateType']).toBe('JAVASCRIPT');
    expect(ft['template']).toBe('return { "path": "/upstream" + request.path };');
  });
});

// ---------------------------------------------------------------------------
// Forward class callback — JSON key: httpForwardClassCallback
// Verified against HttpClassCallbackDTO: callbackClass
// ---------------------------------------------------------------------------

describe('buildExpectationJson forward class callback', () => {
  it('emits httpForwardClassCallback with callbackClass', () => {
    const action: StandardActionPayload = {
      type: 'forward_class_callback',
      forwardClassCallback: { callbackClass: 'com.example.MyForwardCallback' },
    };
    const result = buildExpectationJson(baseMatcher(), action);
    const fc = result['httpForwardClassCallback'] as Record<string, unknown>;
    expect(fc).toBeDefined();
    expect(fc['callbackClass']).toBe('com.example.MyForwardCallback');
  });
});

// ---------------------------------------------------------------------------
// gRPC stream response — JSON key: grpcStreamResponse
// Verified against GrpcStreamResponseDTO: statusName, statusMessage, headers, messages[], closeConnection
// GrpcStreamMessageDTO fields: json, delay
// ---------------------------------------------------------------------------

describe('buildExpectationJson gRPC stream response', () => {
  it('emits grpcStreamResponse with messages and status', () => {
    const action: StandardActionPayload = {
      type: 'grpc_stream',
      grpcStream: {
        statusName: 'OK',
        statusMessage: 'success',
        headers: 'grpc-encoding: identity',
        messages: '{"name":"Alice"}\n{"name":"Bob"}',
        closeConnection: true,
      },
    };
    const result = buildExpectationJson(baseMatcher(), action);
    const grpc = result['grpcStreamResponse'] as Record<string, unknown>;
    expect(grpc).toBeDefined();
    expect(grpc['statusName']).toBe('OK');
    expect(grpc['statusMessage']).toBe('success');
    expect(grpc['headers']).toEqual({ 'grpc-encoding': ['identity'] });
    expect(grpc['messages']).toEqual([
      { json: '{"name":"Alice"}' },
      { json: '{"name":"Bob"}' },
    ]);
    expect(grpc['closeConnection']).toBe(true);
  });

  it('emits minimal grpcStreamResponse', () => {
    const action: StandardActionPayload = {
      type: 'grpc_stream',
      grpcStream: {
        statusName: '',
        statusMessage: '',
        headers: '',
        messages: '',
        closeConnection: false,
      },
    };
    const result = buildExpectationJson(baseMatcher(), action);
    const grpc = result['grpcStreamResponse'] as Record<string, unknown>;
    expect(grpc).toBeDefined();
    expect(grpc['statusName']).toBeUndefined();
    expect(grpc['messages']).toBeUndefined();
    expect(grpc['closeConnection']).toBeUndefined();
  });
});

// ---------------------------------------------------------------------------
// Java codegen — verify imports and key snippets for new types
// ---------------------------------------------------------------------------

describe('standardToJava for new action types', () => {
  it('generates SSE Java snippet', () => {
    const action: StandardActionPayload = {
      type: 'sse',
      sse: {
        statusCode: 200,
        headers: '',
        events: [{ event: 'update', data: 'hello', id: '1', retry: '' }],
        closeConnection: false,
      },
    };
    const java = standardToJava(baseMatcher(), action);
    expect(java).toContain('import static org.mockserver.model.HttpSseResponse.sseResponse');
    expect(java).toContain('sseResponse()');
    expect(java).toContain('.withEvent(sseEvent()');
  });

  it('generates binary response Java snippet', () => {
    const action: StandardActionPayload = {
      type: 'binary_response',
      binaryResponse: { binaryData: 'SGVsbG8=' },
    };
    const java = standardToJava(baseMatcher(), action);
    expect(java).toContain('import static org.mockserver.model.BinaryResponse.binaryResponse');
    expect(java).toContain('binaryResponse()');
    expect(java).toContain('Base64.getDecoder().decode');
  });

  it('generates DNS response Java snippet', () => {
    const action: StandardActionPayload = {
      type: 'dns_response',
      dnsResponse: { responseCode: 'NXDOMAIN', answerRecords: '' },
    };
    const java = standardToJava(baseMatcher(), action);
    expect(java).toContain('import static org.mockserver.model.DnsResponse.dnsResponse');
    expect(java).toContain('DnsResponseCode.NXDOMAIN');
  });

  it('generates forward template Java snippet', () => {
    const action: StandardActionPayload = {
      type: 'forward_template',
      forwardTemplate: { templateType: 'JAVASCRIPT', template: 'return {};' },
    };
    const java = standardToJava(baseMatcher(), action);
    expect(java).toContain('TemplateType.JAVASCRIPT');
    expect(java).toContain('.forward(');
  });

  it('generates forward class callback Java snippet', () => {
    const action: StandardActionPayload = {
      type: 'forward_class_callback',
      forwardClassCallback: { callbackClass: 'com.example.Fwd' },
    };
    const java = standardToJava(baseMatcher(), action);
    expect(java).toContain('.withCallbackClass("com.example.Fwd")');
    expect(java).toContain('.forward(');
  });

  it('generates gRPC stream Java snippet', () => {
    const action: StandardActionPayload = {
      type: 'grpc_stream',
      grpcStream: {
        statusName: 'OK',
        statusMessage: '',
        headers: '',
        messages: '{"a":1}',
        closeConnection: false,
      },
    };
    const java = standardToJava(baseMatcher(), action);
    expect(java).toContain('import static org.mockserver.model.GrpcStreamResponse.grpcStreamResponse');
    expect(java).toContain('.withStatusName("OK")');
    expect(java).toContain('.withMessage(');
  });
});

// ---------------------------------------------------------------------------
// curl codegen — verify each new type includes the discriminator key
// ---------------------------------------------------------------------------

describe('standardToCurl for new action types', () => {
  it('includes httpSseResponse in curl payload', () => {
    const action: StandardActionPayload = {
      type: 'sse',
      sse: {
        statusCode: 200,
        headers: '',
        events: [{ event: '', data: 'x', id: '', retry: '' }],
        closeConnection: false,
      },
    };
    const curl = standardToCurl(baseMatcher(), action, 'http://localhost:1080');
    expect(curl).toContain('"httpSseResponse"');
  });

  it('includes binaryResponse in curl payload', () => {
    const action: StandardActionPayload = {
      type: 'binary_response',
      binaryResponse: { binaryData: 'AA==' },
    };
    const curl = standardToCurl(baseMatcher(), action, 'http://localhost:1080');
    expect(curl).toContain('"binaryResponse"');
  });

  it('includes dnsResponse in curl payload', () => {
    const action: StandardActionPayload = {
      type: 'dns_response',
      dnsResponse: { responseCode: 'NOERROR', answerRecords: '' },
    };
    const curl = standardToCurl(baseMatcher(), action, 'http://localhost:1080');
    expect(curl).toContain('"dnsResponse"');
  });

  it('includes httpForwardTemplate in curl payload', () => {
    const action: StandardActionPayload = {
      type: 'forward_template',
      forwardTemplate: { templateType: 'VELOCITY', template: 'x' },
    };
    const curl = standardToCurl(baseMatcher(), action, 'http://localhost:1080');
    expect(curl).toContain('"httpForwardTemplate"');
  });

  it('includes httpForwardClassCallback in curl payload', () => {
    const action: StandardActionPayload = {
      type: 'forward_class_callback',
      forwardClassCallback: { callbackClass: 'com.example.Cb' },
    };
    const curl = standardToCurl(baseMatcher(), action, 'http://localhost:1080');
    expect(curl).toContain('"httpForwardClassCallback"');
  });

  it('includes grpcStreamResponse in curl payload', () => {
    const action: StandardActionPayload = {
      type: 'grpc_stream',
      grpcStream: {
        statusName: 'OK',
        statusMessage: '',
        headers: '',
        messages: '{"a":1}',
        closeConnection: false,
      },
    };
    const curl = standardToCurl(baseMatcher(), action, 'http://localhost:1080');
    expect(curl).toContain('"grpcStreamResponse"');
  });
});
