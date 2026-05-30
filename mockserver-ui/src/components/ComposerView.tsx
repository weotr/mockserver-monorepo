import { useState, useCallback, useMemo } from 'react';
import Box from '@mui/material/Box';
import Paper from '@mui/material/Paper';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import RadioGroup from '@mui/material/RadioGroup';
import FormControlLabel from '@mui/material/FormControlLabel';
import Radio from '@mui/material/Radio';
import Button from '@mui/material/Button';
import Alert from '@mui/material/Alert';
import MenuItem from '@mui/material/MenuItem';
import Divider from '@mui/material/Divider';
import Snackbar from '@mui/material/Snackbar';
import Switch from '@mui/material/Switch';
import Collapse from '@mui/material/Collapse';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import { useDashboardStore } from '../store';
import type { JsonListItem } from '../types';
import { listConversationScenarios } from '../lib/conversationCodegen';
import LlmConversationForm from './LlmConversationForm';
import StandardReview from './StandardReview';
import {
  buildExpectationJson,
  chaosFromExpectation,
  type StandardActionPayload,
  type StandardChaosDraft,
  type ChaosDelayUnit,
} from '../lib/standardCodegen';

// ---------------------------------------------------------------------------
// Response action types
// ---------------------------------------------------------------------------

type ExpectationKind = 'standard' | 'llm_conversation';

type ActionType =
  | 'static'
  | 'forward'
  | 'forward_override'
  | 'callback'
  | 'template'
  | 'error';

interface ActionTypeMeta {
  value: ActionType;
  label: string;
  description: string;
}

const ACTION_TYPES: ActionTypeMeta[] = [
  { value: 'static', label: 'Static HTTP response', description: 'Return a fixed status / headers / body for matching requests.' },
  { value: 'forward', label: 'Forward to upstream', description: 'Proxy the request to a configured scheme://host:port.' },
  { value: 'forward_override', label: 'Forward with override', description: 'Forward upstream while rewriting host / scheme / path on the outgoing request.' },
  { value: 'callback', label: 'Class callback', description: 'Invoke a server-side class FQCN to build the response dynamically.' },
  { value: 'template', label: 'Response template', description: 'Velocity / JavaScript / Mustache templates for dynamic responses.' },
  { value: 'error', label: 'Error / fault injection', description: 'Drop the connection mid-request or send arbitrary bytes as the response.' },
];

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function baseUrl(p: ConnectionParams): string {
  const protocol = p.secure ? 'https' : 'http';
  return `${protocol}://${p.host}:${p.port}`;
}

/**
 * PUT the current matcher + action to MockServer. The payload is built by
 * `buildExpectationJson` (shared with `StandardReview`'s code preview) so the
 * Java/JSON/curl tabs always reflect the exact bytes that go over the wire.
 */
async function registerExpectation(
  connectionParams: ConnectionParams,
  matcher: MatcherState,
  action: StandardActionPayload,
): Promise<void> {
  const url = `${baseUrl(connectionParams)}/mockserver/expectation`;
  const body = buildExpectationJson(matcher, action);
  const res = await fetch(url, {
    method: 'PUT',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`MockServer returned ${res.status}: ${text || res.statusText}`);
  }
}

// ---------------------------------------------------------------------------
// Request matcher panel — shared across all action types
// ---------------------------------------------------------------------------

interface MatcherState {
  id: string;
  method: string;
  path: string;
  headers: string;       // "Name: value" lines, "!" prefix negates
  queryString: string;   // "key=value" lines, "!" prefix negates
  cookies: string;       // "name=value" lines, "!" prefix negates
  pathParams: string;    // "name=value" lines, "!" prefix negates (for /users/{id} style)
  body: string;
  bodyBinary: boolean;   // when true, body is base64-encoded raw bytes
  secure: boolean;
  priority: number;
  times: number;         // 0 = unlimited
}

function emptyMatcher(): MatcherState {
  return {
    id: '',
    method: 'GET',
    path: '',
    headers: '',
    queryString: '',
    cookies: '',
    pathParams: '',
    body: '',
    bodyBinary: false,
    secure: false,
    priority: 0,
    times: 0,
  };
}

/**
 * MockServer's NottableString accepts a leading `!` to mean "must not match
 * this value". When MockServer SERIALIZES an active expectation it sends the
 * object form `{ value: "...", not: true }`. This helper unifies both shapes
 * back into the `!`-prefix string convention used by the matcher textareas.
 */
function denottable(field: unknown): string {
  if (typeof field === 'string') return field;
  if (field && typeof field === 'object' && 'value' in (field as Record<string, unknown>)) {
    const f = field as Record<string, unknown>;
    const prefix = f['not'] === true ? '!' : '';
    return prefix + String(f['value'] ?? '');
  }
  return '';
}

