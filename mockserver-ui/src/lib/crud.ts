/**
 * Client for MockServer's PUT /mockserver/crud — registers auto-CRUD resource
 * expectations (GET/POST/PUT/DELETE for a given basePath with automatic ID management).
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

export interface CrudConfig {
  basePath: string;
  idField?: string;
  idStrategy?: 'AUTO_INCREMENT' | 'UUID';
  initialData?: unknown[];
}

export interface CrudResult {
  basePath: string;
  idField: string;
  idStrategy: string;
  itemCount: number;
}

/**
 * Register auto-CRUD expectations for the given basePath.
 * Returns the server's confirmation including the item count.
 */
export async function registerCrudResource(params: ConnectionParams, config: CrudConfig): Promise<CrudResult> {
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/crud`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(config),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(text || `Failed to register CRUD resource (HTTP ${res.status} ${res.statusText})`);
  }
  return (await res.json()) as CrudResult;
}
