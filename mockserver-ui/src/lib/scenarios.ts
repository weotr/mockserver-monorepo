/**
 * Client for MockServer's scenario state-machine control-plane endpoints.
 *
 * GET  /mockserver/scenario                → { scenarios: [{ scenarioName, currentState }] }
 * GET  /mockserver/scenario/{name}         → { scenarioName, currentState }
 * PUT  /mockserver/scenario/{name}         → set state (+ optional timed transition)
 * PUT  /mockserver/scenario/{name}/trigger → external trigger to set state
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

// ---------------------------------------------------------------------------
// Response types
// ---------------------------------------------------------------------------

export interface ScenarioStateResponse {
  scenarioName: string;
  currentState: string;
}

export interface SetScenarioStateResponse {
  scenarioName: string;
  currentState: string;
  nextState?: string;
  transitionAfterMs?: number;
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function scenarioEndpoint(params: ConnectionParams, name: string): string {
  return `${buildBaseUrl(params)}/mockserver/scenario/${encodeURIComponent(name)}`;
}

async function ensureOk(res: Response): Promise<void> {
  if (res.ok) return;
  let message = `HTTP ${res.status} ${res.statusText}`;
  try {
    const body = (await res.json()) as { error?: unknown };
    if (body && typeof body.error === 'string') message = body.error;
  } catch {
    // non-JSON body — keep the status-line message
  }
  throw new Error(message);
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

/** List every known scenario and its current state. */
export async function listScenarios(
  params: ConnectionParams,
  signal?: AbortSignal,
): Promise<ScenarioStateResponse[]> {
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/scenario`, { signal });
  await ensureOk(res);
  const body = (await res.json()) as { scenarios?: ScenarioStateResponse[] };
  return Array.isArray(body.scenarios) ? body.scenarios : [];
}

/** Fetch the current state of a scenario. */
export async function getScenarioState(
  params: ConnectionParams,
  name: string,
  signal?: AbortSignal,
): Promise<ScenarioStateResponse> {
  const res = await fetch(scenarioEndpoint(params, name), { signal });
  await ensureOk(res);
  return (await res.json()) as ScenarioStateResponse;
}

/**
 * Set the state of a scenario, optionally scheduling a timed transition.
 *
 * @param state           The state to set immediately
 * @param transitionAfterMs  If provided with nextState, schedule an auto-transition
 * @param nextState       The state to transition to after transitionAfterMs
 */
export async function setScenarioState(
  params: ConnectionParams,
  name: string,
  state: string,
  transitionAfterMs?: number,
  nextState?: string,
): Promise<SetScenarioStateResponse> {
  const payload: Record<string, unknown> = { state };
  if (transitionAfterMs != null && transitionAfterMs > 0 && nextState) {
    payload.transitionAfterMs = transitionAfterMs;
    payload.nextState = nextState;
  }
  const res = await fetch(scenarioEndpoint(params, name), {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
  await ensureOk(res);
  return (await res.json()) as SetScenarioStateResponse;
}

/** Trigger a scenario state transition (external trigger). */
export async function triggerScenario(
  params: ConnectionParams,
  name: string,
  newState: string,
): Promise<ScenarioStateResponse> {
  const res = await fetch(`${scenarioEndpoint(params, name)}/trigger`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ newState }),
  });
  await ensureOk(res);
  return (await res.json()) as ScenarioStateResponse;
}
