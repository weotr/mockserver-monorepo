import { useCallback, useEffect, useRef } from 'react';
import type { ConnectionParams } from './useConnectionParams';
import type { ClearType, RequestFilter, WebSocketMessage } from '../types';
import { useDashboardStore } from '../store';
import { buildBaseUrl } from '../lib/mcpClient';

const RECONNECT_DELAY_MS = 3000;

/**
 * Close a WebSocket we're discarding (reconnect or unmount) without side effects. Calling
 * `close()` on a socket that is still CONNECTING makes the browser log a noisy
 * "WebSocket is closed before the connection is established" warning — which happens routinely
 * under React StrictMode's dev double-mount (connect, then immediate cleanup). We detach the
 * handlers first (so this intentional close neither updates state nor schedules a reconnect),
 * then close immediately if already open/closing, or defer the close until it finishes opening.
 */
function closeWebSocket(ws: WebSocket): void {
  ws.onmessage = null;
  ws.onerror = null;
  ws.onclose = null;
  if (ws.readyState === WebSocket.CONNECTING) {
    ws.onopen = () => ws.close();
  } else {
    ws.onopen = null;
    ws.close();
  }
}

export function useWebSocket(params: ConnectionParams) {
  const socketRef = useRef<WebSocket | null>(null);
  const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const reconnectCountRef = useRef(0);
  const lastFilterRef = useRef<RequestFilter>({});
  const connectRef = useRef<(filter: RequestFilter) => void>(() => {});

  const applyMessage = useDashboardStore((s) => s.applyMessage);
  const setConnectionStatus = useDashboardStore((s) => s.setConnectionStatus);
  const setError = useDashboardStore((s) => s.setError);

  const scheduleReconnect = useCallback(
    (filter: RequestFilter) => {
      // Keep retrying with a capped backoff rather than permanently giving up — a server that
      // is down longer than the first few attempts (a deploy/restart) should still reconnect
      // automatically once it comes back. onopen resets the counter and clears the error.
      reconnectCountRef.current += 1;
      // Surface the banner early (after the 2nd failed attempt, ~a few seconds) so a user pointed
      // at the wrong server isn't left guessing. Include the host/port so the cause is actionable —
      // they come from the ?host=/?port= URL params. onopen resets the counter and clears the error.
      if (reconnectCountRef.current === 2) {
        setError(`Connection lost to ${params.host}:${params.port} — retrying automatically. Check the server is running and the host/port (?host=&port= in the URL) are correct.`);
      }
      if (reconnectTimerRef.current) clearTimeout(reconnectTimerRef.current);
      const delay = RECONNECT_DELAY_MS * Math.min(reconnectCountRef.current, 5); // capped at 15s
      reconnectTimerRef.current = setTimeout(() => {
        connectRef.current(filter);
      }, delay);
    },
    [setError, params.host, params.port],
  );

  const connect = useCallback(
    (filter: RequestFilter) => {
      lastFilterRef.current = filter;
      if (reconnectTimerRef.current) {
        clearTimeout(reconnectTimerRef.current);
        reconnectTimerRef.current = null;
      }
      if (socketRef.current) {
        closeWebSocket(socketRef.current);
        socketRef.current = null;
      }

      setConnectionStatus('connecting');
      const protocol = params.secure ? 'wss' : 'ws';
      const url = `${protocol}://${params.host}:${params.port}${params.basePath ?? ''}/_mockserver_ui_websocket`;

      const ws = new WebSocket(url);
      socketRef.current = ws;

      ws.onopen = () => {
        reconnectCountRef.current = 0;
        setConnectionStatus('connected');
        setError(null);
        ws.send(JSON.stringify(filter));
      };

      ws.onmessage = (event: MessageEvent) => {
        try {
          const data = JSON.parse(event.data as string) as WebSocketMessage;
          applyMessage(data);
        } catch {
          setError('Failed to parse WebSocket message');
        }
      };

      ws.onclose = () => {
        setConnectionStatus('disconnected');
        socketRef.current = null;
        scheduleReconnect(filter);
      };

      ws.onerror = () => {
        setConnectionStatus('error');
      };
    },
    [params, applyMessage, setConnectionStatus, setError, scheduleReconnect],
  );

  useEffect(() => {
    connectRef.current = connect;
  }, [connect]);

  const sendFilter = useCallback(
    (filter: RequestFilter) => {
      lastFilterRef.current = filter;
      const ws = socketRef.current;
      if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify(filter));
      } else {
        connect(filter);
      }
    },
    [connect],
  );

  const disconnect = useCallback(() => {
    if (reconnectTimerRef.current) {
      clearTimeout(reconnectTimerRef.current);
      reconnectTimerRef.current = null;
    }
    if (socketRef.current) {
      closeWebSocket(socketRef.current);
      socketRef.current = null;
    }
    setConnectionStatus('disconnected');
  }, [setConnectionStatus]);

  const clearServer = useCallback(
    async (type: ClearType = 'all') => {
      const base = buildBaseUrl(params);
      try {
        const url =
          type === 'all'
            ? `${base}/mockserver/reset`
            : `${base}/mockserver/clear?type=${encodeURIComponent(type)}`;
        const response = await fetch(url, { method: 'PUT' });
        if (!response.ok) {
          setError(`Clear failed: ${response.status} ${response.statusText}`);
          return;
        }
        if (type === 'all') {
          useDashboardStore.getState().clearUI();
          connect(lastFilterRef.current);
        } else if (type === 'log') {
          // Only clear the log list locally — expectations and recorded requests
          // still exist server-side and should remain visible.
          useDashboardStore.setState({ logMessages: [] });
        } else if (type === 'expectations') {
          useDashboardStore.setState({ activeExpectations: [] });
        }
        const what = type === 'all' ? 'Server reset — all expectations, logs and recorded traffic cleared' : type === 'log' ? 'Server logs cleared' : 'Expectations cleared';
        useDashboardStore.getState().setNotification({ message: what, severity: 'success' });
      } catch {
        setError('Failed to clear server');
      }
    },
    [params, setError, connect],
  );

  useEffect(() => {
    return () => {
      disconnect();
    };
  }, [disconnect]);

  return { connect, disconnect, sendFilter, clearServer };
}
