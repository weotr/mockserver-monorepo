/**
 * Client for MockServer's file store endpoints:
 *   PUT /mockserver/files/list     -> JSON array of file-name strings.
 *   PUT /mockserver/files/store    -> store a file ({ name, content, base64? }).
 *   PUT /mockserver/files/retrieve -> retrieve a file's raw content by name.
 *   PUT /mockserver/files/delete   -> delete a file by name.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

/**
 * Build the standard `MockServer returned <status>: <body>` error message so
 * thrown errors are mapped by {@link humanizeError}/{@link humanizeServerError}.
 */
async function serverError(res: Response): Promise<string> {
  const body = await res.text().catch(() => '');
  return `MockServer returned ${res.status}: ${body}`;
}

/** List all file names in the server file store. */
export async function listFiles(params: ConnectionParams, signal?: AbortSignal): Promise<string[]> {
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/files/list`, { method: 'PUT', signal });
  if (!res.ok) throw new Error(await serverError(res));
  const body: unknown = await res.json().catch(() => []);
  return Array.isArray(body) ? (body as string[]) : [];
}

/** Store a file. When base64 is true the server base64-decodes content before storing. */
export async function storeFile(
  params: ConnectionParams,
  file: { name: string; content: string; base64?: boolean },
): Promise<{ name: string; size: number }> {
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/files/store`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(file),
  });
  if (!res.ok) throw new Error(await serverError(res));
  return (await res.json()) as { name: string; size: number };
}

/** Delete a file by name. */
export async function deleteFile(params: ConnectionParams, name: string): Promise<void> {
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/files/delete`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name }),
  });
  if (!res.ok) throw new Error(await serverError(res));
}

/** Retrieve a file's content as text. */
export async function retrieveFileText(params: ConnectionParams, name: string): Promise<string> {
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/files/retrieve`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name }),
  });
  if (!res.ok) throw new Error(await serverError(res));
  return res.text();
}
