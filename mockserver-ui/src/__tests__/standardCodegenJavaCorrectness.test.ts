/**
 * Regression coverage for the Java client codegen produced by standardCodegen.ts. These
 * pin fixes to bugs where the generated Java did not compile against the real MockServer
 * client API or diverged from the JSON payload:
 *
 *  - DNS expectations emitted a bare request() instead of a dnsRequest() matcher.
 *  - SSE / WebSocket / binary / DNS / gRPC-stream responses used a .respond(...) overload
 *    that does not exist (the client exposes respondWithSse/WebSocket/Binary/Dns/GrpcStream).
 *  - forward-with-fallback used .forward(...) instead of the .forwardWithFallback(...) method.
 *  - chaos chained .withChaos(...) AFTER the terminal action (which returns Expectation[]);
 *    it must come before, on the ForwardChainExpectation.
 *  - DNS answer records / SSE retry / gRPC headers were emitted in JSON but dropped from Java.
 */
import { describe, it, expect } from 'vitest';
import {
  standardToJava,
  buildExpectationJson,
  type StandardMatcher,
  type StandardActionPayload,
} from '../lib/standardCodegen';

function httpMatcher(overrides?: Partial<StandardMatcher>): StandardMatcher {
  return {
    id: '', method: 'GET', path: '/api', headers: '', queryString: '', cookies: '',
    pathParams: '', body: '', bodyBinary: false, bodyMatcherType: 'string',
    secure: false, priority: 0, times: 0, ...overrides,
  };
}

function dnsMatcher(): StandardMatcher {
  return httpMatcher({ method: '', path: '', dns: { dnsName: 'api.example.com', dnsType: 'A', dnsClass: 'IN' } });
}

describe('DNS request matcher Java', () => {
  it('emits a dnsRequest() matcher, not a bare request()', () => {
    const java = standardToJava(dnsMatcher(), { type: 'dns_response', dnsResponse: { responseCode: 'NOERROR', answerRecords: '' } });
    expect(java).toContain('dnsRequest()');
    expect(java).toContain('.withDnsName("api.example.com")');
    expect(java).toContain('.withDnsType(DnsRecordType.A)');
    expect(java).toContain('.withDnsClass(DnsRecordClass.IN)');
    expect(java).toContain('import static org.mockserver.model.DnsRequestDefinition.dnsRequest;');
    expect(java).toContain('import org.mockserver.model.DnsRecordType;');
    expect(java).toContain('import org.mockserver.model.DnsRecordClass;');
    // must NOT fall back to the HTTP request matcher
    expect(java).not.toContain('request()');
  });
});