function MatcherPanel({ matcher, setMatcher }: { matcher: MatcherState; setMatcher: (m: MatcherState) => void }) {
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
      <Box sx={{ display: 'flex', gap: 1 }}>
        <TextField
          label="Expectation ID (optional)"
          size="small"
          sx={{ flex: 1 }}
          value={matcher.id}
          onChange={(e) => setMatcher({ ...matcher, id: e.target.value })}
          placeholder="leave blank to auto-generate; reuse an ID to update an existing expectation"
        />
      </Box>
      <Box sx={{ display: 'flex', gap: 1, alignItems: 'center', flexWrap: 'wrap' }}>
        <TextField
          label="Method"
          size="small"
          select
          value={matcher.method}
          onChange={(e) => setMatcher({ ...matcher, method: e.target.value })}
          sx={{ minWidth: 110 }}
        >
          {['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS', 'ANY'].map((m) => (
            <MenuItem key={m} value={m === 'ANY' ? '' : m}>{m}</MenuItem>
          ))}
        </TextField>
        <TextField
          label="Path"
          size="small"
          fullWidth
          sx={{ flex: 1, minWidth: 200 }}
          value={matcher.path}
          onChange={(e) => setMatcher({ ...matcher, path: e.target.value })}
          placeholder="/foo/bar  ·  prefix with ! to negate"
        />
      </Box>
      <Typography variant="caption" color="text.secondary" sx={{ fontSize: '0.7rem', mt: -1 }}>
        Prefix any string field or any line below with <code>!</code> to negate the match.
      </Typography>
      <Box sx={{ display: 'flex', gap: 1 }}>
        <TextField
          label="Headers (Name: value per line)"
          multiline
          minRows={2}
          maxRows={6}
          sx={{ flex: 1 }}
          value={matcher.headers}
          onChange={(e) => setMatcher({ ...matcher, headers: e.target.value })}
          placeholder={'Accept: application/json\n!Authorization: Bearer …'}
          slotProps={{ input: { sx: { fontFamily: 'monospace', fontSize: '0.78rem' } } }}
        />
        <TextField
          label="Query params (key=value per line)"
          multiline
          minRows={2}
          maxRows={6}
          sx={{ flex: 1 }}
          value={matcher.queryString}
          onChange={(e) => setMatcher({ ...matcher, queryString: e.target.value })}
          placeholder={'limit=50\noffset=0'}
          slotProps={{ input: { sx: { fontFamily: 'monospace', fontSize: '0.78rem' } } }}
        />
      </Box>
      <Box sx={{ display: 'flex', gap: 1 }}>
        <TextField
          label="Cookies (name=value per line)"
          multiline
          minRows={2}
          maxRows={4}
          sx={{ flex: 1 }}
          value={matcher.cookies}
          onChange={(e) => setMatcher({ ...matcher, cookies: e.target.value })}
          placeholder={'session=abc123\ntheme=dark'}
          slotProps={{ input: { sx: { fontFamily: 'monospace', fontSize: '0.78rem' } } }}
        />
        <TextField
          label="Path parameters (name=value per line)"
          multiline
          minRows={2}
          maxRows={4}
          sx={{ flex: 1 }}
          value={matcher.pathParams}
          onChange={(e) => setMatcher({ ...matcher, pathParams: e.target.value })}
          placeholder={'id=42  (for paths like /users/{id})'}
          slotProps={{ input: { sx: { fontFamily: 'monospace', fontSize: '0.78rem' } } }}
        />
      </Box>
      <Box>
        <TextField
          label={matcher.bodyBinary ? 'Body matcher (base64 bytes)' : 'Body matcher (string or JSON)'}
          fullWidth
          multiline
          minRows={2}
          maxRows={10}
          value={matcher.body}
          onChange={(e) => setMatcher({ ...matcher, body: e.target.value })}
          placeholder={matcher.bodyBinary ? 'SGVsbG8sIFdvcmxkIQ==' : 'e.g. {"foo":"bar"}'}
          slotProps={{ input: { sx: { fontFamily: 'monospace', fontSize: '0.78rem' } } }}
        />
        <FormControlLabel
          control={
            <Switch
              size="small"
              checked={matcher.bodyBinary}
              onChange={(e) => setMatcher({ ...matcher, bodyBinary: e.target.checked })}
            />
          }
          label={<Typography variant="body2" sx={{ fontSize: '0.8rem' }}>Body is binary (base64)</Typography>}
          sx={{ mt: 0.5 }}
        />
      </Box>
      <Box sx={{ display: 'flex', gap: 1, alignItems: 'center', flexWrap: 'wrap' }}>
        <FormControlLabel
          control={
            <Switch
              size="small"
              checked={matcher.secure}
              onChange={(e) => setMatcher({ ...matcher, secure: e.target.checked })}
            />
          }
          label={<Typography variant="body2" sx={{ fontSize: '0.82rem' }}>HTTPS only</Typography>}
        />
        <TextField
          label="Priority (higher = wins)"
          size="small"
          type="number"
          sx={{ width: 200 }}
          value={matcher.priority}
          onChange={(e) => setMatcher({ ...matcher, priority: Number(e.target.value) || 0 })}
        />
        <TextField
          label="Times (0 = unlimited)"
          size="small"
          type="number"
          sx={{ width: 170 }}
          value={matcher.times}
          onChange={(e) => setMatcher({ ...matcher, times: Math.max(0, Number(e.target.value) || 0) })}
        />
      </Box>
    </Box>
  );
}

/**
 * Best-effort population of MatcherState from an existing active-expectation
 * payload. Round-trip is lossy for advanced matchers (NottableString.not,
 * BodyDTO variants, pathParameters, cookies) — those revert to text. The user
 * can hand-edit the resulting text if needed before re-registering.
 */
/**
 * Convert a Headers / Parameters / Cookies record back to "key separator
 * value" lines. Each value is run through `denottable` so NottableString
 * `{value, not}` objects round-trip as `!value` strings the matcher
 * textareas understand.
 */
function mapToLines(map: unknown, separator: ':' | '='): string {
  if (!map || typeof map !== 'object') return '';
  const lines: string[] = [];
  for (const [k, val] of Object.entries(map as Record<string, unknown>)) {
    if (Array.isArray(val)) {
      for (const vv of val as unknown[]) lines.push(`${k}${separator} ${denottable(vv)}`.replace(/^(.+): $/, '$1: '));
    } else if (typeof val === 'string' || (val && typeof val === 'object')) {
      lines.push(`${k}${separator} ${denottable(val)}`);
    }
  }
  return lines.join('\n');
}

function matcherFromExpectation(item: JsonListItem): MatcherState {
  const v = item.value;
  const req = (v['httpRequest'] as Record<string, unknown> | undefined) ?? {};

  // Body shape detection: { type: "BINARY", base64Bytes } → keep as base64
  // and flip the binary toggle. Other variants unwrap to a text string.
  const rawBody = req['body'];
  let bodyText = '';
  let bodyBinary = false;
  if (typeof rawBody === 'string') {
    bodyText = rawBody;
  } else if (rawBody && typeof rawBody === 'object') {
    const b = rawBody as Record<string, unknown>;
    if (b['type'] === 'BINARY' && typeof b['base64Bytes'] === 'string') {
      bodyText = b['base64Bytes'] as string;
      bodyBinary = true;
    } else if (typeof b['string'] === 'string') {
      bodyText = b['string'];
    } else if (b['json'] != null) {
      bodyText = typeof b['json'] === 'string' ? b['json'] : JSON.stringify(b['json'], null, 2);
    } else {
      bodyText = JSON.stringify(b, null, 2);
    }
  }

  return {
    id: typeof v['id'] === 'string' ? (v['id'] as string) : '',
    method: denottable(req['method']),
    path: denottable(req['path']),
    headers: mapToLines(req['headers'], ':'),
    queryString: mapToLines(req['queryStringParameters'], '='),
    cookies: mapToLines(req['cookies'], '='),
    pathParams: mapToLines(req['pathParameters'], '='),
    body: bodyText,
    bodyBinary,
    secure: req['secure'] === true,
    priority: typeof v['priority'] === 'number' ? (v['priority'] as number) : 0,
    times:
      typeof v['times'] === 'object' && v['times'] !== null && typeof (v['times'] as Record<string, unknown>)['remainingTimes'] === 'number'
        ? ((v['times'] as Record<string, unknown>)['remainingTimes'] as number)
        : 0,
  };
}

