/**
 * Client helpers for bulk-importing expectations into MockServer from various
 * formats:
 *
 * - **Expectation JSON**: `PUT /mockserver/expectation` (single or array)
 * - **HAR (HTTP Archive)** and **Postman collection**: `PUT /mockserver/import`
 *   with a `?format=har|postman` query parameter (raw collection JSON body).
 *
 * OpenAPI and WSDL have their own dedicated libs (openapiImport.ts, wsdlImport.ts).
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

/** Collection formats accepted by the PUT /mockserver/import endpoint. */
export type ImportCollectionFormat = 'har' | 'postman';

/**
 * Import raw expectation JSON (a single object or JSON array) via
 * `PUT /mockserver/expectation`. Returns the created expectations.
 *
 * @throws Error with the server's message on a non-2xx response.
 */
export async function importExpectationJson(
  params: ConnectionParams,
  jsonPayload: string,
): Promise<unknown[]> {
  const parsed: unknown = JSON.parse(jsonPayload);
  const body = Array.isArray(parsed) ? parsed : [parsed];
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/expectation`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    throw new Error((await res.text()) || `HTTP ${res.status} ${res.statusText}`);
  }
  const created = (await res.json()) as unknown;
  return Array.isArray(created) ? created : [];
}

/**
 * Import a recorded-traffic collection (HAR HTTP Archive or Postman collection)
 * via `PUT /mockserver/import?format=<format>`. The server converts the
 * collection into expectations and returns them (201).
 *
 * @throws Error with the server's message on a non-2xx response (e.g. 400 on
 *         parse failure or an unrecognised collection).
 */
export async function importCollection(
  params: ConnectionParams,
  payload: string,
  format: ImportCollectionFormat,
): Promise<unknown[]> {
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/import?format=${format}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: payload,
  });
  if (!res.ok) {
    throw new Error((await res.text()) || `HTTP ${res.status} ${res.statusText}`);
  }
  const created = (await res.json()) as unknown;
  return Array.isArray(created) ? created : [];
}
