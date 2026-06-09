/**
 * Client for MockServer's interactive request breakpoints endpoints
 * (`/mockserver/breakpoint`). Lists paused exchanges and provides continue,
 * modify, and abort actions. See the Breakpoints docs.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

export interface PausedExchangeRequest {
  method?: string;
  path?: string;
}

export interface PausedExchange {
  id: string;
  ageMillis: number;
  expectationId?: string;
  request?: PausedExchangeRequest;
}

export interface BreakpointListResponse {
  pausedExchanges: PausedExchange[];
  count: number;
}

function endpoint(params: ConnectionParams): string {
  return `${buildBaseUrl(params)}/mockserver/breakpoint`;
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

/** Fetch the list of currently paused exchanges. */
export async function fetchBreakpoints(
  params: ConnectionParams,
  signal?: AbortSignal,
): Promise<BreakpointListResponse> {
  const res = await fetch(endpoint(params), { signal });
  if (!res.ok) return { pausedExchanges: [], count: 0 };
  return res.json();
}

/** Resume a paused exchange unchanged. */
export async function continueBreakpoint(
  params: ConnectionParams,
  id: string,
): Promise<void> {
  const res = await fetch(`${endpoint(params)}/continue`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id }),
  });
  await ensureOk(res);
}

/** Resume a paused exchange with a modified request. */
export async function modifyBreakpoint(
  params: ConnectionParams,
  id: string,
  httpRequest: Record<string, unknown>,
): Promise<void> {
  const res = await fetch(`${endpoint(params)}/modify`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id, httpRequest }),
  });
  await ensureOk(res);
}

/** Abort a paused exchange, optionally returning a custom response. */
export async function abortBreakpoint(
  params: ConnectionParams,
  id: string,
  httpResponse?: Record<string, unknown>,
): Promise<void> {
  const body: Record<string, unknown> = { id };
  if (httpResponse) body.httpResponse = httpResponse;
  const res = await fetch(`${endpoint(params)}/abort`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  await ensureOk(res);
}