// ---------------------------------------------------------------------------
// Per-action prefill — detect which response action an expectation uses and
// return the matching ActionType + per-state overrides.
// ---------------------------------------------------------------------------

interface ActionPrefill {
  type: ActionType;
  staticState?: StaticState;
  forwardState?: ForwardState;
  forwardOverrideState?: ForwardOverrideState;
  callbackState?: CallbackState;
  templateState?: TemplateState;
  errorState?: ErrorState;
}

function unwrapBody(body: unknown): string {
  if (body == null) return '';
  if (typeof body === 'string') return body;
  if (typeof body !== 'object') return String(body);
  const b = body as Record<string, unknown>;
  if (typeof b['string'] === 'string') return b['string'];
  if (b['json'] != null) {
    return typeof b['json'] === 'string' ? b['json'] : JSON.stringify(b['json'], null, 2);
  }
  if (typeof b['base64Bytes'] === 'string') return b['base64Bytes'] as string;
  return JSON.stringify(b, null, 2);
}

function headersToText(headers: unknown, exclude?: string): string {
  if (!headers || typeof headers !== 'object') return '';
  const lines: string[] = [];
  for (const [k, v] of Object.entries(headers as Record<string, unknown>)) {
    if (exclude && k.toLowerCase() === exclude.toLowerCase()) continue;
    if (Array.isArray(v)) {
      for (const vv of v as unknown[]) lines.push(`${k}: ${String(vv)}`);
    } else {
      lines.push(`${k}: ${String(v)}`);
    }
  }
  return lines.join('\n');
}

function paramsToText(params: unknown): string {
  if (!params || typeof params !== 'object') return '';
  const lines: string[] = [];
  for (const [k, v] of Object.entries(params as Record<string, unknown>)) {
    if (Array.isArray(v)) {
      for (const vv of v as unknown[]) lines.push(`${k}=${String(vv)}`);
    } else {
      lines.push(`${k}=${String(v)}`);
    }
  }
  return lines.join('\n');
}

function actionFromExpectation(item: JsonListItem): ActionPrefill | null {
  const v = item.value;

  // Static HTTP response
  if (v['httpResponse'] && typeof v['httpResponse'] === 'object') {
    const r = v['httpResponse'] as Record<string, unknown>;
    const headers = r['headers'] as Record<string, unknown> | undefined;
    const contentType = headers?.['content-type'] ?? headers?.['Content-Type'];
    return {
      type: 'static',
      staticState: {
        statusCode: typeof r['statusCode'] === 'number' ? (r['statusCode'] as number) : 200,
        body: unwrapBody(r['body']),
        contentType: Array.isArray(contentType) ? String((contentType as unknown[])[0] ?? 'application/json')
          : typeof contentType === 'string' ? contentType : 'application/json',
      },
    };
  }

  // Forward to upstream
  if (v['httpForward'] && typeof v['httpForward'] === 'object') {
    const f = v['httpForward'] as Record<string, unknown>;
    return {
      type: 'forward',
      forwardState: {
        scheme: f['scheme'] === 'HTTP' ? 'HTTP' : 'HTTPS',
        host: typeof f['host'] === 'string' ? (f['host'] as string) : '',
        port: typeof f['port'] === 'number' ? (f['port'] as number) : 443,
      },
    };
  }

  // Forward with override
  if (v['httpOverrideForwardedRequest'] && typeof v['httpOverrideForwardedRequest'] === 'object') {
    const o = v['httpOverrideForwardedRequest'] as Record<string, unknown>;
    const override = (o['requestOverride'] as Record<string, unknown> | undefined) ?? {};
    const overrideHeaders = override['headers'] as Record<string, unknown> | undefined;
    const hostHeader = overrideHeaders?.['Host'] ?? overrideHeaders?.['host'];
    const hostText = Array.isArray(hostHeader) ? String((hostHeader as unknown[])[0] ?? '')
      : typeof hostHeader === 'string' ? hostHeader : '';
    return {
      type: 'forward_override',
      forwardOverrideState: {
        overrideMethod: typeof override['method'] === 'string' ? (override['method'] as string) : '',
        overrideHost: hostText,
        overrideScheme: override['secure'] === true ? 'HTTPS' : override['secure'] === false ? 'HTTP' : '',
        overridePath: typeof override['path'] === 'string' ? (override['path'] as string) : '',
        overrideQueryString: paramsToText(override['queryStringParameters']),
        overrideHeaders: headersToText(override['headers'], 'host'),
        overrideBody: unwrapBody(override['body']),
      },
    };
  }

  // Class callback
  if (v['httpResponseClassCallback'] && typeof v['httpResponseClassCallback'] === 'object') {
    const c = v['httpResponseClassCallback'] as Record<string, unknown>;
    return {
      type: 'callback',
      callbackState: {
        callbackClass: typeof c['callbackClass'] === 'string' ? (c['callbackClass'] as string) : '',
      },
    };
  }

  // Response template
  if (v['httpResponseTemplate'] && typeof v['httpResponseTemplate'] === 'object') {
    const t = v['httpResponseTemplate'] as Record<string, unknown>;
    const tt = t['templateType'];
    return {
      type: 'template',
      templateState: {
        templateType: tt === 'JAVASCRIPT' || tt === 'MUSTACHE' ? tt : 'VELOCITY',
        template: typeof t['template'] === 'string' ? (t['template'] as string) : '',
      },
    };
  }

  // Error
  if (v['httpError'] && typeof v['httpError'] === 'object') {
    const e = v['httpError'] as Record<string, unknown>;
    const delay = e['delay'] as Record<string, unknown> | undefined;
    const responseBytes = e['responseBytes'];
    return {
      type: 'error',
      errorState: {
        dropConnection: e['dropConnection'] === true,
        responseBytesB64: typeof responseBytes === 'string' ? responseBytes : '',
        delayValue: typeof delay?.['value'] === 'number' ? (delay['value'] as number) : 0,
        delayUnit:
          delay?.['timeUnit'] === 'SECONDS' ? 'SECONDS'
          : delay?.['timeUnit'] === 'MINUTES' ? 'MINUTES'
          : 'MILLISECONDS',
      },
    };
  }

  // LLM Conversation expectations are filtered out of the standard picker
  // entirely — they have their own top-level kind + wizard path.

  return null;
}

