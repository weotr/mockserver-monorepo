/**
 * Client for MockServer's WASM custom rule module endpoints
 * (`/mockserver/wasm/modules`). Upload, list, and delete WASM modules.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

function endpoint(params: ConnectionParams): string {
  return `${buildBaseUrl(params)}/mockserver/wasm/modules`;
}

/** List all loaded WASM module names. */
export async function listWasmModules(
  params: ConnectionParams,
  signal?: AbortSignal,
): Promise<string[]> {
  const res = await fetch(endpoint(params), { signal });
  if (!res.ok) {
    throw new Error(`HTTP ${res.status} ${res.statusText}`);
  }
  const body = await res.json();
  return Array.isArray(body) ? body : [];
}

/** Upload a WASM module with the given name. */
export async function uploadWasmModule(
  params: ConnectionParams,
  name: string,
  fileBytes: ArrayBuffer,
): Promise<void> {
  const url = `${endpoint(params)}?name=${encodeURIComponent(name)}`;
  const res = await fetch(url, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/octet-stream' },
    body: fileBytes,
  });
  if (!res.ok) {
    let message = `HTTP ${res.status} ${res.statusText}`;
    try {
      message = await res.text();
    } catch {
      // keep status-line message
    }
    throw new Error(message);
  }
}

/** Delete a WASM module by name. */
export async function deleteWasmModule(
  params: ConnectionParams,
  name: string,
): Promise<void> {
  const url = `${endpoint(params)}?name=${encodeURIComponent(name)}`;
  const res = await fetch(url, { method: 'DELETE' });
  if (!res.ok) {
    let message = `HTTP ${res.status} ${res.statusText}`;
    try {
      message = await res.text();
    } catch {
      // keep status-line message
    }
    throw new Error(message);
  }
}
