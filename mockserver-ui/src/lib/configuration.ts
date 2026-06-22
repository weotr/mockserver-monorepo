/**
 * Client for MockServer's live configuration control plane (GET/PUT /mockserver/configuration).
 * GET returns the full configuration; PUT applies a partial ConfigurationDTO (only the supplied
 * fields are changed), so the dashboard can tweak a setting like the log level at runtime.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

export type Configuration = Record<string, unknown>;

export async function getConfiguration(params: ConnectionParams, signal?: AbortSignal): Promise<Configuration> {
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/configuration`, { signal });
  if (!res.ok) throw new Error(`Failed to load configuration (HTTP ${res.status} ${res.statusText})`);
  return (await res.json()) as Configuration;
}

/** Apply a partial configuration change (only the supplied keys are modified server-side). */
export async function updateConfiguration(params: ConnectionParams, partial: Configuration): Promise<void> {
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/configuration`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(partial),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(text || `Failed to update configuration (HTTP ${res.status} ${res.statusText})`);
  }
}

export const LOG_LEVELS = ['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR', 'OFF'] as const;

// ---------------------------------------------------------------------------
// Editable property descriptors
// ---------------------------------------------------------------------------

/** The control type rendered for an editable property. */
export type EditableType = 'boolean' | 'string' | 'number';

/**
 * Descriptor for a runtime-mutable configuration property that should be
 * editable in the dashboard.  Adding an entry here is all that is needed to
 * expose a new toggle / field — ConfigurationDialog drives its controls from
 * this list.
 */
export interface EditablePropertyDescriptor {
  /** The JSON key in ConfigurationDTO. */
  key: string;
  /** Human-readable label shown next to the control. */
  label: string;
  /** Control type. */
  type: EditableType;
  /** Short tooltip / help text. */
  help: string;
  /** Logical group (controls are rendered grouped). */
  group: string;
}

/**
 * Declarative list of runtime-mutable properties beyond the original three
 * (logLevel, detailedMatchFailures, metricsEnabled — which keep their
 * bespoke controls).
 *
 * Order within each group determines render order.
 */
export const EDITABLE_PROPERTIES: readonly EditablePropertyDescriptor[] = [
  // Developer / data
  // NOTE: devMode is intentionally NOT runtime-editable — its only effect is supplying the
  // startup defaults for maxLogEntries/maxExpectations, which size the log ring buffer and
  // expectation store at construction time and are never resized at runtime. Changing it on a
  // running server would mislead (no effect), so it stays in the read-only table.
  {
    key: 'generateRealisticExampleValues',
    label: 'Realistic example values',
    type: 'boolean',
    help: 'Generate realistic (Faker-style) example values in stubs instead of fixed placeholders.',
    group: 'Developer / data',
  },
  {
    key: 'attachMismatchDiagnosticToResponse',
    label: 'Mismatch diagnostic in response',
    type: 'boolean',
    help: 'Attach detailed mismatch diagnostics to unmatched-request responses.',
    group: 'Developer / data',
  },

  // Validation proxy
  {
    key: 'validateProxyOpenAPISpec',
    label: 'OpenAPI spec (URL / path)',
    type: 'string',
    help: 'OpenAPI specification URL, file path, or inline JSON/YAML used to validate proxied traffic.',
    group: 'Validation proxy',
  },
  {
    key: 'validateProxyEnforce',
    label: 'Enforce validation',
    type: 'boolean',
    help: 'When enabled, proxied requests that violate the OpenAPI spec are rejected.',
    group: 'Validation proxy',
  },

  // Chaos auto-halt
  {
    key: 'chaosAutoHaltEnabled',
    label: 'Auto-halt enabled',
    type: 'boolean',
    help: 'Automatically halt chaos injection when the error threshold is exceeded.',
    group: 'Chaos auto-halt',
  },
  {
    key: 'chaosAutoHaltErrorThreshold',
    label: 'Error threshold',
    type: 'number',
    help: 'Maximum number of errors within the window before chaos is halted.',
    group: 'Chaos auto-halt',
  },
  {
    key: 'chaosAutoHaltWindowMillis',
    label: 'Window (ms)',
    type: 'number',
    help: 'Sliding window in milliseconds over which errors are counted for auto-halt.',
    group: 'Chaos auto-halt',
  },

  // Matching & proxying — read live on every request/match, so safe to change at runtime
  {
    key: 'matchersFailFast',
    label: 'Matchers fail fast',
    type: 'boolean',
    help: 'Stop evaluating a request matcher at the first non-matching field instead of collecting every mismatch. Read live on each match.',
    group: 'Matching & proxying',
  },
  {
    key: 'attemptToProxyIfNoMatchingExpectation',
    label: 'Proxy unmatched requests',
    type: 'boolean',
    help: 'When no expectation matches, forward the request to its Host instead of returning 404. Read live on each request.',
    group: 'Matching & proxying',
  },
  {
    key: 'maximumNumberOfRequestToReturnInVerificationFailure',
    label: 'Max requests in verification failure',
    type: 'number',
    help: 'Cap on the number of recorded requests included in a verification failure message. Read live when a verification fails.',
    group: 'Matching & proxying',
  },

  // Logging — read live on every log write, so safe to change at runtime
  {
    key: 'disableLogging',
    label: 'Disable logging',
    type: 'boolean',
    help: 'Suppress all MockServer log output. Read live on each log write.',
    group: 'Logging',
  },
  {
    key: 'compactLogFormat',
    label: 'Compact log format',
    type: 'boolean',
    help: 'Render log entries in a single-line compact format. Read live on each log write.',
    group: 'Logging',
  },

  // CORS — read live when building responses / handling preflight, so safe to change at runtime
  {
    key: 'enableCORSForAPI',
    label: 'CORS for control plane API',
    type: 'boolean',
    help: 'Add CORS headers (and answer preflight) for the MockServer control-plane API. Read live per request.',
    group: 'CORS',
  },
  {
    key: 'enableCORSForAllResponses',
    label: 'CORS for all responses',
    type: 'boolean',
    help: 'Add CORS headers to every response, not just the control-plane API. Read live per response.',
    group: 'CORS',
  },

] as const;