// ---------------------------------------------------------------------------
// Static HTTP action panel
// ---------------------------------------------------------------------------

interface StaticState {
  statusCode: number;
  body: string;
  contentType: string;
}

function StaticHttpPanel({
  state,
  setState,
}: {
  state: StaticState;
  setState: (s: StaticState) => void;
}) {
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
      <Box sx={{ display: 'flex', gap: 1 }}>
        <TextField
          label="Status code"
          size="small"
          type="number"
          value={state.statusCode}
          onChange={(e) => setState({ ...state, statusCode: Number(e.target.value) || 200 })}
          sx={{ width: 130 }}
        />
        <TextField
          label="Content-Type"
          size="small"
          value={state.contentType}
          onChange={(e) => setState({ ...state, contentType: e.target.value })}
          sx={{ flex: 1 }}
        />
      </Box>
      <TextField
        label="Response body"
        multiline
        minRows={6}
        maxRows={20}
        value={state.body}
        onChange={(e) => setState({ ...state, body: e.target.value })}
        placeholder='{"ok":true}'
        slotProps={{ input: { sx: { fontFamily: 'monospace', fontSize: '0.78rem' } } }}
      />
    </Box>
  );
}

// ---------------------------------------------------------------------------
// Forward to upstream
// ---------------------------------------------------------------------------

interface ForwardState {
  scheme: 'HTTP' | 'HTTPS';
  host: string;
  port: number;
}

function ForwardPanel({
  state,
  setState,
}: {
  state: ForwardState;
  setState: (s: ForwardState) => void;
}) {
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
      <Typography variant="body2" color="text.secondary">
        MockServer will proxy the matched request to <code>{state.scheme.toLowerCase()}://{state.host || 'host'}:{state.port}</code>,
        preserving the path / headers / body.
      </Typography>
      <Box sx={{ display: 'flex', gap: 1 }}>
        <TextField
          label="Scheme"
          size="small"
          select
          value={state.scheme}
          onChange={(e) => setState({ ...state, scheme: e.target.value as 'HTTP' | 'HTTPS' })}
          sx={{ width: 130 }}
        >
          <MenuItem value="HTTP">HTTP</MenuItem>
          <MenuItem value="HTTPS">HTTPS</MenuItem>
        </TextField>
        <TextField
          label="Host"
          size="small"
          sx={{ flex: 1 }}
          value={state.host}
          onChange={(e) => setState({ ...state, host: e.target.value })}
          placeholder="api.example.com"
        />
        <TextField
          label="Port"
          size="small"
          type="number"
          sx={{ width: 110 }}
          value={state.port}
          onChange={(e) => setState({ ...state, port: Number(e.target.value) || 0 })}
        />
      </Box>
    </Box>
  );
}

// ---------------------------------------------------------------------------
// Forward with override
// ---------------------------------------------------------------------------

interface ForwardOverrideState {
  overrideMethod: string;
  overrideHost: string;
  overrideScheme: 'HTTP' | 'HTTPS' | '';
  overridePath: string;
  overrideQueryString: string;   // "key=value" lines
  overrideHeaders: string;        // "name: value" lines
  overrideBody: string;
}

function ForwardOverridePanel({
  state,
  setState,
}: {
  state: ForwardOverrideState;
  setState: (s: ForwardOverrideState) => void;
}) {
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
      <Typography variant="body2" color="text.secondary">
        Forward the matched request upstream after rewriting any of the fields below.
        Leave a field blank to keep the incoming value. At least one override must be set.
      </Typography>
      <Box sx={{ display: 'flex', gap: 1 }}>
        <TextField
          label="Method"
          size="small"
          select
          value={state.overrideMethod}
          onChange={(e) => setState({ ...state, overrideMethod: e.target.value })}
          sx={{ width: 130 }}
        >
          {['', 'GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS'].map((m) => (
            <MenuItem key={m || 'unchanged'} value={m}>{m || '(unchanged)'}</MenuItem>
          ))}
        </TextField>
        <TextField
          label="Scheme"
          size="small"
          select
          value={state.overrideScheme}
          onChange={(e) => setState({ ...state, overrideScheme: e.target.value as ForwardOverrideState['overrideScheme'] })}
          sx={{ width: 140 }}
        >
          <MenuItem value="">(unchanged)</MenuItem>
          <MenuItem value="HTTP">HTTP</MenuItem>
          <MenuItem value="HTTPS">HTTPS</MenuItem>
        </TextField>
        <TextField
          label="Host header"
          size="small"
          sx={{ flex: 1 }}
          value={state.overrideHost}
          onChange={(e) => setState({ ...state, overrideHost: e.target.value })}
          placeholder="upstream.example.com:8080"
        />
      </Box>
      <TextField
        label="Path"
        size="small"
        value={state.overridePath}
        onChange={(e) => setState({ ...state, overridePath: e.target.value })}
        placeholder="/v2/redirected-endpoint"
      />
      <TextField
        label="Query string parameters (one per line, key=value)"
        multiline
        minRows={2}
        maxRows={6}
        value={state.overrideQueryString}
        onChange={(e) => setState({ ...state, overrideQueryString: e.target.value })}
        placeholder={'limit=50\noffset=0'}
        slotProps={{ input: { sx: { fontFamily: 'monospace', fontSize: '0.78rem' } } }}
      />
      <TextField
        label="Headers (one per line, Name: value)"
        multiline
        minRows={2}
        maxRows={6}
        value={state.overrideHeaders}
        onChange={(e) => setState({ ...state, overrideHeaders: e.target.value })}
        placeholder={'X-Forwarded-For: 1.2.3.4\nAuthorization: Bearer XYZ'}
        slotProps={{ input: { sx: { fontFamily: 'monospace', fontSize: '0.78rem' } } }}
      />
      <TextField
        label="Body override"
        multiline
        minRows={3}
        maxRows={12}
        value={state.overrideBody}
        onChange={(e) => setState({ ...state, overrideBody: e.target.value })}
        placeholder='{"replaced":"body"}'
        slotProps={{ input: { sx: { fontFamily: 'monospace', fontSize: '0.78rem' } } }}
      />
    </Box>
  );
}

