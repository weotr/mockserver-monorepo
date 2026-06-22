/**
 * Client for MockServer's server-side cassette registry:
 *   GET    /mockserver/cassettes  -> { cassettes: [{ path, filename, expectationCount, origin, lastUsed }] }
 *   PUT    /mockserver/cassettes  -> register/update one (body { path, filename?, expectationCount?, origin? })
 *   DELETE /mockserver/cassettes  -> remove one (body { path })
 *
 * The dashboard merges this server-side list with its per-browser localStorage registry so
 * cassettes recorded/loaded anywhere — or seeded by automation such as the demo loader — are
 * visible across page reloads and browsers.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import type { CassetteEntry } from './cassetteRegistry';

/** Throw in the standard `MockServer returned <status>: <body>` shape for a non-2xx response. */
async function throwServerError(res: Response): Promise<never> {
  const body = await res.text().catch(() => '');
  throw new Error(`MockServer returned ${res.status}: ${body}`);
}

interface ServerCassette {
  path: string;
  filename: string;
  expectationCount: number;
  origin: string;
  lastUsed: number; // epoch millis
}

function endpoint(params: ConnectionParams): string {
  return `${buildBaseUrl(params)}/mockserver/cassettes`;
}

function toEntry(c: ServerCassette): CassetteEntry {
  return {
    filename: c.filename,
    path: c.path,
    expectationCount: typeof c.expectationCount === 'number' ? c.expectationCount : -1,
    lastUsed: c.lastUsed ? new Date(c.lastUsed).toISOString() : '',
    origin: c.origin === 'recorded' ? 'recorded' : 'loaded',
  };
}

/** List cassettes tracked server-side, as CassetteEntry values (lastUsed normalised to an ISO string). */
export async function listServerCassettes(params: ConnectionParams, signal?: AbortSignal): Promise<CassetteEntry[]> {
  const res = await fetch(endpoint(params), { signal });
  if (!res.ok) await throwServerError(res);
  const body = (await res.json()) as { cassettes?: ServerCassette[] };
  return Array.isArray(body.cassettes) ? body.cassettes.map(toEntry) : [];
}

/** Register (or update) a cassette server-side. Best-effort — callers typically ignore failures. */
export async function registerServerCassette(
  params: ConnectionParams,
  entry: { path: string; filename?: string; expectationCount?: number; origin?: 'recorded' | 'loaded' },
): Promise<void> {
  const res = await fetch(endpoint(params), {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(entry),
  });
  if (!res.ok) await throwServerError(res);
}

/** Remove a cassette server-side by path. Best-effort. */
export async function deleteServerCassette(params: ConnectionParams, path: string): Promise<void> {
  const res = await fetch(`${endpoint(params)}?path=${encodeURIComponent(path)}`, { method: 'DELETE' });
  if (!res.ok) await throwServerError(res);
}
