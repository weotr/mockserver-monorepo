import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { usePolling } from '../hooks/usePolling';

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  // Reset visibility to the jsdom default (visible).
  setHidden(false);
});

function setHidden(hidden: boolean) {
  Object.defineProperty(document, 'hidden', { value: hidden, configurable: true });
}

async function flush() {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0);
  });
}

describe('usePolling visibility gating', () => {
  it('polls on the interval while visible', async () => {
    const fetcher = vi.fn(async () => 'ok');
    renderHook(() => usePolling({ fetcher, intervalMs: 1000 }));

    await flush(); // initial poll
    expect(fetcher).toHaveBeenCalledTimes(1);

    await act(async () => { await vi.advanceTimersByTimeAsync(1000); });
    expect(fetcher).toHaveBeenCalledTimes(2);
  });

  it('pauses scheduling while the tab is hidden, then resumes once on return', async () => {
    const fetcher = vi.fn(async () => 'ok');
    renderHook(() => usePolling({ fetcher, intervalMs: 1000 }));

    await flush(); // initial poll (1)
    expect(fetcher).toHaveBeenCalledTimes(1);

    // Hide the tab before the next tick is scheduled — no further polls.
    setHidden(true);
    document.dispatchEvent(new Event('visibilitychange'));
    await act(async () => { await vi.advanceTimersByTimeAsync(5000); });
    expect(fetcher).toHaveBeenCalledTimes(1);

    // Returning to the tab triggers exactly one immediate resume poll...
    setHidden(false);
    await act(async () => {
      document.dispatchEvent(new Event('visibilitychange'));
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(fetcher).toHaveBeenCalledTimes(2);

    // ...and the loop continues at a single (not doubled) cadence afterwards.
    await act(async () => { await vi.advanceTimersByTimeAsync(1000); });
    expect(fetcher).toHaveBeenCalledTimes(3);
  });

  it('does not fork a duplicate loop when the tab is hidden then shown during an in-flight fetch', async () => {
    // Hold the first fetch open so we can flip visibility mid-flight.
    let resolveFirst!: (v: string) => void;
    const fetcher = vi
      .fn()
      .mockImplementationOnce(() => new Promise<string>((r) => { resolveFirst = r; }))
      .mockImplementation(async () => 'ok');

    renderHook(() => usePolling({ fetcher, intervalMs: 1000 }));
    await flush(); // initial poll starts but does not resolve
    expect(fetcher).toHaveBeenCalledTimes(1);

    // Hide then show while the first fetch is still in flight — the in-flight
    // poll owns rescheduling, so the visible event must NOT launch a second one.
    setHidden(true);
    document.dispatchEvent(new Event('visibilitychange'));
    setHidden(false);
    document.dispatchEvent(new Event('visibilitychange'));
    expect(fetcher).toHaveBeenCalledTimes(1);

    // Resolve the first fetch; exactly one scheduled timer should drive the loop.
    await act(async () => { resolveFirst('ok'); await vi.advanceTimersByTimeAsync(0); });
    await act(async () => { await vi.advanceTimersByTimeAsync(1000); });
    expect(fetcher).toHaveBeenCalledTimes(2); // not 3 — no doubled loop
  });
});
