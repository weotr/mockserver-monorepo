import { useCallback } from 'react';
import { useDashboardStore } from '../store';
import type { ConnectionParams } from './useConnectionParams';
import type { DebugMismatchResult } from '../types';
import { buildBaseUrl } from '../lib/mcpClient';

export function useDebugMismatch(params: ConnectionParams) {
  const openDebugMismatch = useDashboardStore((s) => s.openDebugMismatch);
  const setLoading = useDashboardStore((s) => s.setDebugMismatchLoading);
  const setError = useDashboardStore((s) => s.setDebugMismatchError);

  const debugMismatch = useCallback(
    async (request: Record<string, unknown>) => {
      setLoading(true);
      useDashboardStore.setState({ debugMismatchOpen: true });
      const base = buildBaseUrl(params);
      try {
        const response = await fetch(`${base}/mockserver/debugMismatch`, {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(request),
        });
        const data = (await response.json()) as DebugMismatchResult & { error?: string };
        if (!response.ok) {
          setError(data.error ?? `Request failed: ${response.status}`);
          return;
        }
        openDebugMismatch(data);
      } catch {
        setError('Failed to connect to MockServer');
      }
    },
    [params, openDebugMismatch, setLoading, setError],
  );

  return { debugMismatch };
}
