import { useCallback, useEffect, useState } from 'react';
import type { ConnectionParams } from './useConnectionParams';
import { buildBaseUrl } from '../lib/mcpClient';
import { parsePrometheusText } from '../lib/prometheusParser';
import type { MetricsSnapshot } from '../lib/metricsDerive';

export type MetricsStatus = 'loading' | 'ok' | 'disabled' | 'error';

export interface MetricsPollingResult {
  status: MetricsStatus;
  /** Rolling window of scraped snapshots (oldest first), capped at historySize. */
  history: MetricsSnapshot[];
  latest: MetricsSnapshot | null;
  error: string | null;
  intervalMs: number;
  /** Force an immediate re-scrape. */
  refresh: () => void;
}

export interface MetricsPollingOptions {
  intervalMs?: number;
  historySize?: number;
}

/**
 * Polls `GET {baseUrl}/mockserver/metrics` on an interval, parses the
 * Prometheus text, and keeps a rolling history so the view can render
 * client-derived time series. A 404 means MockServer was started without
 * `metricsEnabled` — surfaced as `status: 'disabled'` so the view can guide
 * the user rather than show an error.
 */
export function useMetricsPolling(
  params: ConnectionParams,
  options: MetricsPollingOptions = {},
): MetricsPollingResult {
  const intervalMs = options.intervalMs ?? 5000;
  const historySize = options.historySize ?? 60;
  const baseUrl = buildBaseUrl(params);

  const [status, setStatus] = useState<MetricsStatus>('loading');
  const [history, setHistory] = useState<MetricsSnapshot[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [refreshTick, setRefreshTick] = useState(0);

  const refresh = useCallback(() => setRefreshTick((t) => t + 1), []);

  // Reset history when the target server changes so stale deltas from a
  // previous instance never bleed into the new one's rate calculations. This
  // is React's "adjust state while rendering" pattern (a setState during render
  // is allowed and avoids the extra commit an effect would cause).
  const [prevBaseUrl, setPrevBaseUrl] = useState(baseUrl);
  if (prevBaseUrl !== baseUrl) {
    setPrevBaseUrl(baseUrl);
    setHistory([]);
    setStatus('loading');
  }

  useEffect(() => {
    let cancelled = false;
    let polling = false;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;

    const isHidden = () => typeof document !== 'undefined' && document.hidden;

    function scheduleNext(): void {
      // Pause scraping while the tab is hidden — a background Metrics view
      // otherwise keeps scraping and re-parsing Prometheus text for nothing.
      if (cancelled || isHidden()) return;
      timer = setTimeout(() => void poll(), intervalMs);
    }

    async function poll(): Promise<void> {
      polling = true;
      try {
        const res = await fetch(`${baseUrl}/mockserver/metrics`, { signal: controller.signal });
        if (cancelled) return;
        if (res.status === 404) {
          setStatus('disabled');
          setError(null);
        } else if (!res.ok) {
          setStatus('error');
          setError(`HTTP ${res.status} ${res.statusText}`);
        } else {
          const text = await res.text();
          if (cancelled) return;
          const snapshot: MetricsSnapshot = { at: Date.now(), samples: parsePrometheusText(text) };
          setHistory((prev) => {
            const next = [...prev, snapshot];
            return next.length > historySize ? next.slice(next.length - historySize) : next;
          });
          setStatus('ok');
          setError(null);
        }
      } catch (e) {
        if (cancelled || controller.signal.aborted) return;
        setStatus('error');
        setError(e instanceof Error ? e.message : String(e));
      } finally {
        polling = false;
        scheduleNext();
      }
    }

    function onVisibilityChange(): void {
      if (cancelled) return;
      if (isHidden()) {
        // Cancel the pending tick so scraping pauses immediately on hide.
        if (timer) { clearTimeout(timer); timer = undefined; }
        return;
      }
      // Resuming: skip if a scrape is already in flight — it reschedules itself
      // on completion, so launching another here would fork a duplicate loop.
      if (polling) return;
      if (timer) clearTimeout(timer);
      void poll();
    }

    void poll();
    if (typeof document !== 'undefined') {
      document.addEventListener('visibilitychange', onVisibilityChange);
    }
    return () => {
      cancelled = true;
      controller.abort();
      if (timer) clearTimeout(timer);
      if (typeof document !== 'undefined') {
        document.removeEventListener('visibilitychange', onVisibilityChange);
      }
    };
  }, [baseUrl, intervalMs, historySize, refreshTick]);

  const latest = history.length > 0 ? (history[history.length - 1] ?? null) : null;
  return { status, history, latest, error, intervalMs, refresh };
}
