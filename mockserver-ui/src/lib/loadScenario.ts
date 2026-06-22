/**
 * Client for MockServer's load-injection (Load Scenario / performance testing)
 * control-plane endpoint (`/mockserver/loadScenario`). Drive API load against a
 * target with a ramp profile and templated steps — a pure SLI producer.
 *
 * The control plane has two halves:
 *
 *  Registry (a named catalogue of scenarios — registering does NOT run them, and
 *  is allowed even when load generation is disabled):
 *   - PUT    /loadScenario              register/replace a scenario (body = LoadScenario JSON)
 *   - GET    /loadScenario              list all registered scenarios + their state
 *   - GET    /loadScenario/{name}       fetch one registered scenario
 *   - DELETE /loadScenario/{name}       remove one registered scenario
 *   - DELETE /loadScenario              clear all registered scenarios
 *
 *  Run control (requires `loadGenerationEnabled=true` —
 *  `MOCKSERVER_LOAD_GENERATION_ENABLED` — else 403):
 *   - PUT    /loadScenario/start        start one/many registered scenarios by name
 *   - PUT    /loadScenario/stop         stop one/many (or all) running scenarios
 *
 * For backward compatibility the legacy single-scenario verbs still work: a bare
 * PUT/GET/DELETE on `/loadScenario` (with a scenario body / status shape) drive
 * the most-recent active run, which {@link fetchLoadScenario} continues to poll.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

/** A MockServer Delay (used for thinkTime / iteration pauses). */
export interface DelayDTO {
  timeUnit: string;
  value: number;
}

/** Target socket address for a step's request. */
export interface SocketAddressDTO {
  host: string;
  port: number;
  scheme?: 'HTTP' | 'HTTPS';
}

/** The minimal HttpRequest shape a load step needs (templated path/body). */
export interface LoadRequestDTO {
  method?: string;
  path?: string;
  headers?: Record<string, string[]>;
  body?: string;
  socketAddress?: SocketAddressDTO;
}

/** A single templated request step in a load scenario. */
export interface LoadStepDTO {
  name?: string;
  request: LoadRequestDTO;
  thinkTime?: DelayDTO;
  labels?: Record<string, string>;
}

/** The kind of a load stage. */
export type LoadStageType = 'VU' | 'RATE' | 'PAUSE';

/** Interpolation curve used to ramp a value across a ramp stage. */
export type RampCurve = 'LINEAR' | 'EXPONENTIAL' | 'QUADRATIC';

/**
 * One stage of a staged load profile (Load Profile v2). Stages run in sequence.
 *  - VU stage: holds `vus`, or ramps `startVus`→`endVus` along `curve`.
 *  - RATE stage: holds `rate`, or ramps `startRate`→`endRate` (iterations/second) along `curve`;
 *    optional `maxVus` caps the auto-scaling VU pool.
 *  - PAUSE stage: drives no load for `durationMillis`.
 */
export interface LoadStageDTO {
  type: LoadStageType;
  durationMillis: number;
  curve?: RampCurve;
  // VU-stage fields.
  vus?: number;
  startVus?: number;
  endVus?: number;
  // RATE-stage fields (iterations per second).
  rate?: number;
  startRate?: number;
  endRate?: number;
  maxVus?: number;
}

/** Staged load profile: an ordered list of stages run one after another. */
export interface LoadProfileDTO {
  stages: LoadStageDTO[];
}

/** An API-driven load scenario. */
export interface LoadScenarioDTO {
  name: string;
  templateType?: 'VELOCITY' | 'MUSTACHE';
  maxRequests?: number;
  labels?: Record<string, string>;
  /** Delay (ms) the server waits before this scenario begins driving load once started. */
  startDelayMillis?: number;
  profile: LoadProfileDTO;
  steps: LoadStepDTO[];
}

export type LoadState = 'none' | 'running' | 'completed' | 'stopped';

/**
 * Registry-level lifecycle state of a registered scenario (uppercase, as the server reports it):
 *  - LOADED    registered but never started
 *  - PENDING   start requested, honouring `startDelayMillis` before load begins
 *  - RUNNING   actively driving load
 *  - COMPLETED finished its profile
 *  - STOPPED   stopped before completing
 */
export type LoadScenarioState = 'LOADED' | 'PENDING' | 'RUNNING' | 'COMPLETED' | 'STOPPED';

/** One entry in the registry listing (GET /loadScenario). */
export interface RegisteredScenario {
  name: string;
  state: LoadScenarioState;
  /** Delay (ms) the server waits before this scenario begins driving load once started. */
  startDelayMillis?: number;
  /** The full scenario definition, echoed so it can be loaded back into the author form. */
  definition?: LoadScenarioDTO;
  /**
   * Live per-scenario status/metrics, synthesised from the flat live fields the server
   * emits on the listing node (present once the scenario has run; undefined for a
   * never-run LOADED scenario).
   */
  status?: LoadScenarioStatus;
}

