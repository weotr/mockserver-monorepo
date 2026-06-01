/**
 * Client for MockServer's OpenAPI import endpoint (`PUT /mockserver/openapi`).
 * Generates expectations from an OpenAPI v3 spec (URL or inline JSON/YAML).
 * Import is incremental: re-importing the same spec (by title) updates the
 * generated expectations in place and prunes operations no longer present.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

/**
 * Import an OpenAPI spec. `specOrUrl` may be a URL or an inline JSON/YAML spec.
 * Returns the list of created/updated expectations.
 *
 * @throws Error with the server's message on a non-2xx response.
 */
export async function importOpenApi(
  params: ConnectionParams,
  specOrUrl: string,
): Promise<unknown[]> {
  const body = JSON.stringify([{ specUrlOrPayload: specOrUrl }]);
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/openapi`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body,
  });
  if (!res.ok) {
    throw new Error((await res.text()) || `HTTP ${res.status} ${res.statusText}`);
  }
  const created = (await res.json()) as unknown;
  return Array.isArray(created) ? created : [];
}