// ---------------------------------------------------------------------------
// Class callback
// ---------------------------------------------------------------------------

interface CallbackState {
  callbackClass: string;
}

function CallbackPanel({
  state,
  setState,
}: {
  state: CallbackState;
  setState: (s: CallbackState) => void;
}) {
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
      <Typography variant="body2" color="text.secondary">
        Invoke a Java class implementing <code>ExpectationResponseCallback</code> on
        the MockServer instance. The class must already be on MockServer's classpath —
        the dashboard does not upload code.
      </Typography>
      <TextField
        label="Callback class (fully-qualified name)"
        size="small"
        value={state.callbackClass}
        onChange={(e) => setState({ ...state, callbackClass: e.target.value })}
        placeholder="com.example.MyResponseCallback"
        slotProps={{ input: { sx: { fontFamily: 'monospace', fontSize: '0.78rem' } } }}
      />
    </Box>
  );
}

// ---------------------------------------------------------------------------
// Response template
// ---------------------------------------------------------------------------

type TemplateType = 'VELOCITY' | 'JAVASCRIPT' | 'MUSTACHE';

interface TemplateState {
  templateType: TemplateType;
  template: string;
}

const TEMPLATE_PLACEHOLDERS: Record<TemplateType, string> = {
  VELOCITY: '{ "statusCode": 200, "body": "$!request.path" }',
  JAVASCRIPT: 'return { statusCode: 200, body: request.path };',
  MUSTACHE: '{ "statusCode": 200, "body": "{{request.path}}" }',
};

function TemplatePanel({
  state,
  setState,
}: {
  state: TemplateState;
  setState: (s: TemplateState) => void;
}) {
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
      <Typography variant="body2" color="text.secondary">
        Build the response dynamically from the incoming request. The template engine
        receives a <code>request</code> object exposing method / path / headers / body.
      </Typography>
      <TextField
        label="Template engine"
        size="small"
        select
        value={state.templateType}
        onChange={(e) => setState({ ...state, templateType: e.target.value as TemplateType })}
        sx={{ width: 200 }}
      >
        <MenuItem value="VELOCITY">Velocity</MenuItem>
        <MenuItem value="JAVASCRIPT">JavaScript</MenuItem>
        <MenuItem value="MUSTACHE">Mustache</MenuItem>
      </TextField>
      <TextField
        label="Template body"
        multiline
        minRows={6}
        maxRows={20}
        value={state.template}
        onChange={(e) => setState({ ...state, template: e.target.value })}
        placeholder={TEMPLATE_PLACEHOLDERS[state.templateType]}
        slotProps={{ input: { sx: { fontFamily: 'monospace', fontSize: '0.78rem' } } }}
      />
    </Box>
  );
}

// ---------------------------------------------------------------------------
// Error / fault injection
// ---------------------------------------------------------------------------

type DelayUnit = 'MILLISECONDS' | 'SECONDS' | 'MINUTES';

interface ErrorState {
  dropConnection: boolean;
  responseBytesB64: string;
  delayValue: number;
  delayUnit: DelayUnit;
}

function ErrorPanel({
  state,
  setState,
}: {
  state: ErrorState;
  setState: (s: ErrorState) => void;
}) {
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
      <Typography variant="body2" color="text.secondary">
        Inject a fault for matched requests — close the TCP connection abruptly,
        and / or return arbitrary bytes that are NOT a valid HTTP response. Useful
        for testing client error handling.
      </Typography>
      <FormControlLabel
        control={
          <Switch
            size="small"
            checked={state.dropConnection}
            onChange={(e) => setState({ ...state, dropConnection: e.target.checked })}
          />
        }
        label={
          <Typography variant="body2" sx={{ fontSize: '0.82rem' }}>
            Drop connection (RST the TCP socket)
          </Typography>
        }
      />
      <TextField
        label="Response bytes (base64, optional)"
        size="small"
        value={state.responseBytesB64}
        onChange={(e) => setState({ ...state, responseBytesB64: e.target.value })}
        placeholder="SFRUUC8xLjEgNTAwIEludGVybmFsIFNlcnZlciBFcnJvcg=="
        slotProps={{ input: { sx: { fontFamily: 'monospace', fontSize: '0.78rem' } } }}
        helperText="Sent before the connection is dropped (if drop is enabled). Base64-encoded raw bytes."
      />
      <Box sx={{ display: 'flex', gap: 1, alignItems: 'flex-start' }}>
        <TextField
          label="Pre-action delay"
          size="small"
          type="number"
          value={state.delayValue}
          onChange={(e) => setState({ ...state, delayValue: Number(e.target.value) || 0 })}
          sx={{ width: 180 }}
          helperText="0 = no delay"
        />
        <TextField
          label="Unit"
          size="small"
          select
          value={state.delayUnit}
          onChange={(e) => setState({ ...state, delayUnit: e.target.value as DelayUnit })}
          sx={{ width: 160 }}
        >
          <MenuItem value="MILLISECONDS">milliseconds</MenuItem>
          <MenuItem value="SECONDS">seconds</MenuItem>
          <MenuItem value="MINUTES">minutes</MenuItem>
        </TextField>
      </Box>
    </Box>
  );
}

// ---------------------------------------------------------------------------
// Chaos / fault injection panel (optional, cross-cutting across action types)
// ---------------------------------------------------------------------------

