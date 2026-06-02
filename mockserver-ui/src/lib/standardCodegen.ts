/**
 * Codegen for "standard" (non-LLM) MockServer expectations — emits Java
 * client snippets, the raw JSON payload sent to PUT /mockserver/expectation,
 * and an equivalent curl command. Used by the Composer's Review section so
 * the user can copy / share the expectation outside the UI.
 *
 * The shape of the JSON payload mirrors what ComposerView.tsx builds before
 * the PUT request; keep the two in sync.
 */

// ---------------------------------------------------------------------------
// Types — mirror ComposerView's per-action state contracts.
// ---------------------------------------------------------------------------

export type StandardActionType =
  | 'static'
  | 'forward'
  | 'forward_override'
  | 'forward_fallback'
  | 'callback'
  | 'template'
  | 'error'
  | 'websocket'
  | 'sse'
  | 'binary_response'
  | 'dns_response'
  | 'forward_template'
  | 'forward_class_callback'
  | 'grpc_stream';

export interface StandardMatcher {
  id: string;
  method: string;
  path: string;
  headers: string;
  queryString: string;
  cookies: string;
  pathParams: string;
  body: string;
  bodyBinary: boolean;
  bodyMatcherType: BodyMatcherType;
  graphqlOptions?: GraphQLMatcherOptions;
  secure: boolean;
  priority: number;
  times: number;
  /** DNS-specific matcher fields — populated only when the expectation kind is 'dns'. */
  dns?: StandardDnsMatcher;
}

// ---------------------------------------------------------------------------
// DNS request matcher — the server routes to DnsRequestDefinition when
// the httpRequest object contains a `dnsName` field.
// ---------------------------------------------------------------------------

export type DnsRecordType = 'A' | 'AAAA' | 'CNAME' | 'MX' | 'SRV' | 'TXT' | 'PTR';
export type DnsRecordClass = 'IN' | 'CH' | 'HS' | 'ANY';

export interface StandardDnsMatcher {
  dnsName: string;
  dnsType: DnsRecordType | '';
  dnsClass: DnsRecordClass | '';
}

export interface StandardStaticState {
  statusCode: number;
  body: string;
  contentType: string;
}

export interface StandardForwardState {
  scheme: 'HTTP' | 'HTTPS';
  host: string;
  port: number;
}

export interface StandardForwardOverrideState {
  overrideMethod: string;
  overrideHost: string;
  overrideScheme: 'HTTP' | 'HTTPS' | '';
  overridePath: string;
  overrideQueryString: string;
  overrideHeaders: string;
  overrideBody: string;
}

export interface StandardCallbackState {
  callbackClass: string;
}

export interface StandardTemplateState {
  templateType: 'VELOCITY' | 'JAVASCRIPT' | 'MUSTACHE';
  template: string;
}

export interface StandardErrorState {
  dropConnection: boolean;
  responseBytesB64: string;
  delayValue: number;
  delayUnit: 'MILLISECONDS' | 'SECONDS' | 'MINUTES';
}

export type SelectionSetMatchType = 'NORMALISED_STRING' | 'AST_EXACT' | 'AST_SUBSET';

export type BodyMatcherType =
  | 'string'
  | 'graphql'
  | 'binary'
  | 'json-schema'
  | 'json-path'
  | 'xml'
  | 'xml-schema'
  | 'xpath'
  | 'regex'
  | 'parameters';

export interface GraphQLMatcherOptions {
  selectionSetMatchType: SelectionSetMatchType;
  fields: string; // comma-separated field names
}

export interface StandardForwardFallbackState {
  scheme: 'HTTP' | 'HTTPS';
  host: string;
  port: number;
  fallbackStatusCode: number;
  fallbackBody: string;
  fallbackOnStatusCodes: string; // comma-separated, e.g. "500,502,503"
  fallbackOnTimeout: boolean;
}

export type WebSocketFrameType = 'TEXT' | 'BINARY' | 'PING' | 'PONG' | 'ANY';

export interface WebSocketMatcherDraft {
  frameType: WebSocketFrameType;
  textMatcher: string;
  responses: string; // one message per line
}

export interface StandardWebSocketState {
  subprotocol: string;
  messages: string; // one message per line (text frames)
  closeConnection: boolean;
  matchers: WebSocketMatcherDraft[];
}

// ---------------------------------------------------------------------------
// SSE response state (JSON key: httpSseResponse)
// SseEvent fields: event, data, id, retry, delay
// ---------------------------------------------------------------------------

export interface StandardSseEventDraft {
  event: string;
  data: string;
  id: string;
  retry: string; // numeric string or empty
}

export interface StandardSseState {
  statusCode: number;
  headers: string; // "Name: value" lines
  events: StandardSseEventDraft[];
  closeConnection: boolean;
}

// ---------------------------------------------------------------------------
// Binary response state (JSON key: binaryResponse)
// BinaryResponse fields: binaryData (byte[], serialised as base64)
// ---------------------------------------------------------------------------

export interface StandardBinaryResponseState {
  binaryData: string; // base64-encoded
}

// ---------------------------------------------------------------------------
// DNS response state (JSON key: dnsResponse)
// DnsResponse fields: responseCode (enum), answerRecords, authorityRecords, additionalRecords
// ---------------------------------------------------------------------------

export type DnsResponseCodeName = 'NOERROR' | 'FORMERR' | 'SERVFAIL' | 'NXDOMAIN' | 'NOTIMP' | 'REFUSED';

export interface StandardDnsState {
  responseCode: DnsResponseCodeName;
  answerRecords: string; // JSON array text for simplicity
}

// ---------------------------------------------------------------------------
// Forward template state (JSON key: httpForwardTemplate)
// Same shape as httpResponseTemplate: templateType + template
// ---------------------------------------------------------------------------

export interface StandardForwardTemplateState {
  templateType: 'VELOCITY' | 'JAVASCRIPT' | 'MUSTACHE';
  template: string;
}

// ---------------------------------------------------------------------------
// Forward class callback state (JSON key: httpForwardClassCallback)
// Same shape as httpResponseClassCallback: callbackClass FQCN
// ---------------------------------------------------------------------------

export interface StandardForwardClassCallbackState {
  callbackClass: string;
}

// ---------------------------------------------------------------------------
// gRPC stream response state (JSON key: grpcStreamResponse)
// GrpcStreamResponse fields: statusName, statusMessage, headers, messages[], closeConnection
// GrpcStreamMessage fields: json, delay
// ---------------------------------------------------------------------------

export interface StandardGrpcStreamState {
  statusName: string;
  statusMessage: string;
  headers: string; // "Name: value" lines
  messages: string; // one JSON message per line
  closeConnection: boolean;
}

export type ChaosDelayUnit = 'MILLISECONDS' | 'SECONDS' | 'MINUTES';

// ---------------------------------------------------------------------------
// Side-effect actions (before / after actions) — webhook (httpRequest) target
// only in this increment. Class/object callbacks are a future increment.
// ---------------------------------------------------------------------------

export type SideEffectPosition = 'before' | 'after';
export type SideEffectDelayUnit = 'MILLISECONDS' | 'SECONDS' | 'MINUTES';
export type SideEffectFailurePolicy = 'BEST_EFFORT' | 'FAIL_FAST';

