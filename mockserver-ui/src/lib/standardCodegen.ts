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
  | 'callback'
  | 'template'
  | 'error';

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
  secure: boolean;
  priority: number;
  times: number;
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

export type ChaosDelayUnit = 'MILLISECONDS' | 'SECONDS' | 'MINUTES';

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
  callback?: StandardCallbackState;
  template?: StandardTemplateState;
  error?: StandardErrorState;
  chaos?: StandardChaosDraft;
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

// ---------------------------------------------------------------------------
// JSON codegen — produces the exact payload PUT /mockserver/expectation
// ---------------------------------------------------------------------------

export function buildExpectationJson(
  matcher: StandardMatcher,
  action: StandardActionPayload,
): Record<string, unknown> {
  const httpRequest: Record<string, unknown> = { path: matcher.path };
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
    httpRequest['body'] = matcher.bodyBinary
      ? { type: 'BINARY', base64Bytes: matcher.body.trim() }
      : matcher.body;
  }
  if (matcher.secure) httpRequest['secure'] = true;

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
  }

  // Chaos profile — top-level sibling of httpRequest / httpResponse.
  if (action.chaos) {
    const chaos = buildChaosJson(action.chaos);
    if (chaos) out['chaos'] = chaos;
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

export function standardToJson(matcher: StandardMatcher, action: StandardActionPayload): string {
  return JSON.stringify(buildExpectationJson(matcher, action), null, 2);
}

// ---------------------------------------------------------------------------
// Java client codegen — emits a snippet usable with MockServerClient
// ---------------------------------------------------------------------------

function matcherToJava(matcher: StandardMatcher): string {
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
    if (matcher.bodyBinary) {
      lines.push(`    .withBody(binary(Base64.getDecoder().decode("${escapeJava(matcher.body.trim())}")))`);
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

export function standardToJava(matcher: StandardMatcher, action: StandardActionPayload): string {
  const hasChaos = action.chaos && buildChaosJson(action.chaos);
  const lines: string[] = [];
  lines.push('import static org.mockserver.model.HttpRequest.request;');
  if (action.type === 'static' || action.type === 'callback' || action.type === 'template') {
    lines.push('import static org.mockserver.model.HttpResponse.response;');
  }
  if (action.type === 'forward' || action.type === 'forward_override') {
    lines.push('import static org.mockserver.model.HttpForward.forward;');
  }
  if (action.type === 'forward_override') {
    lines.push('import static org.mockserver.model.HttpOverrideForwardedRequest.forwardOverriddenRequest;');
  }
  if (action.type === 'error') {
    lines.push('import static org.mockserver.model.HttpError.error;');
  }
  const chaosHasLatency = hasChaos && action.chaos?.latencyValue != null;
  if ((action.type === 'error' && action.error?.delayValue) || chaosHasLatency) {
    lines.push('import org.mockserver.model.Delay;');
    lines.push('import java.util.concurrent.TimeUnit;');
  }
  if (hasChaos) {
    lines.push('import static org.mockserver.model.HttpChaosProfile.httpChaosProfile;');
  }
  if ((action.type === 'error' && action.error?.responseBytesB64.trim()) || matcher.bodyBinary) {
    lines.push('import java.util.Base64;');
  }
  lines.push('');
  lines.push('mockServerClient');
  lines.push('  .when(');
  lines.push('    ' + matcherToJava(matcher).split('\n').join('\n    '));
  lines.push('  )');
  lines.push('  ' + actionToJava(action).split('\n').join('\n  '));
  if (hasChaos) {
    lines.push('  ' + chaosToJava(action.chaos!).split('\n').join('\n  '));
  }
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
