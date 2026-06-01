/**
 * Client for MockServer's WSDL import endpoint (`PUT /mockserver/wsdl`).
 * Converts a WSDL 1.1 document (SOAP 1.1/1.2) into expectations and returns
 * the created expectations.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

/**
 * Import a WSDL document. Returns the list of created expectations (as the
 * server's expectation JSON objects).
 *
 * @throws Error with the server's message on a non-2xx response (e.g. a WSDL
 *         that cannot be parsed or declares no SOAP operations).
 */
export async function importWsdl(
  params: ConnectionParams,
  wsdl: string,
): Promise<unknown[]> {
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/wsdl`, {
    method: 'PUT',
    headers: { 'Content-Type': 'text/xml' },
    body: wsdl,
  });
  if (!res.ok) {
    throw new Error((await res.text()) || `HTTP ${res.status} ${res.statusText}`);
  }
  const created = (await res.json()) as unknown;
  return Array.isArray(created) ? created : [];
}