export interface StandardSideEffectAction {
  position: SideEffectPosition;
  method: string;
  path: string;
  host: string;
  body: string;
  delayValue: number;
  delayUnit: SideEffectDelayUnit;
  // before-only fields:
  blocking: boolean;
  timeoutValue: number;
  timeoutUnit: SideEffectDelayUnit;
  failurePolicy: SideEffectFailurePolicy;
}

/**
 * Draft state for the HTTP chaos profile panel. Maps 1:1 to the seven
 * HttpChaosProfile fields. `undefined` means "not set / omit from JSON".
 */
export interface StandardChaosDraft {
  errorStatus?: number;
  errorProbability?: number;
  retryAfter?: string;
  latencyUnit?: ChaosDelayUnit;
  latencyValue?: number;
  seed?: number;
  succeedFirst?: number;
  failRequestCount?: number;
}

export interface StandardActionPayload {
  type: StandardActionType;
  static?: StandardStaticState;
  forward?: StandardForwardState;
  forwardOverride?: StandardForwardOverrideState;
  forwardFallback?: StandardForwardFallbackState;
  callback?: StandardCallbackState;
  template?: StandardTemplateState;
  error?: StandardErrorState;
  websocket?: StandardWebSocketState;
  sse?: StandardSseState;
  binaryResponse?: StandardBinaryResponseState;
  dnsResponse?: StandardDnsState;
  forwardTemplate?: StandardForwardTemplateState;
  forwardClassCallback?: StandardForwardClassCallbackState;
  grpcStream?: StandardGrpcStreamState;
  chaos?: StandardChaosDraft;
  sideEffects?: StandardSideEffectAction[];
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

export function parseKeyValueLines(
  text: string,
  separator: ':' | '=',
): Record<string, string[]> | undefined {
  const lines = text.split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
  if (lines.length === 0) return undefined;
  const out: Record<string, string[]> = {};
  for (const line of lines) {
    const idx = line.indexOf(separator);
    if (idx < 0) continue;
    const key = line.slice(0, idx).trim();
    const value = line.slice(idx + 1).trim();
    if (!key) continue;
    const arr = out[key] ?? [];
    arr.push(value);
    out[key] = arr;
  }
  return Object.keys(out).length > 0 ? out : undefined;
}

function escapeJava(s: string): string {
  return s
    .replace(/\\/g, '\\\\')
    .replace(/"/g, '\\"')
    .replace(/\n/g, '\\n')
    .replace(/\r/g, '\\r')
    .replace(/\t/g, '\\t');
}

interface ParsedDnsRecord {
  name?: string;
  type?: string;
  dnsClass?: string;
  ttl?: number;
  value?: string;
}

/**
 * Parse the DNS answer-records free-text JSON array (as entered in the composer) into
 * structured records for Java codegen. Returns [] when the text is blank or not a JSON
 * array of objects, so the Java snippet simply omits answer records rather than emitting
 * something uncompilable.
 */
function parseDnsRecords(text: string): ParsedDnsRecord[] {
  if (!text.trim()) return [];
  let parsed: unknown;
  try {
    parsed = JSON.parse(text.trim());
  } catch {
    return [];
  }
  if (!Array.isArray(parsed)) return [];
  const out: ParsedDnsRecord[] = [];
  for (const entry of parsed) {
    if (!entry || typeof entry !== 'object') continue;
    const e = entry as Record<string, unknown>;
    const rec: ParsedDnsRecord = {};
    if (typeof e['name'] === 'string') rec.name = e['name'];
    if (typeof e['type'] === 'string') rec.type = e['type'];
    if (typeof e['dnsClass'] === 'string') rec.dnsClass = e['dnsClass'];
    if (typeof e['ttl'] === 'number') rec.ttl = e['ttl'];
    if (typeof e['value'] === 'string') rec.value = e['value'];
    out.push(rec);
  }
  return out;
}

// ---------------------------------------------------------------------------
// JSON codegen — produces the exact payload PUT /mockserver/expectation
// ---------------------------------------------------------------------------

export function buildExpectationJson(
  matcher: StandardMatcher,
  action: StandardActionPayload,
): Record<string, unknown> {
  // DNS expectations use a completely different request matcher shape:
  // { dnsName: "...", dnsType?: "...", dnsClass?: "..." }
  // The JSON key is still "httpRequest" (the server routes to
  // DnsRequestDefinition when the object contains a dnsName field).
  let httpRequest: Record<string, unknown>;

  if (matcher.dns && matcher.dns.dnsName.trim()) {
    httpRequest = { dnsName: matcher.dns.dnsName.trim() };
    if (matcher.dns.dnsType) httpRequest['dnsType'] = matcher.dns.dnsType;
    if (matcher.dns.dnsClass) httpRequest['dnsClass'] = matcher.dns.dnsClass;
  } else {
    httpRequest = { path: matcher.path };
    if (matcher.method) httpRequest['method'] = matcher.method;

    const headers = parseKeyValueLines(matcher.headers, ':');
    if (headers) httpRequest['headers'] = headers;

    const query = parseKeyValueLines(matcher.queryString, '=');
    if (query) httpRequest['queryStringParameters'] = query;

    const cookies = parseKeyValueLines(matcher.cookies, '=');
    if (cookies) {
      const flat: Record<string, string> = {};
      for (const [k, vs] of Object.entries(cookies)) flat[k] = vs[0] ?? '';
      httpRequest['cookies'] = flat;
    }

    const pathParams = parseKeyValueLines(matcher.pathParams, '=');
    if (pathParams) httpRequest['pathParameters'] = pathParams;

    if (matcher.body.trim()) {
      if (matcher.bodyMatcherType === 'binary' || matcher.bodyBinary) {
        httpRequest['body'] = { type: 'BINARY', base64Bytes: matcher.body.trim() };
      } else if (matcher.bodyMatcherType === 'graphql') {
        const gqlBody: Record<string, unknown> = { type: 'GRAPHQL', query: matcher.body.trim() };
        if (matcher.graphqlOptions) {
          if (matcher.graphqlOptions.selectionSetMatchType !== 'NORMALISED_STRING') {
            gqlBody['selectionSetMatchType'] = matcher.graphqlOptions.selectionSetMatchType;
          }
          const fields = matcher.graphqlOptions.fields
            .split(',')
            .map((f) => f.trim())
            .filter(Boolean);
          if (fields.length > 0) gqlBody['fields'] = fields;
        }
        httpRequest['body'] = gqlBody;
      } else if (matcher.bodyMatcherType === 'json-schema') {
        httpRequest['body'] = { type: 'JSON_SCHEMA', jsonSchema: matcher.body.trim() };
      } else if (matcher.bodyMatcherType === 'json-path') {
        httpRequest['body'] = { type: 'JSON_PATH', jsonPath: matcher.body.trim() };
      } else if (matcher.bodyMatcherType === 'xml') {
        httpRequest['body'] = { type: 'XML', xml: matcher.body.trim() };
      } else if (matcher.bodyMatcherType === 'xml-schema') {
        httpRequest['body'] = { type: 'XML_SCHEMA', xmlSchema: matcher.body.trim() };
      } else if (matcher.bodyMatcherType === 'xpath') {
        httpRequest['body'] = { type: 'XPATH', xpath: matcher.body.trim() };
      } else if (matcher.bodyMatcherType === 'regex') {
        httpRequest['body'] = { type: 'REGEX', regex: matcher.body.trim() };
      } else if (matcher.bodyMatcherType === 'parameters') {
        const params = parseKeyValueLines(matcher.body, '=');
        httpRequest['body'] = { type: 'PARAMETERS', parameters: params ?? {} };
      } else {
        httpRequest['body'] = matcher.body;
      }
    }
    if (matcher.secure) httpRequest['secure'] = true;
  }

  const out: Record<string, unknown> = { httpRequest };

  switch (action.type) {
    case 'static':
      if (action.static) {
        const payload: Record<string, unknown> = { statusCode: action.static.statusCode };
        if (action.static.body) payload['body'] = action.static.body;
        if (action.static.contentType) {
          payload['headers'] = { 'content-type': [action.static.contentType] };
        }
        out['httpResponse'] = payload;
      }
      break;
    case 'forward':
      if (action.forward) {
        out['httpForward'] = {
          scheme: action.forward.scheme,
          host: action.forward.host,
          port: action.forward.port,
        };
      }
      break;
    case 'forward_override':
      if (action.forwardOverride) {
        const override: Record<string, unknown> = {};
        if (action.forwardOverride.overrideMethod) override['method'] = action.forwardOverride.overrideMethod;
        if (action.forwardOverride.overrideScheme) override['secure'] = action.forwardOverride.overrideScheme === 'HTTPS';
        if (action.forwardOverride.overridePath) override['path'] = action.forwardOverride.overridePath;
        const overrideHeaders = parseKeyValueLines(action.forwardOverride.overrideHeaders, ':') ?? {};
        if (action.forwardOverride.overrideHost) {
          overrideHeaders['Host'] = [action.forwardOverride.overrideHost];
        }
        if (Object.keys(overrideHeaders).length > 0) override['headers'] = overrideHeaders;
        const overrideQuery = parseKeyValueLines(action.forwardOverride.overrideQueryString, '=');
        if (overrideQuery) override['queryStringParameters'] = overrideQuery;
        if (action.forwardOverride.overrideBody) override['body'] = action.forwardOverride.overrideBody;
        out['httpOverrideForwardedRequest'] = { requestOverride: override };
      }
      break;
    case 'callback':
      if (action.callback) {
        out['httpResponseClassCallback'] = { callbackClass: action.callback.callbackClass.trim() };
      }
      break;
    case 'template':
      if (action.template) {
        out['httpResponseTemplate'] = {
          templateType: action.template.templateType,
          template: action.template.template,
        };
      }
      break;
    case 'error':
      if (action.error) {
        const payload: Record<string, unknown> = {};
        if (action.error.dropConnection) payload['dropConnection'] = true;
        if (action.error.responseBytesB64.trim()) payload['responseBytes'] = action.error.responseBytesB64.trim();
        if (action.error.delayValue > 0) {
          payload['delay'] = { timeUnit: action.error.delayUnit, value: action.error.delayValue };
        }
        out['httpError'] = payload;
      }
      break;
    case 'forward_fallback':
      if (action.forwardFallback) {
        const fb = action.forwardFallback;
        const fwdPayload: Record<string, unknown> = {
          httpForward: { scheme: fb.scheme, host: fb.host, port: fb.port },
        };
        const fallbackResp: Record<string, unknown> = { statusCode: fb.fallbackStatusCode };
        if (fb.fallbackBody.trim()) fallbackResp['body'] = fb.fallbackBody;
        fwdPayload['fallbackResponse'] = fallbackResp;
        const codes = fb.fallbackOnStatusCodes
          .split(',')
          .map((s) => parseInt(s.trim(), 10))
          .filter((n) => !isNaN(n));
        if (codes.length > 0) fwdPayload['fallbackOnStatusCodes'] = codes;
        if (fb.fallbackOnTimeout) fwdPayload['fallbackOnTimeout'] = true;
        out['httpForwardWithFallback'] = fwdPayload;
      }
      break;
    case 'websocket':
      if (action.websocket) {
        const ws = action.websocket;
        const wsPayload: Record<string, unknown> = {};
        if (ws.subprotocol.trim()) wsPayload['subprotocol'] = ws.subprotocol.trim();
        const msgLines = ws.messages.split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
        if (msgLines.length > 0) {
          wsPayload['messages'] = msgLines.map((text) => ({ text }));
        }
        if (ws.closeConnection) wsPayload['closeConnection'] = true;
        if (ws.matchers.length > 0) {
          wsPayload['matchers'] = ws.matchers.map((m) => {
            const matcherObj: Record<string, unknown> = { frameType: m.frameType };
            if (m.textMatcher.trim()) matcherObj['textMatcher'] = m.textMatcher.trim();
            const respLines = m.responses.split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
            if (respLines.length > 0) {
              matcherObj['responses'] = respLines.map((text) => ({ text }));
            }
            return matcherObj;
          });
        }
        out['httpWebSocketResponse'] = wsPayload;
      }
      break;
    case 'sse':
      if (action.sse) {
        const sse = action.sse;
        const ssePayload: Record<string, unknown> = {};
        if (sse.statusCode) ssePayload['statusCode'] = sse.statusCode;
        const sseHeaders = parseKeyValueLines(sse.headers, ':');
        if (sseHeaders) ssePayload['headers'] = sseHeaders;
        if (sse.events.length > 0) {
          ssePayload['events'] = sse.events
            .filter((ev) => ev.data.trim() || ev.event.trim())
            .map((ev) => {
              const evObj: Record<string, unknown> = {};
              if (ev.event.trim()) evObj['event'] = ev.event.trim();
              if (ev.data.trim()) evObj['data'] = ev.data.trim();
              if (ev.id.trim()) evObj['id'] = ev.id.trim();
              const retryNum = parseInt(ev.retry, 10);
              if (!isNaN(retryNum) && retryNum > 0) evObj['retry'] = retryNum;
              return evObj;
            });
        }
        if (sse.closeConnection) ssePayload['closeConnection'] = true;
        out['httpSseResponse'] = ssePayload;
      }
      break;
    case 'binary_response':
      if (action.binaryResponse) {
        const binPayload: Record<string, unknown> = {};
        if (action.binaryResponse.binaryData.trim()) {
          binPayload['binaryData'] = action.binaryResponse.binaryData.trim();
        }
        out['binaryResponse'] = binPayload;
      }
      break;
    case 'dns_response':
      if (action.dnsResponse) {
        const dnsPayload: Record<string, unknown> = {};
        if (action.dnsResponse.responseCode) {
          dnsPayload['responseCode'] = action.dnsResponse.responseCode;
        }
        if (action.dnsResponse.answerRecords.trim()) {
          try {
            dnsPayload['answerRecords'] = JSON.parse(action.dnsResponse.answerRecords.trim());
          } catch {
            // leave raw if not valid JSON
          }
        }
        out['dnsResponse'] = dnsPayload;
      }
      break;
    case 'forward_template':
      if (action.forwardTemplate) {
        out['httpForwardTemplate'] = {
          templateType: action.forwardTemplate.templateType,
          template: action.forwardTemplate.template,
        };
      }
      break;
    case 'forward_class_callback':
      if (action.forwardClassCallback) {
        out['httpForwardClassCallback'] = {
          callbackClass: action.forwardClassCallback.callbackClass.trim(),
        };
      }
      break;
    case 'grpc_stream':
      if (action.grpcStream) {
        const grpc = action.grpcStream;
        const grpcPayload: Record<string, unknown> = {};
        if (grpc.statusName.trim()) grpcPayload['statusName'] = grpc.statusName.trim();
        if (grpc.statusMessage.trim()) grpcPayload['statusMessage'] = grpc.statusMessage.trim();
        const grpcHeaders = parseKeyValueLines(grpc.headers, ':');
        if (grpcHeaders) grpcPayload['headers'] = grpcHeaders;
        const grpcMsgLines = grpc.messages.split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
        if (grpcMsgLines.length > 0) {
          grpcPayload['messages'] = grpcMsgLines.map((json) => ({ json }));
        }
        if (grpc.closeConnection) grpcPayload['closeConnection'] = true;
        out['grpcStreamResponse'] = grpcPayload;
      }
      break;
  }

  // Chaos profile — top-level sibling of httpRequest / httpResponse.
  if (action.chaos) {
    const chaos = buildChaosJson(action.chaos);
    if (chaos) out['chaos'] = chaos;
  }

  // Side-effect actions — beforeActions / afterActions
  if (action.sideEffects && action.sideEffects.length > 0) {
    const beforeActions = action.sideEffects
      .filter((se) => se.position === 'before' && se.path.trim())
      .map(buildSideEffectActionJson);
    const afterActions = action.sideEffects
      .filter((se) => se.position === 'after' && se.path.trim())
      .map(buildSideEffectActionJson);
    if (beforeActions.length > 0) out['beforeActions'] = beforeActions;
    if (afterActions.length > 0) out['afterActions'] = afterActions;
  }

  if (matcher.id.trim()) out['id'] = matcher.id.trim();
  if (matcher.priority !== 0) out['priority'] = matcher.priority;
  if (matcher.times > 0) {
    out['times'] = { remainingTimes: matcher.times, unlimited: false };
  }

  return out;
}

/**
 * Build the JSON object for a chaos profile draft. Returns `undefined` when
 * all fields are empty/unset so the caller can skip emitting the key entirely.
 */
export function buildChaosJson(draft: StandardChaosDraft): Record<string, unknown> | undefined {
  const out: Record<string, unknown> = {};
  if (draft.errorStatus != null) out['errorStatus'] = draft.errorStatus;
  if (draft.errorProbability != null) out['errorProbability'] = draft.errorProbability;
  if (draft.retryAfter) out['retryAfter'] = draft.retryAfter;
  if (draft.latencyValue != null && draft.latencyValue > 0) {
    out['latency'] = { timeUnit: draft.latencyUnit ?? 'MILLISECONDS', value: draft.latencyValue };
  }
  if (draft.seed != null) out['seed'] = draft.seed;
  if (draft.succeedFirst != null) out['succeedFirst'] = draft.succeedFirst;
  if (draft.failRequestCount != null) out['failRequestCount'] = draft.failRequestCount;
  return Object.keys(out).length > 0 ? out : undefined;
}

/**
 * Round-trip: parse a top-level `chaos` JSON object from an existing
 * expectation back into a `StandardChaosDraft` for repopulating the composer.
 */
export function chaosFromExpectation(value: Record<string, unknown>): StandardChaosDraft | undefined {
  const raw = value['chaos'];
  if (!raw || typeof raw !== 'object') return undefined;
  const c = raw as Record<string, unknown>;
  const draft: StandardChaosDraft = {};
  if (typeof c['errorStatus'] === 'number') draft.errorStatus = c['errorStatus'] as number;
  if (typeof c['errorProbability'] === 'number') draft.errorProbability = c['errorProbability'] as number;
  if (typeof c['retryAfter'] === 'string') draft.retryAfter = c['retryAfter'] as string;
  if (c['latency'] && typeof c['latency'] === 'object') {
    const lat = c['latency'] as Record<string, unknown>;
    if (typeof lat['value'] === 'number') {
      draft.latencyValue = lat['value'] as number;
      const tu = lat['timeUnit'];
      draft.latencyUnit = tu === 'SECONDS' ? 'SECONDS' : tu === 'MINUTES' ? 'MINUTES' : 'MILLISECONDS';
    }
  }
  if (typeof c['seed'] === 'number') draft.seed = c['seed'] as number;
  if (typeof c['succeedFirst'] === 'number') draft.succeedFirst = c['succeedFirst'] as number;
  if (typeof c['failRequestCount'] === 'number') draft.failRequestCount = c['failRequestCount'] as number;
  // Only return if at least one field was populated
  return Object.keys(draft).length > 0 ? draft : undefined;
}

// ---------------------------------------------------------------------------
// Side-effect actions — JSON helpers
// ---------------------------------------------------------------------------

/**
 * Build a single side-effect action object for the JSON payload.
 * Shared by beforeActions and afterActions emission.
 */
function buildSideEffectActionJson(
  se: StandardSideEffectAction,
): Record<string, unknown> {
  const httpReq: Record<string, unknown> = { path: se.path };
  if (se.method.trim()) httpReq['method'] = se.method.trim();
  // the webhook destination is derived from the Host header (HttpRequest has no
  // top-level host field); this matches the .withHeader("Host", ...) Java preview
  if (se.host.trim()) httpReq['headers'] = { Host: [se.host.trim()] };
  if (se.body.trim()) httpReq['body'] = se.body.trim();
  const out: Record<string, unknown> = { httpRequest: httpReq };
  if (se.delayValue > 0) {
    out['delay'] = { timeUnit: se.delayUnit, value: se.delayValue };
  }
  if (se.position === 'before') {
    // blocking defaults to true — only emit when false to keep JSON minimal
    if (!se.blocking) out['blocking'] = false;
    if (se.timeoutValue > 0) {
      out['timeout'] = { timeUnit: se.timeoutUnit, value: se.timeoutValue };
    }
    // failurePolicy defaults to BEST_EFFORT — only emit when FAIL_FAST
    if (se.failurePolicy === 'FAIL_FAST') out['failurePolicy'] = 'FAIL_FAST';
  }
  return out;
}

/**
 * Round-trip: parse `beforeActions` and `afterActions` from an existing
 * expectation back into `StandardSideEffectAction[]` for the composer.
 * Normalises single-object and array forms. Ignores callback-only entries
 * (no httpRequest).
 */
export function sideEffectsFromExpectation(
  value: Record<string, unknown>,
): StandardSideEffectAction[] | undefined {
  const result: StandardSideEffectAction[] = [];

  function normalise(raw: unknown): Record<string, unknown>[] {
    if (!raw) return [];
    if (Array.isArray(raw)) return raw as Record<string, unknown>[];
    if (typeof raw === 'object') return [raw as Record<string, unknown>];
    return [];
  }

  function parseDelayUnit(unit: unknown): SideEffectDelayUnit {
    if (unit === 'SECONDS') return 'SECONDS';
    if (unit === 'MINUTES') return 'MINUTES';
    return 'MILLISECONDS';
  }

  // the webhook destination is carried in the Host header; tolerate both the
  // object form ({ Host: ["host:port"] }) and the name/values array form, plus
  // a legacy top-level host field
  function extractHost(r: Record<string, unknown>): string {
    if (typeof r['host'] === 'string') return r['host'] as string;
    const headers = r['headers'];
    if (headers && typeof headers === 'object' && !Array.isArray(headers)) {
      const h = headers as Record<string, unknown>;
      const v = h['Host'] ?? h['host'];
      if (Array.isArray(v) && typeof v[0] === 'string') return v[0] as string;
      if (typeof v === 'string') return v;
    } else if (Array.isArray(headers)) {
      for (const entry of headers) {
        if (entry && typeof entry === 'object') {
          const e = entry as Record<string, unknown>;
          const name = typeof e['name'] === 'string' ? (e['name'] as string) : '';
          if (name.toLowerCase() === 'host') {
            const values = e['values'];
            if (Array.isArray(values) && typeof values[0] === 'string') return values[0] as string;
          }
        }
      }
    }
    return '';
  }

  function parseOne(obj: Record<string, unknown>, position: SideEffectPosition): StandardSideEffectAction | null {
    const req = obj['httpRequest'];
    if (!req || typeof req !== 'object') return null;
    const r = req as Record<string, unknown>;
    const delay = obj['delay'] as Record<string, unknown> | undefined;
    const timeout = obj['timeout'] as Record<string, unknown> | undefined;
    return {
      position,
      method: typeof r['method'] === 'string' ? (r['method'] as string) : '',
      path: typeof r['path'] === 'string' ? (r['path'] as string) : '',
      host: extractHost(r),
      body: typeof r['body'] === 'string' ? (r['body'] as string) : '',
      delayValue: typeof delay?.['value'] === 'number' ? (delay['value'] as number) : 0,
      delayUnit: parseDelayUnit(delay?.['timeUnit']),
      blocking: obj['blocking'] !== false, // default true
      timeoutValue: typeof timeout?.['value'] === 'number' ? (timeout['value'] as number) : 0,
      timeoutUnit: parseDelayUnit(timeout?.['timeUnit']),
      failurePolicy: obj['failurePolicy'] === 'FAIL_FAST' ? 'FAIL_FAST' : 'BEST_EFFORT',
    };
  }

  for (const obj of normalise(value['beforeActions'])) {
    const parsed = parseOne(obj, 'before');
    if (parsed) result.push(parsed);
  }
  for (const obj of normalise(value['afterActions'])) {
    const parsed = parseOne(obj, 'after');
    if (parsed) result.push(parsed);
  }

  return result.length > 0 ? result : undefined;
}

export function standardToJson(matcher: StandardMatcher, action: StandardActionPayload): string {
  return JSON.stringify(buildExpectationJson(matcher, action), null, 2);
}

// ---------------------------------------------------------------------------
// Java client codegen — emits a snippet usable with MockServerClient
// ---------------------------------------------------------------------------

function matcherToJava(matcher: StandardMatcher): string {
  // DNS expectations use a DnsRequestDefinition matcher, not an HttpRequest.
  if (matcher.dns && matcher.dns.dnsName.trim()) {
    const dnsLines: string[] = ['dnsRequest()'];
    dnsLines.push(`    .withDnsName("${escapeJava(matcher.dns.dnsName.trim())}")`);
    if (matcher.dns.dnsType) dnsLines.push(`    .withDnsType(DnsRecordType.${matcher.dns.dnsType})`);
    if (matcher.dns.dnsClass) dnsLines.push(`    .withDnsClass(DnsRecordClass.${matcher.dns.dnsClass})`);
    return dnsLines.join('\n');
  }
  const lines: string[] = ['request()'];
  if (matcher.method) lines.push(`    .withMethod("${escapeJava(matcher.method)}")`);
  if (matcher.path) lines.push(`    .withPath("${escapeJava(matcher.path)}")`);

  const headers = parseKeyValueLines(matcher.headers, ':');
  if (headers) {
    for (const [k, vs] of Object.entries(headers)) {
      for (const v of vs) {
        lines.push(`    .withHeader("${escapeJava(k)}", "${escapeJava(v)}")`);
      }
    }
  }

  const query = parseKeyValueLines(matcher.queryString, '=');
  if (query) {
    for (const [k, vs] of Object.entries(query)) {
      const values = vs.map((v) => `"${escapeJava(v)}"`).join(', ');
      lines.push(`    .withQueryStringParameter("${escapeJava(k)}", ${values})`);
    }
  }

  const cookies = parseKeyValueLines(matcher.cookies, '=');
  if (cookies) {
    for (const [k, vs] of Object.entries(cookies)) {
      lines.push(`    .withCookie("${escapeJava(k)}", "${escapeJava(vs[0] ?? '')}")`);
    }
  }

  const pathParams = parseKeyValueLines(matcher.pathParams, '=');
  if (pathParams) {
    for (const [k, vs] of Object.entries(pathParams)) {
      lines.push(`    .withPathParameter("${escapeJava(k)}", "${escapeJava(vs[0] ?? '')}")`);
    }
  }

  if (matcher.body.trim()) {
    if (matcher.bodyMatcherType === 'binary' || matcher.bodyBinary) {
      lines.push(`    .withBody(binary(Base64.getDecoder().decode("${escapeJava(matcher.body.trim())}")))`);
    } else if (matcher.bodyMatcherType === 'graphql') {
      let gql = `graphQL("${escapeJava(matcher.body.trim())}")`;
      if (matcher.graphqlOptions && matcher.graphqlOptions.selectionSetMatchType !== 'NORMALISED_STRING') {
        gql += `\n        .withSelectionSetMatchType(SelectionSetMatchType.${matcher.graphqlOptions.selectionSetMatchType})`;
        const fields = matcher.graphqlOptions.fields.split(',').map((f) => f.trim()).filter(Boolean);
        if (fields.length > 0) {
          gql += `\n        .withFields(${fields.map((f) => `"${escapeJava(f)}"`).join(', ')})`;
        }
      }
      lines.push(`    .withBody(${gql})`);
    } else if (matcher.bodyMatcherType === 'json-schema') {
      lines.push(`    .withBody(jsonSchema("${escapeJava(matcher.body.trim())}"))`);
    } else if (matcher.bodyMatcherType === 'json-path') {
      lines.push(`    .withBody(jsonPath("${escapeJava(matcher.body.trim())}"))`);
    } else if (matcher.bodyMatcherType === 'xml') {
      lines.push(`    .withBody(xml("${escapeJava(matcher.body.trim())}"))`);
    } else if (matcher.bodyMatcherType === 'xml-schema') {
      lines.push(`    .withBody(xmlSchema("${escapeJava(matcher.body.trim())}"))`);
    } else if (matcher.bodyMatcherType === 'xpath') {
      lines.push(`    .withBody(xpath("${escapeJava(matcher.body.trim())}"))`);
    } else if (matcher.bodyMatcherType === 'regex') {
      lines.push(`    .withBody(regex("${escapeJava(matcher.body.trim())}"))`);
    } else if (matcher.bodyMatcherType === 'parameters') {
      const params = parseKeyValueLines(matcher.body, '=');
      if (params) {
        const paramEntries = Object.entries(params)
          .map(([k, vs]) => `param("${escapeJava(k)}", ${vs.map((v) => `"${escapeJava(v)}"`).join(', ')})`)
          .join(', ');
        lines.push(`    .withBody(params(${paramEntries}))`);
      }
    } else {
      lines.push(`    .withBody("${escapeJava(matcher.body)}")`);
    }
  }
  if (matcher.secure) lines.push('    .withSecure(true)');
  return lines.join('\n');
}

function actionToJava(action: StandardActionPayload): string {
  switch (action.type) {
    case 'static': {
      const s = action.static;
      if (!s) return '.respond(response())';
      const lines = ['.respond('];
      lines.push('    response()');
      lines.push(`        .withStatusCode(${s.statusCode})`);
      if (s.contentType) lines.push(`        .withHeader("Content-Type", "${escapeJava(s.contentType)}")`);
      if (s.body) lines.push(`        .withBody("${escapeJava(s.body)}")`);
      lines.push(')');
      return lines.join('\n');
    }
    case 'forward': {
      const f = action.forward;
      if (!f) return '.forward(forward())';
      return [
        '.forward(',
        '    forward()',
        `        .withScheme(Scheme.${f.scheme})`,
        `        .withHost("${escapeJava(f.host)}")`,
        `        .withPort(${f.port})`,
        ')',
      ].join('\n');
    }
    case 'forward_override': {
      const o = action.forwardOverride;
      if (!o) return '.forward(forwardOverriddenRequest(request()))';
      const overrideLines = ['request()'];
      if (o.overrideMethod) overrideLines.push(`        .withMethod("${escapeJava(o.overrideMethod)}")`);
      if (o.overridePath) overrideLines.push(`        .withPath("${escapeJava(o.overridePath)}")`);
      if (o.overrideHost) overrideLines.push(`        .withHeader("Host", "${escapeJava(o.overrideHost)}")`);
      if (o.overrideScheme) overrideLines.push(`        .withSecure(${o.overrideScheme === 'HTTPS'})`);
      const overrideHeaders = parseKeyValueLines(o.overrideHeaders, ':');
      if (overrideHeaders) {
        for (const [k, vs] of Object.entries(overrideHeaders)) {
          for (const v of vs) overrideLines.push(`        .withHeader("${escapeJava(k)}", "${escapeJava(v)}")`);
        }
      }
      const overrideQuery = parseKeyValueLines(o.overrideQueryString, '=');
      if (overrideQuery) {
        for (const [k, vs] of Object.entries(overrideQuery)) {
          const values = vs.map((v) => `"${escapeJava(v)}"`).join(', ');
          overrideLines.push(`        .withQueryStringParameter("${escapeJava(k)}", ${values})`);
        }
      }
      if (o.overrideBody) overrideLines.push(`        .withBody("${escapeJava(o.overrideBody)}")`);
      return [
        '.forward(',
        '    forwardOverriddenRequest(',
        overrideLines.join('\n            '),
        '    )',
        ')',
      ].join('\n');
    }
    case 'callback': {
      const c = action.callback;
      if (!c) return '.respond(callback())';
      return [
        '.respond(',
        '    callback()',
        `        .withCallbackClass("${escapeJava(c.callbackClass)}")`,
        ')',
      ].join('\n');
    }
    case 'template': {
      const t = action.template;
      if (!t) return '.respond(template(TemplateType.VELOCITY, ""))';
      return [
        '.respond(',
        `    template(TemplateType.${t.templateType}, "${escapeJava(t.template)}")`,
        ')',
      ].join('\n');
    }
    case 'error': {
      const e = action.error;
      if (!e) return '.error(error())';
      const lines = ['.error(', '    error()'];
      if (e.dropConnection) lines.push('        .withDropConnection(true)');
      if (e.responseBytesB64.trim()) lines.push(`        .withResponseBytes(Base64.getDecoder().decode("${escapeJava(e.responseBytesB64.trim())}"))`);
      if (e.delayValue > 0) lines.push(`        .withDelay(new Delay(TimeUnit.${e.delayUnit}, ${e.delayValue}))`);
      lines.push(')');
      return lines.join('\n');
    }
    case 'forward_fallback': {
      const fb = action.forwardFallback;
      if (!fb) return '.forwardWithFallback(forwardWithFallback())';
      const lines = ['.forwardWithFallback(', '    forwardWithFallback()'];
      lines.push(`        .withForward(forward().withScheme(Scheme.${fb.scheme}).withHost("${escapeJava(fb.host)}").withPort(${fb.port}))`);
      const respParts = [`response().withStatusCode(${fb.fallbackStatusCode})`];
      if (fb.fallbackBody.trim()) respParts.push(`.withBody("${escapeJava(fb.fallbackBody)}")`);
      lines.push(`        .withFallback(${respParts.join('')})`);
      const codes = fb.fallbackOnStatusCodes.split(',').map((s) => parseInt(s.trim(), 10)).filter((n) => !isNaN(n));
      if (codes.length > 0) lines.push(`        .withFallbackOnStatusCodes(${codes.join(', ')})`);
      if (fb.fallbackOnTimeout) lines.push('        .withFallbackOnTimeout(true)');
      lines.push(')');
      return lines.join('\n');
    }
    case 'websocket': {
      const ws = action.websocket;
      if (!ws) return '.respondWithWebSocket(webSocketResponse())';
      const lines = ['.respondWithWebSocket(', '    webSocketResponse()'];
      if (ws.subprotocol.trim()) lines.push(`        .withSubprotocol("${escapeJava(ws.subprotocol.trim())}")`);
      const msgLines = ws.messages.split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
      for (const msg of msgLines) lines.push(`        .withMessage(webSocketMessage("${escapeJava(msg)}"))`);
      for (const m of ws.matchers) {
        // Emit the nested webSocketMessageMatcher() builder across indented lines.
        lines.push('        .withMatcher(');
        lines.push(`            webSocketMessageMatcher()`);
        lines.push(`                .withFrameType(WebSocketFrameType.${m.frameType})`);
        if (m.textMatcher.trim()) lines.push(`                .withTextMatcher(string("${escapeJava(m.textMatcher.trim())}"))`);
        const respLines = m.responses.split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
        for (const r of respLines) lines.push(`                .withResponse(webSocketMessage("${escapeJava(r)}"))`);
        lines.push('        )');
      }
      if (ws.closeConnection) lines.push('        .withCloseConnection(true)');
      lines.push(')');
      return lines.join('\n');
    }
    case 'sse': {
      const sse = action.sse;
      if (!sse) return '.respondWithSse(sseResponse())';
      const lines = ['.respondWithSse(', '    sseResponse()'];
      if (sse.statusCode) lines.push(`        .withStatusCode(${sse.statusCode})`);
      for (const ev of sse.events) {
        if (ev.data.trim() || ev.event.trim()) {
          let evChain = 'sseEvent()';
          if (ev.event.trim()) evChain += `.withEvent("${escapeJava(ev.event.trim())}")`;
          if (ev.data.trim()) evChain += `.withData("${escapeJava(ev.data.trim())}")`;
          if (ev.id.trim()) evChain += `.withId("${escapeJava(ev.id.trim())}")`;
          const retryNum = parseInt(ev.retry, 10);
          if (!isNaN(retryNum) && retryNum > 0) evChain += `.withRetry(${retryNum})`;
          lines.push(`        .withEvent(${evChain})`);
        }
      }
      if (sse.closeConnection) lines.push('        .withCloseConnection(true)');
      lines.push(')');
      return lines.join('\n');
    }
    case 'binary_response': {
      const bin = action.binaryResponse;
      if (!bin) return '.respondWithBinary(binaryResponse())';
      const lines = ['.respondWithBinary(', '    binaryResponse()'];
      if (bin.binaryData.trim()) {
        lines.push(`        .withBinaryData(Base64.getDecoder().decode("${escapeJava(bin.binaryData.trim())}"))`);
      }
      lines.push(')');
      return lines.join('\n');
    }
    case 'dns_response': {
      const dns = action.dnsResponse;
      if (!dns) return '.respondWithDns(dnsResponse())';
      const lines = ['.respondWithDns(', '    dnsResponse()'];
      if (dns.responseCode) lines.push(`        .withResponseCode(DnsResponseCode.${dns.responseCode})`);
      for (const rec of parseDnsRecords(dns.answerRecords)) {
        // Emit the nested dnsRecord() builder across indented lines rather than one long
        // call, so the snippet stays readable.
        lines.push('        .withAnswerRecord(');
        lines.push('            dnsRecord()');
        if (rec.name) lines.push(`                .withName("${escapeJava(rec.name)}")`);
        if (rec.type) lines.push(`                .withType(DnsRecordType.${rec.type})`);
        if (rec.dnsClass) lines.push(`                .withDnsClass(DnsRecordClass.${rec.dnsClass})`);
        if (rec.ttl != null) lines.push(`                .withTtl(${rec.ttl})`);
        if (rec.value) lines.push(`                .withValue("${escapeJava(rec.value)}")`);
        lines.push('        )');
      }
      lines.push(')');
      return lines.join('\n');
    }
    case 'forward_template': {
      const ft = action.forwardTemplate;
      if (!ft) return '.forward(template(TemplateType.VELOCITY, ""))';
      return [
        '.forward(',
        `    template(TemplateType.${ft.templateType}, "${escapeJava(ft.template)}")`,
        ')',
      ].join('\n');
    }
    case 'forward_class_callback': {
      const fc = action.forwardClassCallback;
      if (!fc) return '.forward(callback())';
      return [
        '.forward(',
        '    callback()',
        `        .withCallbackClass("${escapeJava(fc.callbackClass)}")`,
        ')',
      ].join('\n');
    }
    case 'grpc_stream': {
      const grpc = action.grpcStream;
      if (!grpc) return '.respondWithGrpcStream(grpcStreamResponse())';
      const lines = ['.respondWithGrpcStream(', '    grpcStreamResponse()'];
      if (grpc.statusName.trim()) lines.push(`        .withStatusName("${escapeJava(grpc.statusName.trim())}")`);
      if (grpc.statusMessage.trim()) lines.push(`        .withStatusMessage("${escapeJava(grpc.statusMessage.trim())}")`);
      const grpcHeaders = parseKeyValueLines(grpc.headers, ':');
      if (grpcHeaders) {
        for (const [k, vs] of Object.entries(grpcHeaders)) {
          const values = vs.map((v) => `"${escapeJava(v)}"`).join(', ');
          lines.push(`        .withHeader("${escapeJava(k)}", ${values})`);
        }
      }
      const grpcMsgLines = grpc.messages.split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
      for (const msg of grpcMsgLines) lines.push(`        .withMessage("${escapeJava(msg)}")`);
      if (grpc.closeConnection) lines.push('        .withCloseConnection(true)');
      lines.push(')');
      return lines.join('\n');
    }
  }
}

function chaosToJava(chaos: StandardChaosDraft): string {
  const lines: string[] = ['.withChaos(', '    httpChaosProfile()'];
  if (chaos.errorStatus != null) lines.push(`        .withErrorStatus(${chaos.errorStatus})`);
  if (chaos.errorProbability != null) {
    const prob = chaos.errorProbability % 1 === 0 ? chaos.errorProbability.toFixed(1) : String(chaos.errorProbability);
    lines.push(`        .withErrorProbability(${prob})`);
  }
  if (chaos.retryAfter) lines.push(`        .withRetryAfter("${escapeJava(chaos.retryAfter)}")`);
  if (chaos.latencyValue != null && chaos.latencyValue > 0) {
    const unit = chaos.latencyUnit ?? 'MILLISECONDS';
    lines.push(`        .withLatency(new Delay(TimeUnit.${unit}, ${chaos.latencyValue}))`);
  }
  if (chaos.seed != null) lines.push(`        .withSeed(${chaos.seed}L)`);
  if (chaos.succeedFirst != null) lines.push(`        .withSucceedFirst(${chaos.succeedFirst})`);
  if (chaos.failRequestCount != null) lines.push(`        .withFailRequestCount(${chaos.failRequestCount})`);
  lines.push(')');
  return lines.join('\n');
}

/**
 * Compute the exact set of imports the generated Java snippet needs, based on the matcher
 * and action actually emitted. Returned sorted (static imports first) and de-duplicated so
 * the snippet compiles as a standalone paste.
 */
function collectJavaImports(
  matcher: StandardMatcher,
  action: StandardActionPayload,
  hasChaos: boolean,
): string[] {
  const imp = new Set<string>();
  const isDns = !!(matcher.dns && matcher.dns.dnsName.trim());

  // Request matcher
  if (isDns) {
    imp.add('import static org.mockserver.model.DnsRequestDefinition.dnsRequest;');
    if (matcher.dns!.dnsType) imp.add('import org.mockserver.model.DnsRecordType;');
    if (matcher.dns!.dnsClass) imp.add('import org.mockserver.model.DnsRecordClass;');
  } else {
    imp.add('import static org.mockserver.model.HttpRequest.request;');
    if (matcher.body.trim()) {
      if (matcher.bodyMatcherType === 'binary' || matcher.bodyBinary) {
        imp.add('import static org.mockserver.model.BinaryBody.binary;');
        imp.add('import java.util.Base64;');
      } else if (matcher.bodyMatcherType === 'graphql') {
        imp.add('import static org.mockserver.model.GraphQLBody.graphQL;');
        if (matcher.graphqlOptions && matcher.graphqlOptions.selectionSetMatchType !== 'NORMALISED_STRING') {
          imp.add('import org.mockserver.model.SelectionSetMatchType;');
        }
      } else if (matcher.bodyMatcherType === 'json-schema') {
        imp.add('import static org.mockserver.model.JsonSchemaBody.jsonSchema;');
      } else if (matcher.bodyMatcherType === 'json-path') {
        imp.add('import static org.mockserver.model.JsonPathBody.jsonPath;');
      } else if (matcher.bodyMatcherType === 'xml') {
        imp.add('import static org.mockserver.model.XmlBody.xml;');
      } else if (matcher.bodyMatcherType === 'xml-schema') {
        imp.add('import static org.mockserver.model.XmlSchemaBody.xmlSchema;');
      } else if (matcher.bodyMatcherType === 'xpath') {
        imp.add('import static org.mockserver.model.XPathBody.xpath;');
      } else if (matcher.bodyMatcherType === 'regex') {
        imp.add('import static org.mockserver.model.RegexBody.regex;');
      } else if (matcher.bodyMatcherType === 'parameters') {
        imp.add('import static org.mockserver.model.ParameterBody.params;');
        imp.add('import static org.mockserver.model.Parameter.param;');
      }
    }
  }

  // Action
  switch (action.type) {
    case 'static':
      imp.add('import static org.mockserver.model.HttpResponse.response;');
      break;
    case 'forward':
      imp.add('import static org.mockserver.model.HttpForward.forward;');
      imp.add('import org.mockserver.model.HttpForward.Scheme;');
      break;
    case 'forward_override':
      imp.add('import static org.mockserver.model.HttpOverrideForwardedRequest.forwardOverriddenRequest;');
      imp.add('import static org.mockserver.model.HttpRequest.request;');
      break;
    case 'forward_fallback':
      imp.add('import static org.mockserver.model.HttpForwardWithFallback.forwardWithFallback;');
      imp.add('import static org.mockserver.model.HttpForward.forward;');
      imp.add('import org.mockserver.model.HttpForward.Scheme;');
      imp.add('import static org.mockserver.model.HttpResponse.response;');
      break;
    case 'callback':
    case 'forward_class_callback':
      imp.add('import static org.mockserver.model.HttpClassCallback.callback;');
      break;
    case 'template':
    case 'forward_template':
      imp.add('import static org.mockserver.model.HttpTemplate.template;');
      imp.add('import org.mockserver.model.HttpTemplate.TemplateType;');
      break;
    case 'error':
      imp.add('import static org.mockserver.model.HttpError.error;');
      if (action.error?.responseBytesB64.trim()) imp.add('import java.util.Base64;');
      if (action.error?.delayValue) {
        imp.add('import org.mockserver.model.Delay;');
        imp.add('import java.util.concurrent.TimeUnit;');
      }
      break;
    case 'websocket':
      imp.add('import static org.mockserver.model.HttpWebSocketResponse.webSocketResponse;');
      imp.add('import static org.mockserver.model.WebSocketMessage.webSocketMessage;');
      if (action.websocket && action.websocket.matchers.length > 0) {
        imp.add('import static org.mockserver.model.WebSocketMessageMatcher.webSocketMessageMatcher;');
        imp.add('import org.mockserver.model.WebSocketFrameType;');
        if (action.websocket.matchers.some((m) => m.textMatcher.trim())) {
          imp.add('import static org.mockserver.model.NottableString.string;');
        }
      }
      break;
    case 'sse':
      imp.add('import static org.mockserver.model.HttpSseResponse.sseResponse;');
      imp.add('import static org.mockserver.model.SseEvent.sseEvent;');
      break;
    case 'binary_response':
      imp.add('import static org.mockserver.model.BinaryResponse.binaryResponse;');
      imp.add('import java.util.Base64;');
      break;
    case 'dns_response':
      imp.add('import static org.mockserver.model.DnsResponse.dnsResponse;');
      imp.add('import org.mockserver.model.DnsResponseCode;');
      if (action.dnsResponse) {
        const recs = parseDnsRecords(action.dnsResponse.answerRecords);
        if (recs.length > 0) {
          imp.add('import static org.mockserver.model.DnsRecord.dnsRecord;');
          if (recs.some((r) => r.type)) imp.add('import org.mockserver.model.DnsRecordType;');
          if (recs.some((r) => r.dnsClass)) imp.add('import org.mockserver.model.DnsRecordClass;');
        }
      }
      break;
    case 'grpc_stream':
      imp.add('import static org.mockserver.model.GrpcStreamResponse.grpcStreamResponse;');
      break;
  }

  // Chaos
  if (hasChaos) {
    imp.add('import static org.mockserver.model.HttpChaosProfile.httpChaosProfile;');
    if (action.chaos?.latencyValue != null && action.chaos.latencyValue > 0) {
      imp.add('import org.mockserver.model.Delay;');
      imp.add('import java.util.concurrent.TimeUnit;');
    }
  }

  // Side-effect (before / after) actions — webhook (httpRequest) target only this increment
  const sideEffects = (action.sideEffects ?? []).filter((se) => se.path.trim());
  const beforeActions = sideEffects.filter((se) => se.position === 'before');
  const afterActions = sideEffects.filter((se) => se.position === 'after');
  if (sideEffects.length > 0) imp.add('import static org.mockserver.model.HttpRequest.request;');
  if (beforeActions.length > 0) imp.add('import static org.mockserver.model.AfterAction.beforeAction;');
  if (afterActions.length > 0) imp.add('import static org.mockserver.model.AfterAction.afterAction;');
  if (beforeActions.some((se) => se.failurePolicy === 'FAIL_FAST')) {
    imp.add('import org.mockserver.model.FailurePolicy;');
  }
  if (sideEffects.some((se) => se.delayValue > 0) || beforeActions.some((se) => se.timeoutValue > 0)) {
    imp.add('import org.mockserver.model.Delay;');
    imp.add('import java.util.concurrent.TimeUnit;');
  }

  const all = Array.from(imp);
  const statics = all.filter((i) => i.startsWith('import static ')).sort();
  const plains = all.filter((i) => !i.startsWith('import static ')).sort();
  return [...statics, ...plains];
}

function sideEffectToJava(se: StandardSideEffectAction): string {
  const isBefore = se.position === 'before';
  const factoryMethod = isBefore ? 'beforeAction' : 'afterAction';
  const lines: string[] = [];
  lines.push(`${factoryMethod}()`);
  lines.push(`        .withHttpRequest(request()`);
  if (se.method.trim()) lines.push(`            .withMethod("${escapeJava(se.method.trim())}")`);
  lines.push(`            .withPath("${escapeJava(se.path)}")`);
  if (se.host.trim()) lines.push(`            .withHeader("Host", "${escapeJava(se.host.trim())}")`);
  if (se.body.trim()) lines.push(`            .withBody("${escapeJava(se.body.trim())}")`);
  lines.push('        )');
  if (se.delayValue > 0) {
    lines.push(`        .withDelay(new Delay(TimeUnit.${se.delayUnit}, ${se.delayValue}))`);
  }
  if (isBefore) {
    if (!se.blocking) lines.push('        .withBlocking(false)');
    if (se.timeoutValue > 0) {
      lines.push(`        .withTimeout(new Delay(TimeUnit.${se.timeoutUnit}, ${se.timeoutValue}))`);
    }
    if (se.failurePolicy === 'FAIL_FAST') lines.push('        .withFailurePolicy(FailurePolicy.FAIL_FAST)');
  }
  return lines.join('\n');
}

export function standardToJava(matcher: StandardMatcher, action: StandardActionPayload): string {
  const hasChaos = !!(action.chaos && buildChaosJson(action.chaos));
  const sideEffects = (action.sideEffects ?? []).filter((se) => se.path.trim());
  const beforeActions = sideEffects.filter((se) => se.position === 'before');
  const afterActions = sideEffects.filter((se) => se.position === 'after');
  const lines: string[] = [];
  for (const imp of collectJavaImports(matcher, action, hasChaos)) lines.push(imp);
  lines.push('');
  lines.push('mockServerClient');
  lines.push('  .when(');
  lines.push('    ' + matcherToJava(matcher).split('\n').join('\n    '));
  lines.push('  )');
  // Chaos is fluent on the ForwardChainExpectation returned by when(...), so it must come
  // BEFORE the terminal action (respond/forward/error), which returns Expectation[].
  if (hasChaos) {
    lines.push('  ' + chaosToJava(action.chaos!).split('\n').join('\n  '));
  }
  // before/after actions are also fluent on ForwardChainExpectation -> before the terminal action
  for (const se of beforeActions) {
    lines.push('  .withBeforeAction(');
    lines.push('    ' + sideEffectToJava(se).split('\n').join('\n    '));
    lines.push('  )');
  }
  for (const se of afterActions) {
    lines.push('  .withAfterAction(');
    lines.push('    ' + sideEffectToJava(se).split('\n').join('\n    '));
    lines.push('  )');
  }
  lines.push('  ' + actionToJava(action).split('\n').join('\n  '));
  lines.push(';');
  return lines.join('\n');
}

// ---------------------------------------------------------------------------
// curl codegen — equivalent shell command for registering the expectation
// ---------------------------------------------------------------------------

export function standardToCurl(
  matcher: StandardMatcher,
  action: StandardActionPayload,
  baseUrl: string,
): string {
  const json = JSON.stringify(buildExpectationJson(matcher, action));
  // Single-quote the JSON payload; escape any embedded single quotes for shell.
  const safe = json.replace(/'/g, `'\\''`);
  return `curl -X PUT '${baseUrl}/mockserver/expectation' \\\n  -H 'Content-Type: application/json' \\\n  -d '${safe}'`;
}