function ChaosPanel({
  chaos,
  setChaos,
}: {
  chaos: StandardChaosDraft;
  setChaos: (c: StandardChaosDraft) => void;
}) {
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
      <Typography variant="body2" color="text.secondary">
        Inject probabilistic faults (error status, latency) into responses for
        matched requests. This works on mocked, forwarded, and proxied responses.
      </Typography>
      <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap', alignItems: 'flex-start' }}>
        <TextField
          label="Error status"
          size="small"
          type="number"
          value={chaos.errorStatus ?? ''}
          onChange={(e) => setChaos({ ...chaos, errorStatus: e.target.value === '' ? undefined : parseInt(e.target.value, 10) })}
          sx={{ width: 120 }}
          helperText="e.g. 500, 503, 429"
        />
        <TextField
          label="Error prob (0-1)"
          size="small"
          type="number"
          value={chaos.errorProbability ?? ''}
          onChange={(e) => setChaos({ ...chaos, errorProbability: e.target.value === '' ? undefined : parseFloat(e.target.value) })}
          sx={{ width: 130 }}
          helperText="1.0 = always"
        />
        <TextField
          label="Retry-After"
          size="small"
          value={chaos.retryAfter ?? ''}
          onChange={(e) => setChaos({ ...chaos, retryAfter: e.target.value || undefined })}
          sx={{ width: 120 }}
          helperText='e.g. "30"'
        />
      </Box>
      <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap', alignItems: 'flex-start' }}>
        <TextField
          label="Latency value"
          size="small"
          type="number"
          value={chaos.latencyValue ?? ''}
          onChange={(e) => setChaos({ ...chaos, latencyValue: e.target.value === '' ? undefined : parseInt(e.target.value, 10) })}
          sx={{ width: 140 }}
          helperText="0 = no latency"
        />
        <TextField
          label="Latency unit"
          size="small"
          select
          value={chaos.latencyUnit ?? 'MILLISECONDS'}
          onChange={(e) => setChaos({ ...chaos, latencyUnit: e.target.value as ChaosDelayUnit })}
          sx={{ width: 160 }}
        >
          <MenuItem value="MILLISECONDS">milliseconds</MenuItem>
          <MenuItem value="SECONDS">seconds</MenuItem>
          <MenuItem value="MINUTES">minutes</MenuItem>
        </TextField>
        <TextField
          label="Seed"
          size="small"
          type="number"
          value={chaos.seed ?? ''}
          onChange={(e) => setChaos({ ...chaos, seed: e.target.value === '' ? undefined : parseInt(e.target.value, 10) })}
          sx={{ width: 110 }}
          helperText="reproducible prob"
        />
      </Box>
      <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap', alignItems: 'flex-start' }}>
        <TextField
          label="Succeed first (N)"
          size="small"
          type="number"
          value={chaos.succeedFirst ?? ''}
          onChange={(e) => setChaos({ ...chaos, succeedFirst: e.target.value === '' ? undefined : parseInt(e.target.value, 10) })}
          sx={{ width: 150 }}
          helperText="first N requests OK"
        />
        <TextField
          label="Fail request count"
          size="small"
          type="number"
          value={chaos.failRequestCount ?? ''}
          onChange={(e) => setChaos({ ...chaos, failRequestCount: e.target.value === '' ? undefined : parseInt(e.target.value, 10) })}
          sx={{ width: 160 }}
          helperText="then next M fail"
        />
      </Box>
    </Box>
  );
}

// ---------------------------------------------------------------------------
// Main view
// ---------------------------------------------------------------------------

export interface ComposerViewProps {
  connectionParams: ConnectionParams;
}

