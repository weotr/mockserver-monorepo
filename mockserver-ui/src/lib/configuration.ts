/**
 * Client for MockServer's live configuration control plane (GET/PUT /mockserver/configuration).
 * GET returns the full configuration; PUT applies a partial ConfigurationDTO (only the supplied
 * fields are changed), so the dashboard can tweak a setting like the log level at runtime.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

export type Configuration = Record<string, unknown>;

export async function getConfiguration(params: ConnectionParams, signal?: AbortSignal): Promise<Configuration> {
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/configuration`, { signal });
  if (!res.ok) throw new Error(`Failed to load configuration (HTTP ${res.status} ${res.statusText})`);
  return (await res.json()) as Configuration;
}

/** Apply a partial configuration change (only the supplied keys are modified server-side). */
export async function updateConfiguration(params: ConnectionParams, partial: Configuration): Promise<void> {
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/configuration`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(partial),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(text || `Failed to update configuration (HTTP ${res.status} ${res.statusText})`);
  }
}

export const LOG_LEVELS = ['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR', 'OFF'] as const;
