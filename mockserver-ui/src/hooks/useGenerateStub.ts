import { useCallback } from 'react';
import { useDashboardStore } from '../store';
import type { ConnectionParams } from './useConnectionParams';
import { generateExpectation } from '../lib/generateStub';

export function useGenerateStub(params: ConnectionParams) {
  const openGenerateStub = useDashboardStore((s) => s.openGenerateStub);
  const setLoading = useDashboardStore((s) => s.setGenerateStubLoading);
  const setError = useDashboardStore((s) => s.setGenerateStubError);

  const generateStub = useCallback(
    async (request: Record<string, unknown>) => {
      setLoading(true);
      useDashboardStore.setState({ generateStubOpen: true });
      try {
        const result = await generateExpectation(params, request, true);
        openGenerateStub(result.suggestions, result.confidence);
      } catch (e) {
        setError(e instanceof Error ? e.message : 'Failed to generate expectation');
      }
    },
    [params, openGenerateStub, setLoading, setError],
  );

  return { generateStub };
}