/** Live status of the current/most-recent load scenario (GET response). */
export interface LoadScenarioStatus {
  name?: string;
  state: LoadState;
  elapsedMillis?: number;
  currentVus?: number;
  /** 0-based index of the currently-running stage (omitted when not running). */
  stageIndex?: number;
  /** Type of the currently-running stage (omitted when not running). */
  stageType?: LoadStageType;
  /** Current setpoint for the running stage: target VUs (VU), target rate iterations/s (RATE), or 0 (PAUSE). */
  currentTarget?: number;
  requestsSent?: number;
  succeeded?: number;
  failed?: number;
  p50Millis?: number;
  p95Millis?: number;
  p99Millis?: number;
  runId?: string;
  startedAt?: number;
  endedAt?: number;
  labels?: Record<string, string>;
  /**
   * The full scenario definition this run was started with — echoed by the server whenever a run
   * exists (omitted only when state is "none"). Lets any tab/client load the exact LoadScenario back
   * into the author form, even one started elsewhere, and re-submit it verbatim as a PUT body.
   */
  definition?: LoadScenarioDTO;
}

/**
 * Error thrown when the control plane responds. Carries the HTTP status so the
 * UI can special-case 403 (load generation disabled) from other failures.
 */
export class LoadScenarioError extends Error {
  readonly status: number;
  constructor(message: string, status: number) {
    super(message);
    this.name = 'LoadScenarioError';
    this.status = status;
  }
}

function endpoint(params: ConnectionParams): string {
  return `${buildBaseUrl(params)}/mockserver/loadScenario`;
}

/** Throws a LoadScenarioError carrying the status (and server {error} when present). */
async function ensureOk(res: Response): Promise<void> {
  if (res.ok) return;
  let message = `HTTP ${res.status} ${res.statusText}`;
  try {
    const body = (await res.json()) as { error?: unknown };
    if (body && typeof body.error === 'string') message = body.error;
  } catch {
    // non-JSON body — keep the status-line message
  }
  throw new LoadScenarioError(message, res.status);
}

/**
 * Fetch the current load scenario status. A 403 means load generation is
 * disabled — surfaced as a LoadScenarioError with status 403 so the panel can
 * render the enablement help instead of a generic error.
 */
export async function fetchLoadScenario(
  params: ConnectionParams,
  signal?: AbortSignal,
): Promise<LoadScenarioStatus> {
  const res = await fetch(endpoint(params), { signal });
  await ensureOk(res);
  const body = (await res.json()) as Partial<LoadScenarioStatus>;
  return { ...body, state: body.state ?? 'none' };
}

/**
 * Register (or replace) a scenario in the registry — PUT /loadScenario with the
 * scenario JSON body. This does NOT run it and is allowed even when load
 * generation is disabled. Returns when the server accepts it.
 */
export async function registerLoadScenario(
  params: ConnectionParams,
  scenario: LoadScenarioDTO,
): Promise<void> {
  const res = await fetch(endpoint(params), {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(scenario),
  });
  await ensureOk(res);
}

/**
 * Backwards-compatible alias retained for callers that still register via the
 * bare PUT verb. Prefer {@link registerLoadScenario} (register) +
 * {@link startScenariosByName} (run) for the explicit two-step flow.
 */
export const startLoadScenario = registerLoadScenario;

/**
 * The server emits each registered scenario's live status fields FLAT on the
 * listing node (siblings of name/state/definition), present only once the
 * scenario has run. The panel models them nested under `status`, so this builds
 * that nested LoadScenarioStatus from the flat node — returning `undefined` when
 * none of the live fields are present (e.g. a never-run LOADED scenario).
 */
function extractLoadScenarioStatus(node: Record<string, unknown>): LoadScenarioStatus | undefined {
  const liveKeys = [
    'elapsedMillis',
    'currentVus',
    'stageIndex',
    'stageType',
    'currentTarget',
    'requestsSent',
    'succeeded',
    'failed',
    'p50Millis',
    'p95Millis',
    'p99Millis',
    'runId',
    'startedAt',
    'endedAt',
    'labels',
  ] as const;
  if (!liveKeys.some((k) => node[k] !== undefined)) return undefined;
  return {
    // The nested status mirrors the node's lifecycle state (the panel's status.state
    // is the lowercase LoadState; map the uppercase registry state down to it).
    state: typeof node.state === 'string' ? (node.state.toLowerCase() as LoadState) : 'none',
    elapsedMillis: node.elapsedMillis as number | undefined,
    currentVus: node.currentVus as number | undefined,
    stageIndex: node.stageIndex as number | undefined,
    stageType: node.stageType as LoadStageType | undefined,
    currentTarget: node.currentTarget as number | undefined,
    requestsSent: node.requestsSent as number | undefined,
    succeeded: node.succeeded as number | undefined,
    failed: node.failed as number | undefined,
    p50Millis: node.p50Millis as number | undefined,
    p95Millis: node.p95Millis as number | undefined,
    p99Millis: node.p99Millis as number | undefined,
    runId: node.runId as string | undefined,
    startedAt: node.startedAt as number | undefined,
    endedAt: node.endedAt as number | undefined,
    labels: node.labels as Record<string, string> | undefined,
  };
}