export default function ComposerView({ connectionParams }: ComposerViewProps) {
  const activeExpectations = useDashboardStore((s) => s.activeExpectations);

  const [kind, setKind] = useState<ExpectationKind>('standard');
  const [actionType, setActionType] = useState<ActionType>('static');
  const [matcher, setMatcher] = useState<MatcherState>(emptyMatcher);
  const [loadFromKey, setLoadFromKey] = useState('');
  const [llmScenarioName, setLlmScenarioName] = useState('');

  // Per-action state (kept independent so switching radios doesn't lose work).
  const [staticState, setStaticState] = useState<StaticState>({
    statusCode: 200,
    body: '',
    contentType: 'application/json',
  });
  const [forwardState, setForwardState] = useState<ForwardState>({
    scheme: 'HTTPS',
    host: '',
    port: 443,
  });
  const [forwardOverrideState, setForwardOverrideState] = useState<ForwardOverrideState>({
    overrideMethod: '',
    overrideHost: '',
    overrideScheme: '',
    overridePath: '',
    overrideQueryString: '',
    overrideHeaders: '',
    overrideBody: '',
  });
  const [callbackState, setCallbackState] = useState<CallbackState>({ callbackClass: '' });
  const [templateState, setTemplateState] = useState<TemplateState>({
    templateType: 'VELOCITY',
    template: '',
  });
  const [errorState, setErrorState] = useState<ErrorState>({
    dropConnection: true,
    responseBytesB64: '',
    delayValue: 0,
    delayUnit: 'MILLISECONDS',
  });

  // Chaos profile — cross-cutting, applies regardless of action type
  // (except httpError which is already a fault action).
  const [chaosEnabled, setChaosEnabled] = useState(false);
  const [chaosState, setChaosState] = useState<StandardChaosDraft>({});

  const [registering, setRegistering] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [snackMessage, setSnackMessage] = useState<string | null>(null);

  const selectedMeta = useMemo(
    () => ACTION_TYPES.find((a) => a.value === actionType)!,
    [actionType],
  );

  // Single register helper — builds a StandardActionPayload from current
  // state and PUTs via registerExpectation, which itself uses
  // buildExpectationJson so the JSON sent matches the Java/JSON/curl preview
  // exactly. Replaces six per-action register helpers that had drifted apart
  // from the codegen path.
  const handleRegister = useCallback(
    async (action: StandardActionPayload) => {
      setRegistering(true);
      setError(null);
      try {
        await registerExpectation(connectionParams, matcher, action);
        setSnackMessage(`Registered ${matcher.method || 'ANY'} ${matcher.path}`);
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      } finally {
        setRegistering(false);
      }
    },
    [connectionParams, matcher],
  );

  const handleLoadExisting = useCallback(
    (key: string) => {
      setLoadFromKey(key);
      if (!key) return;
      const item = activeExpectations.find((e) => e.key === key);
      if (!item) return;
      setMatcher(matcherFromExpectation(item));

      // Detect the action shape and prefill the matching panel + switch the radio.
      const prefill = actionFromExpectation(item);
      if (!prefill) return;
      setActionType(prefill.type);
      if (prefill.staticState) setStaticState(prefill.staticState);
      if (prefill.forwardState) setForwardState(prefill.forwardState);
      if (prefill.forwardOverrideState) setForwardOverrideState(prefill.forwardOverrideState);
      if (prefill.callbackState) setCallbackState(prefill.callbackState);
      if (prefill.templateState) setTemplateState(prefill.templateState);
      if (prefill.errorState) setErrorState(prefill.errorState);

      // Repopulate chaos panel from an existing expectation
      const existingChaos = chaosFromExpectation(item.value);
      if (existingChaos) {
        setChaosEnabled(true);
        setChaosState(existingChaos);
      } else {
        setChaosEnabled(false);
        setChaosState({});
      }
    },
    [activeExpectations],
  );

  // Standard kind picker only lists expectations that AREN'T LLM
  // Conversation scenarios — those have their own picker on the
  // LLM-conversation kind below.
  const standardExpectations = useMemo(
    () => activeExpectations.filter((e) => !e.value['httpLlmResponse']),
    [activeExpectations],
  );

  const llmScenarios = useMemo(
    () => listConversationScenarios(activeExpectations),
    [activeExpectations],
  );

  return (
    <Box sx={{ flex: 1, overflowY: 'auto', p: 2 }}>
      <Box sx={{ maxWidth: 920, mx: 'auto', display: 'flex', flexDirection: 'column', gap: 2 }}>
        <Typography variant="h6" sx={{ fontSize: '1rem', fontWeight: 600 }}>
          Compose a new expectation
        </Typography>

        {/* Top-level kind selector — LLM Conversation is structurally
            different from a standard HTTP expectation (multiple expectations
            with scenario state) so it gets its own form path entirely. */}
        <Paper variant="outlined" sx={{ p: 2 }}>
          <Typography variant="subtitle2" sx={{ fontSize: '0.78rem', fontWeight: 600, mb: 1, textTransform: 'uppercase', letterSpacing: 0.5, color: 'text.secondary' }}>
            Expectation kind
          </Typography>
          <RadioGroup
            row
            value={kind}
            onChange={(e) => {
              setKind(e.target.value as ExpectationKind);
              setLoadFromKey('');
              setLlmScenarioName('');
            }}
          >
            <FormControlLabel
              value="standard"
              control={<Radio size="small" />}
              label={<Typography variant="body2" sx={{ fontSize: '0.82rem' }}>Standard HTTP expectation</Typography>}
            />
            <FormControlLabel
              value="llm_conversation"
              control={<Radio size="small" />}
              label={<Typography variant="body2" sx={{ fontSize: '0.82rem' }}>LLM Conversation</Typography>}
            />
          </RadioGroup>
        </Paper>

        {kind === 'standard' && (
          <Paper variant="outlined" sx={{ p: 2 }}>
            <Box sx={{ display: 'flex', gap: 1, alignItems: 'center' }}>
              <TextField
                label="Edit existing or add new"
                size="small"
                select
                sx={{ flex: 1 }}
                value={loadFromKey}
                onChange={(e) => handleLoadExisting(e.target.value)}
                slotProps={{ select: { native: true, displayEmpty: true }, inputLabel: { shrink: true } }}
              >
                <option value="">— add new expectation —</option>
                {standardExpectations.map((e) => {
                  const req = (e.value['httpRequest'] as Record<string, unknown> | undefined) ?? {};
                  const m = (req['method'] as string | undefined) ?? '';
                  const p = (req['path'] as string | undefined) ?? '';
                  const idShort = e.key.slice(0, 8);
                  return (
                    <option key={e.key} value={e.key}>
                      {idShort}… · {m || 'ANY'} {p || '(no path)'}
                    </option>
                  );
                })}
              </TextField>
              <Button
                size="small"
                variant="outlined"
                onClick={() => {
                  setLoadFromKey('');
                  setMatcher(emptyMatcher());
                }}
              >
                Reset
              </Button>
            </Box>
            {loadFromKey && (
              <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1, fontSize: '0.7rem' }}>
                Matcher and response action are prefilled from the selected
                expectation. Keep the Expectation ID to update in place; clear
                it to create a new expectation.
              </Typography>
            )}
          </Paper>
        )}

        {kind === 'llm_conversation' && (
          <>
            <Paper variant="outlined" sx={{ p: 2 }}>
              <Box sx={{ display: 'flex', gap: 1, alignItems: 'center' }}>
                <TextField
                  label="Edit existing or add new"
                  size="small"
                  select
                  sx={{ flex: 1 }}
                  value={llmScenarioName}
                  onChange={(e) => setLlmScenarioName(e.target.value)}
                  slotProps={{ select: { native: true, displayEmpty: true }, inputLabel: { shrink: true } }}
                >
                  <option value="">— add new LLM conversation —</option>
                  {llmScenarios.map((s) => (
                    <option key={s.scenarioName} value={s.scenarioName}>
                      {s.shortName} ({s.expectations.length} turn{s.expectations.length === 1 ? '' : 's'})
                    </option>
                  ))}
                </TextField>
                {llmScenarioName && (
                  <Button size="small" variant="outlined" onClick={() => setLlmScenarioName('')}>
                    Reset
                  </Button>
                )}
              </Box>
              <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1, fontSize: '0.7rem' }}>
                Editing preserves the existing expectation IDs so other clients
                holding references keep working.
              </Typography>
            </Paper>

            {/* Inline LLM conversation form — remounts whenever the selected
                scenario changes so the draft state is fresh per-scenario. */}
            <LlmConversationForm
              key={llmScenarioName || 'new'}
              connectionParams={connectionParams}
              initialScenarioName={llmScenarioName || undefined}
            />
          </>
        )}

        {kind === 'standard' && (
          <>
            {/* Step 1: matcher */}
            <Paper variant="outlined" sx={{ p: 2 }}>
              <Typography variant="subtitle2" sx={{ fontSize: '0.78rem', fontWeight: 600, mb: 1, textTransform: 'uppercase', letterSpacing: 0.5, color: 'text.secondary' }}>
                1 · Match a request
              </Typography>
              <MatcherPanel matcher={matcher} setMatcher={setMatcher} />
              <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1, fontSize: '0.7rem' }}>
                Protocol (HTTP/1.1 vs HTTP/2), keep-alive, respond-before-body, the
                socket-address override, and client certificate chains are not yet
                exposed in the form — use the REST API or raw JSON for those.
              </Typography>
            </Paper>

            {/* Step 2: action type */}
            <Paper variant="outlined" sx={{ p: 2 }}>
              <Typography variant="subtitle2" sx={{ fontSize: '0.78rem', fontWeight: 600, mb: 1, textTransform: 'uppercase', letterSpacing: 0.5, color: 'text.secondary' }}>
                2 · Respond with
              </Typography>
              <RadioGroup value={actionType} onChange={(e) => setActionType(e.target.value as ActionType)}>
                {ACTION_TYPES.map((a) => (
                  <FormControlLabel
                    key={a.value}
                    value={a.value}
                    control={<Radio size="small" />}
                    label={
                      <Box>
                        <Typography variant="body2" sx={{ fontSize: '0.82rem', fontWeight: 600 }}>
                          {a.label}
                        </Typography>
                        <Typography variant="caption" color="text.secondary" sx={{ fontSize: '0.7rem' }}>
                          {a.description}
                        </Typography>
                      </Box>
                    }
                    sx={{ alignItems: 'flex-start', mb: 0.5 }}
                  />
                ))}
              </RadioGroup>
            </Paper>

            {/* Step 3: per-action panel */}
            <Paper variant="outlined" sx={{ p: 2 }}>
              <Typography variant="subtitle2" sx={{ fontSize: '0.78rem', fontWeight: 600, mb: 1, textTransform: 'uppercase', letterSpacing: 0.5, color: 'text.secondary' }}>
                3 · {selectedMeta.label}
              </Typography>
              <Divider sx={{ mb: 1.5 }} />

              {actionType === 'static' && (
                <StaticHttpPanel state={staticState} setState={setStaticState} />
              )}
              {actionType === 'forward' && (
                <ForwardPanel state={forwardState} setState={setForwardState} />
              )}
              {actionType === 'forward_override' && (
                <ForwardOverridePanel state={forwardOverrideState} setState={setForwardOverrideState} />
              )}
              {actionType === 'callback' && (
                <CallbackPanel state={callbackState} setState={setCallbackState} />
              )}
              {actionType === 'template' && (
                <TemplatePanel state={templateState} setState={setTemplateState} />
              )}
              {actionType === 'error' && (
                <ErrorPanel state={errorState} setState={setErrorState} />
              )}
            </Paper>

            {/* Chaos / fault injection — optional, cross-cutting. Not shown
                for the Error action type since it's already a fault action. */}
            {actionType !== 'error' && (
              <Paper variant="outlined" sx={{ p: 2 }}>
                <FormControlLabel
                  control={
                    <Switch
                      size="small"
                      checked={chaosEnabled}
                      onChange={(e) => {
                        setChaosEnabled(e.target.checked);
                        if (!e.target.checked) setChaosState({});
                      }}
                    />
                  }
                  label={
                    <Typography variant="subtitle2" sx={{ fontSize: '0.78rem', fontWeight: 600, textTransform: 'uppercase', letterSpacing: 0.5, color: 'text.secondary' }}>
                      Inject fault / chaos (optional)
                    </Typography>
                  }
                  sx={{ m: 0 }}
                />
                <Collapse in={chaosEnabled} unmountOnExit>
                  <Box sx={{ mt: 1.5 }}>
                    <ChaosPanel chaos={chaosState} setChaos={setChaosState} />
                  </Box>
                </Collapse>
              </Paper>
            )}

            {/* Step 4: review & register — shows the generated Java / JSON /
                curl, then the single Register button (mirrors the
                LLM Conversation form's review-and-register section). */}
            {(() => {
              const currentAction: StandardActionPayload = { type: actionType };
              if (actionType === 'static') currentAction.static = staticState;
              if (actionType === 'forward') currentAction.forward = forwardState;
              if (actionType === 'forward_override') currentAction.forwardOverride = forwardOverrideState;
              if (actionType === 'callback') currentAction.callback = callbackState;
              if (actionType === 'template') currentAction.template = templateState;
              if (actionType === 'error') currentAction.error = errorState;
              if (chaosEnabled && actionType !== 'error') currentAction.chaos = chaosState;

              const dispatchRegister = () => void handleRegister(currentAction);

              // Per-action validation
              const canRegister = (() => {
                if (matcher.path.trim().length === 0) return false;
                switch (actionType) {
                  case 'static': return true;
                  case 'forward': return forwardState.host.trim().length > 0 && forwardState.port > 0;
                  case 'forward_override':
                    return (
                      forwardOverrideState.overrideMethod.trim().length > 0 ||
                      forwardOverrideState.overrideHost.trim().length > 0 ||
                      forwardOverrideState.overrideScheme !== '' ||
                      forwardOverrideState.overridePath.trim().length > 0 ||
                      forwardOverrideState.overrideQueryString.trim().length > 0 ||
                      forwardOverrideState.overrideHeaders.trim().length > 0 ||
                      forwardOverrideState.overrideBody.trim().length > 0
                    );
                  case 'callback': return callbackState.callbackClass.trim().length > 0;
                  case 'template': return templateState.template.trim().length > 0;
                  case 'error':
                    return errorState.dropConnection || errorState.responseBytesB64.trim().length > 0;
                }
              })();

              const editingExisting = matcher.id.trim().length > 0;

              return (
                <Paper variant="outlined" sx={{ p: 2 }}>
                  <Typography variant="subtitle2" sx={{ fontSize: '0.78rem', fontWeight: 600, mb: 1, textTransform: 'uppercase', letterSpacing: 0.5, color: 'text.secondary' }}>
                    4 · Review &amp; register
                  </Typography>
                  <Divider sx={{ mb: 1 }} />
                  <StandardReview
                    matcher={matcher}
                    action={currentAction}
                    baseUrl={baseUrl(connectionParams)}
                  />
                  <Box sx={{ mt: 2, display: 'flex', gap: 1, alignItems: 'center' }}>
                    <Button
                      variant="contained"
                      size="small"
                      onClick={dispatchRegister}
                      disabled={registering || !canRegister}
                    >
                      {registering
                        ? 'Registering…'
                        : editingExisting
                          ? 'Update expectation'
                          : 'Register expectation'}
                    </Button>
                    {editingExisting ? (
                      <Typography variant="caption" color="success.main" sx={{ fontSize: '0.7rem' }}>
                        Editing — the Expectation ID will be reused so this updates in place.
                      </Typography>
                    ) : (
                      <Typography variant="caption" color="text.secondary" sx={{ fontSize: '0.7rem' }}>
                        Leave the Expectation ID blank to create a new one, or paste an existing ID above to update in place.
                      </Typography>
                    )}
                  </Box>
                </Paper>
              );
            })()}
          </>
        )}

        {error && (
          <Alert severity="error" variant="outlined">{error}</Alert>
        )}
      </Box>
      <Snackbar
        open={snackMessage !== null}
        autoHideDuration={3000}
        onClose={() => setSnackMessage(null)}
        message={snackMessage ?? ''}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      />
    </Box>
  );
}