describe('streaming/binary/DNS response actions use the type-specific fluent method', () => {
  const cases: { type: StandardActionPayload['type']; action: StandardActionPayload; method: string }[] = [
    { type: 'sse', action: { type: 'sse', sse: { statusCode: 200, headers: '', events: [{ event: 'm', data: 'd', id: '', retry: '' }], closeConnection: false } }, method: '.respondWithSse(' },
    { type: 'websocket', action: { type: 'websocket', websocket: { subprotocol: '', messages: 'hi', closeConnection: false, matchers: [] } }, method: '.respondWithWebSocket(' },
    { type: 'binary_response', action: { type: 'binary_response', binaryResponse: { binaryData: 'SGk=' } }, method: '.respondWithBinary(' },
    { type: 'dns_response', action: { type: 'dns_response', dnsResponse: { responseCode: 'NXDOMAIN', answerRecords: '' } }, method: '.respondWithDns(' },
    { type: 'grpc_stream', action: { type: 'grpc_stream', grpcStream: { statusName: 'OK', statusMessage: '', headers: '', messages: '{"a":1}', closeConnection: false } }, method: '.respondWithGrpcStream(' },
  ];
  for (const c of cases) {
    it(`${c.type} uses ${c.method.slice(0, -1)} not .respond(`, () => {
      const java = standardToJava(httpMatcher(), c.action);
      expect(java).toContain(c.method);
      // the non-existent generic .respond(<thatType>) overload must not be used
      expect(java).not.toMatch(/\.respond\(\s*\n\s*(sseResponse|webSocketResponse|binaryResponse|dnsResponse|grpcStreamResponse)/);
    });
  }
});

describe('forward-with-fallback Java', () => {
  it('uses .forwardWithFallback(...) not .forward(...)', () => {
    const java = standardToJava(httpMatcher(), {
      type: 'forward_fallback',
      forwardFallback: { scheme: 'HTTP', host: 'h', port: 80, fallbackStatusCode: 200, fallbackBody: '', fallbackOnStatusCodes: '500', fallbackOnTimeout: true },
    });
    expect(java).toContain('.forwardWithFallback(');
    expect(java).not.toContain('.forward(forwardWithFallback');
  });
});

describe('chaos Java placement', () => {
  it('emits .withChaos(...) before the terminal action', () => {
    const java = standardToJava(httpMatcher(), {
      type: 'static',
      static: { statusCode: 200, body: 'ok', contentType: '' },
      chaos: { errorStatus: 503, errorProbability: 1 },
    });
    const chaosIdx = java.indexOf('.withChaos(');
    const respondIdx = java.indexOf('.respond(');
    expect(chaosIdx).toBeGreaterThan(-1);
    expect(respondIdx).toBeGreaterThan(-1);
    expect(chaosIdx).toBeLessThan(respondIdx);
  });
});

describe('Java/JSON parity additions', () => {
  it('SSE emits .withRetry for events with a retry value', () => {
    const java = standardToJava(httpMatcher(), {
      type: 'sse',
      sse: { statusCode: 200, headers: '', events: [{ event: 'm', data: 'd', id: '', retry: '3000' }], closeConnection: false },
    });
    expect(java).toContain('.withRetry(3000)');
  });

  it('gRPC stream emits headers', () => {
    const java = standardToJava(httpMatcher(), {
      type: 'grpc_stream',
      grpcStream: { statusName: 'OK', statusMessage: '', headers: 'x-trace: abc', messages: '', closeConnection: false },
    });
    expect(java).toContain('.withHeader("x-trace", "abc")');
  });

  it('DNS response emits answer records parsed from the JSON field', () => {
    const records = JSON.stringify([{ name: 'api.example.com', type: 'A', ttl: 60, value: '1.2.3.4' }]);
    const java = standardToJava(dnsMatcher(), { type: 'dns_response', dnsResponse: { responseCode: 'NOERROR', answerRecords: records } });
    // the nested dnsRecord() builder is emitted across indented lines, not one long call
    expect(java).toMatch(/\.withAnswerRecord\(\s*\n\s*dnsRecord\(\)/);
    expect(java).toContain('.withName("api.example.com")');
    expect(java).toContain('.withType(DnsRecordType.A)');
    expect(java).toContain('.withTtl(60)');
    expect(java).toContain('.withValue("1.2.3.4")');
    expect(java).toContain('import static org.mockserver.model.DnsRecord.dnsRecord;');
  });
});

describe('static response connectionOptions', () => {
  const action: StandardActionPayload = {
    type: 'static',
    static: { statusCode: 200, body: '', contentType: '', connectionOptions: { keepAliveOverride: false, contentLengthHeaderOverride: 999, suppressConnectionHeader: true } },
  };

  it('emits connectionOptions in the httpResponse JSON (only set fields)', () => {
    const resp = buildExpectationJson(httpMatcher(), action)['httpResponse'] as Record<string, unknown>;
    expect(resp['connectionOptions']).toEqual({ keepAliveOverride: false, contentLengthHeaderOverride: 999, suppressConnectionHeader: true });
  });

  it('emits .withConnectionOptions(...) in Java with the import', () => {
    const java = standardToJava(httpMatcher(), action);
    expect(java).toContain('.withConnectionOptions(');
    expect(java).toContain('.withKeepAliveOverride(false)');
    expect(java).toContain('.withContentLengthHeaderOverride(999)');
    expect(java).toContain('.withSuppressConnectionHeader(true)');
    expect(java).toContain('import static org.mockserver.model.ConnectionOptions.connectionOptions;');
  });

  it('omits connectionOptions when nothing is set', () => {
    const resp = buildExpectationJson(httpMatcher(), { type: 'static', static: { statusCode: 200, body: '', contentType: '' } })['httpResponse'] as Record<string, unknown>;
    expect(resp).not.toHaveProperty('connectionOptions');
  });
});

describe('expectation timeToLive', () => {
  const ttlAction: StandardActionPayload = { type: 'static', static: { statusCode: 200, body: '', contentType: '' } };

  it('emits a SECONDS timeToLive when ttlSeconds > 0', () => {
    const json = buildExpectationJson(httpMatcher({ ttlSeconds: 90 }), ttlAction);
    expect(json['timeToLive']).toEqual({ timeUnit: 'SECONDS', timeToLive: 90, unlimited: false });
  });

  it('omits timeToLive when ttlSeconds is 0 or absent', () => {
    expect(buildExpectationJson(httpMatcher({ ttlSeconds: 0 }), ttlAction)).not.toHaveProperty('timeToLive');
    expect(buildExpectationJson(httpMatcher(), ttlAction)).not.toHaveProperty('timeToLive');
  });
});

describe('static response preserves arbitrary response headers', () => {
  const action: StandardActionPayload = {
    type: 'static',
    static: { statusCode: 302, body: '', contentType: '', headers: 'Location: /new\nCache-Control: no-cache' },
  };

  it('emits the extra headers (plus content-type) in the JSON payload', () => {
    const json = buildExpectationJson(httpMatcher(), action);
    const resp = json['httpResponse'] as Record<string, unknown>;
    expect(resp['headers']).toEqual({
      'Location': ['/new'],
      'Cache-Control': ['no-cache'],
    });
  });

  it('emits .withHeader(...) for each extra header in the Java snippet', () => {
    const java = standardToJava(httpMatcher(), action);
    expect(java).toContain('.withHeader("Location", "/new")');
    expect(java).toContain('.withHeader("Cache-Control", "no-cache")');
  });

  it('does not double-emit content-type if the user also types it in the headers textarea', () => {
    const a: StandardActionPayload = {
      type: 'static',
      static: { statusCode: 200, body: '', contentType: 'application/json', headers: 'Content-Type: text/html\nX-A: 1' },
    };
    const json = buildExpectationJson(httpMatcher(), a);
    const resp = json['httpResponse'] as Record<string, unknown>;
    // the dedicated contentType field wins; the textarea content-type is dropped
    expect(resp['headers']).toEqual({ 'X-A': ['1'], 'content-type': ['application/json'] });
    const java = standardToJava(httpMatcher(), a);
    expect(java.match(/Content-Type/gi)?.length).toBe(1);
  });

  it('merges extra headers with an explicit content-type', () => {
    const json = buildExpectationJson(httpMatcher(), {
      type: 'static',
      static: { statusCode: 200, body: 'x', contentType: 'application/json', headers: 'X-Trace: abc' },
    });
    const resp = json['httpResponse'] as Record<string, unknown>;
    expect(resp['headers']).toEqual({
      'X-Trace': ['abc'],
      'content-type': ['application/json'],
    });
  });
});
