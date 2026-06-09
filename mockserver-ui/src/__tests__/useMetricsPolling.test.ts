import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useMetricsPolling } from '../hooks/useMetricsPolling';

const params = { host: '127.0.0.1', port: '1080', secure: false };

function stubFetch(status: number, body = '') {
  return vi.fn(async () => ({
    status,
    ok: status >= 200 && status < 300,
    statusText: 'stub',
    text: async () => body,
  }));
}

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('useMetricsPolling', () => {
  it('sets status to disabled on 404', async () => {
    const fetchMock = stubFetch(404);
    vi.stubGlobal('fetch', fetchMock);

    const { result } = renderHook(() =>
      useMetricsPolling(params, { intervalMs: 5000 }),
    );

    // The initial poll fires immediately (async). Flush microtasks.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });

    expect(result.current.status).toBe('disabled');
    expect(result.current.error).toBeNull();
  });

  it('sets status to error on non-OK/non-404 response', async () => {
    const fetchMock = stubFetch(500, '');
    vi.stubGlobal('fetch', fetchMock);

    const { result } = renderHook(() =>
      useMetricsPolling(params, { intervalMs: 5000 }),
    );

    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });

    expect(result.current.status).toBe('error');
    expect(result.current.error).toContain('500');
  });

  it('sets status to error on network failure', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('network down')));

    const { result } = renderHook(() =>
      useMetricsPolling(params, { intervalMs: 5000 }),
    );

    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });

    expect(result.current.status).toBe('error');
    expect(result.current.error).toBe('network down');
  });

  it('accumulates history up to historySize then trims the oldest', async () => {
    let callCount = 0;
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        callCount++;
        return {
          status: 200,
          ok: true,
          statusText: 'OK',
          text: async () => `requests_received_count ${callCount}.0\n`,
        };
      }),
    );

    const { result } = renderHook(() =>
      useMetricsPolling(params, { intervalMs: 1000, historySize: 3 }),
    );

    // First poll
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(result.current.history).toHaveLength(1);

    // Second poll
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000);
    });
    expect(result.current.history).toHaveLength(2);

    // Third poll — at the cap
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000);
    });
    expect(result.current.history).toHaveLength(3);

    // Fourth poll — oldest should be trimmed
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000);
    });
    expect(result.current.history).toHaveLength(3);

    // The latest should be the most recent call
    expect(result.current.latest).not.toBeNull();
    expect(result.current.status).toBe('ok');
  });

  it('resets history when the server (baseUrl) changes', async () => {
    vi.stubGlobal('fetch', stubFetch(200, 'requests_received_count 1.0\n'));

    const { result, rerender } = renderHook(
      ({ p }) => useMetricsPolling(p, { intervalMs: 5000 }),
      { initialProps: { p: params } },
    );

    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(result.current.history).toHaveLength(1);

    // Change the server
    rerender({ p: { host: '10.0.0.1', port: '2080', secure: false } });

    // History should be cleared
    expect(result.current.history).toHaveLength(0);
    expect(result.current.status).toBe('loading');
  });

  it('returns the last snapshot as latest', async () => {
    vi.stubGlobal('fetch', stubFetch(200, 'requests_received_count 42.0\n'));

    const { result } = renderHook(() =>
      useMetricsPolling(params, { intervalMs: 5000 }),
    );

    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });

    expect(result.current.latest).not.toBeNull();
    expect(result.current.latest!.samples.length).toBeGreaterThan(0);
  });

  it('polls repeatedly on the configured interval', async () => {
    const fetchMock = stubFetch(200, 'requests_received_count 1.0\n');
    vi.stubGlobal('fetch', fetchMock);

    renderHook(() => useMetricsPolling(params, { intervalMs: 2000 }));

    // Initial poll
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(fetchMock).toHaveBeenCalledTimes(1);

    // Second poll after intervalMs
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });
    expect(fetchMock).toHaveBeenCalledTimes(2);

    // Third poll
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it('stops polling on unmount', async () => {
    const fetchMock = stubFetch(200, 'requests_received_count 1.0\n');
    vi.stubGlobal('fetch', fetchMock);

    const { unmount } = renderHook(() =>
      useMetricsPolling(params, { intervalMs: 2000 }),
    );

    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(fetchMock).toHaveBeenCalledTimes(1);

    unmount();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(10000);
    });

    // No additional calls after unmount
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