/** Map one raw registry node (flat live fields) to a RegisteredScenario with nested status. */
function toRegisteredScenario(node: Record<string, unknown>): RegisteredScenario {
  return {
    name: node.name as string,
    state: node.state as LoadScenarioState,
    startDelayMillis: node.startDelayMillis as number | undefined,
    definition: node.definition as LoadScenarioDTO | undefined,
    status: extractLoadScenarioStatus(node),
  };
}

/** List every registered scenario and its registry state (GET /loadScenario). */
export async function listLoadScenarios(
  params: ConnectionParams,
  signal?: AbortSignal,
): Promise<RegisteredScenario[]> {
  const res = await fetch(endpoint(params), { signal });
  await ensureOk(res);
  const body = (await res.json()) as { scenarios?: Array<Record<string, unknown>> };
  return Array.isArray(body.scenarios) ? body.scenarios.map(toRegisteredScenario) : [];
}

/** Fetch a single registered scenario by name (GET /loadScenario/{name}). */
export async function getLoadScenario(
  params: ConnectionParams,
  name: string,
  signal?: AbortSignal,
): Promise<RegisteredScenario> {
  const res = await fetch(`${endpoint(params)}/${encodeURIComponent(name)}`, { signal });
  await ensureOk(res);
  return toRegisteredScenario((await res.json()) as Record<string, unknown>);
}

/**
 * Start one or more registered scenarios by name (PUT /loadScenario/start).
 * Requires load generation enabled — a 403 surfaces as a LoadScenarioError so the
 * panel can render the enablement help. Honours each scenario's startDelayMillis.
 */
export async function startScenariosByName(
  params: ConnectionParams,
  names: string[],
): Promise<void> {
  const res = await fetch(`${endpoint(params)}/start`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(names.length === 1 ? { name: names[0] } : { names }),
  });
  await ensureOk(res);
}

/**
 * Stop running scenarios (PUT /loadScenario/stop). Pass specific names, or omit
 * `names` (and pass `all: true`) to stop everything. Idempotent.
 */
export async function stopScenariosByName(
  params: ConnectionParams,
  names?: string[],
): Promise<void> {
  const body = names && names.length > 0 ? { names } : { all: true };
  const res = await fetch(`${endpoint(params)}/stop`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  await ensureOk(res);
}

/** Remove a single registered scenario (DELETE /loadScenario/{name}). */
export async function deleteLoadScenario(
  params: ConnectionParams,
  name: string,
): Promise<void> {
  const res = await fetch(`${endpoint(params)}/${encodeURIComponent(name)}`, { method: 'DELETE' });
  await ensureOk(res);
}

/** Clear the entire registry (DELETE /loadScenario). */
export async function clearLoadScenarios(params: ConnectionParams): Promise<void> {
  const res = await fetch(endpoint(params), { method: 'DELETE' });
  await ensureOk(res);
}

/**
 * Stop the current load scenario via the legacy bare DELETE verb. Idempotent —
 * 200 whether or not one was running. Retained for the single-run live view.
 */
export async function stopLoadScenario(params: ConnectionParams): Promise<void> {
  const res = await fetch(endpoint(params), { method: 'DELETE' });
  await ensureOk(res);
}

/** Whether the status represents an active (still-running) scenario. */
export function isRunning(status: LoadScenarioStatus | null): boolean {
  return status?.state === 'running';
}

/** Error rate (0..1) from the counts, or 0 when nothing has been sent. */
export function errorRate(status: LoadScenarioStatus): number {
  const sent = status.requestsSent ?? 0;
  if (sent <= 0) return 0;
  return (status.failed ?? 0) / sent;
}

/** Format a millisecond duration as a compact elapsed string (e.g. "1m 05s", "12.3s"). */
export function formatElapsed(millis: number): string {
  if (!Number.isFinite(millis) || millis < 0) return '—';
  const totalSeconds = millis / 1000;
  if (totalSeconds < 60) return `${totalSeconds.toFixed(1)}s`;
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = Math.round(totalSeconds % 60);
  return `${minutes}m ${String(seconds).padStart(2, '0')}s`;
}
