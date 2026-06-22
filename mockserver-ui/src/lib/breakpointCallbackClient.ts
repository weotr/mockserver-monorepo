/**
 * WebSocket callback client for interactive breakpoint resolution.
 *
 * Opens a connection to `/_mockserver_callback_websocket` (the same endpoint
 * used by language clients for `forwardObject`/`responseObject` callbacks) and
 * reads its server-assigned `clientId` from the first `WebSocketClientIdDTO`
 * message. The browser WebSocket API does not support custom request headers,
 * so the server generates the clientId server-side when the
 * `CLIENT_REGISTRATION_ID_HEADER` is absent (already implemented in
 * `CallbackWebSocketServerHandler.upgradeChannel`).
 *
 * Messages from the server use the `{type, value}` envelope where `value` is a
 * double-encoded JSON string (the same as language clients). Three server message
 * types are handled:
 *
 * 1. `org.mockserver.model.HttpRequest` (REQUEST phase breakpoint)
 * 2. `org.mockserver.model.HttpRequestAndHttpResponse` (RESPONSE phase breakpoint)
 * 3. `org.mockserver.serialization.model.PausedStreamFrameDTO` (FRAME phase breakpoint)
 *
 * The client dispatches each message to a registered handler by breakpoint id
 * (extracted from `X-MockServer-BreakpointId` header for request/response, or from
 * `breakpointId` field for frames). The handler returns a resolution which is
 * serialized back over the WS.
 */

import type { ConnectionParams } from '../hooks/useConnectionParams';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

/** The `{type, value}` envelope used on the callback WebSocket. */
export interface WsEnvelope {
  type: string;
  value: string; // double-encoded JSON
}

/** Server-assigned clientId. */
export interface WebSocketClientIdDTO {
  clientId: string;
}

/** Paused request pushed for REQUEST-phase breakpoint. */
export interface PausedRequest {
  method?: string;
  path?: string;
  headers?: Record<string, string[]>;
  body?: unknown;
  [key: string]: unknown;
}

/** Paused request+response pushed for RESPONSE-phase breakpoint. */
export interface PausedRequestAndResponse {
  httpRequest?: PausedRequest;
  httpResponse?: {
    statusCode?: number;
    reasonPhrase?: string;
    headers?: Record<string, string[]>;
    body?: unknown;
    [key: string]: unknown;
  };
}

/** Paused stream frame pushed for RESPONSE_STREAM / INBOUND_STREAM breakpoint. */
export interface PausedStreamFrame {
  correlationId: string;
  streamId: string;
  sequenceNumber: number;
  direction: 'INBOUND' | 'OUTBOUND';
  phase: 'RESPONSE_STREAM' | 'INBOUND_STREAM';
  body: string; // Base64
  requestMethod?: string | null;
  requestPath?: string | null;
  breakpointId?: string | null;
  /** Epoch-millis when MockServer first received the originating request. */
  requestTimestamp?: number | null;
}

/** Resolution for a stream frame. */
export interface StreamFrameDecision {
  correlationId: string;
  action: 'CONTINUE' | 'MODIFY' | 'DROP' | 'INJECT' | 'CLOSE';
  body?: string | null; // Base64
}

export type BreakpointPhase = 'REQUEST' | 'RESPONSE' | 'RESPONSE_STREAM' | 'INBOUND_STREAM';

/**
 * Discriminated union for items pushed to the UI from the callback WS.
 * The `phase` field lets consumers determine what kind of item they're resolving.
 */
export type PausedItem =
  | { phase: 'REQUEST'; breakpointId: string | null; correlationId: string; requestTimestamp: number | null; request: PausedRequest }
  | { phase: 'RESPONSE'; breakpointId: string | null; correlationId: string; requestTimestamp: number | null; request: PausedRequest; response: PausedRequestAndResponse['httpResponse'] }
  | { phase: 'RESPONSE_STREAM' | 'INBOUND_STREAM'; breakpointId: string | null; frame: PausedStreamFrame };

/** Listener for paused items pushed over the callback WS. */
export type PausedItemListener = (item: PausedItem) => void;

/** Connection state. */
export type CallbackClientState = 'disconnected' | 'connecting' | 'connected';

/** State-change listener. */
export type StateListener = (state: CallbackClientState) => void;

// ---------------------------------------------------------------------------
// Type constants (Java class names used in the envelope `type` field)
// ---------------------------------------------------------------------------

const TYPE_HTTP_REQUEST = 'org.mockserver.model.HttpRequest';
const TYPE_HTTP_RESPONSE = 'org.mockserver.model.HttpResponse';
const TYPE_REQUEST_AND_RESPONSE = 'org.mockserver.model.HttpRequestAndHttpResponse';
const TYPE_CLIENT_ID = 'org.mockserver.serialization.model.WebSocketClientIdDTO';
const TYPE_PAUSED_FRAME = 'org.mockserver.serialization.model.PausedStreamFrameDTO';
const TYPE_FRAME_DECISION = 'org.mockserver.serialization.model.StreamFrameDecisionDTO';

const BREAKPOINT_ID_HEADER = 'X-MockServer-BreakpointId';
const CORRELATION_ID_HEADER = 'WebSocketCorrelationId';
const REQUEST_TIMESTAMP_HEADER = 'X-MockServer-RequestTimestamp';

// ---------------------------------------------------------------------------
// Helper
// ---------------------------------------------------------------------------

function extractHeader(headers: Record<string, string[]> | undefined, name: string): string | null {
  if (!headers) return null;
  // Headers may be stored case-insensitively
  for (const key of Object.keys(headers)) {
    if (key.toLowerCase() === name.toLowerCase()) {
      const vals = headers[key];
      return Array.isArray(vals) && vals.length > 0 ? vals[0]! : null;
    }
  }
  return null;
}

// ---------------------------------------------------------------------------
// Client
// ---------------------------------------------------------------------------

const RECONNECT_DELAY_MS = 3000;
const MAX_RECONNECT_ATTEMPTS = 20;

export class BreakpointCallbackClient {
  private ws: WebSocket | null = null;
  private _clientId: string | null = null;
  private _state: CallbackClientState = 'disconnected';
  private reconnectAttempts = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private connectionParams: ConnectionParams | null = null;

  private pausedItemListener: PausedItemListener | null = null;
  private stateListener: StateListener | null = null;

  /** The server-assigned clientId; null until connected. */
  get clientId(): string | null { return this._clientId; }

  /** Current connection state. */
  get state(): CallbackClientState { return this._state; }

  /** Register a listener that is called for every paused item. */
  onPausedItem(listener: PausedItemListener): void {
    this.pausedItemListener = listener;
  }

  /** Register a listener for connection state changes. */
  onStateChange(listener: StateListener): void {
    this.stateListener = listener;
  }

  /** Open the callback WS connection. Reconnects automatically on close. */
  connect(params: ConnectionParams): void {
    // Idempotent across UI re-mounts: if we are already connected (or connecting)
    // to the same server, keep the existing connection — and, crucially, its
    // server-assigned clientId and the breakpoint matchers registered under it —
    // alive. Tearing the socket down and reconnecting would assign a new clientId
    // and orphan those matchers (e.g. when navigating away from and back to the
    // Breakpoints tab).
    const sameServer =
      this.connectionParams !== null &&
      JSON.stringify(this.connectionParams) === JSON.stringify(params);
    if (sameServer && (this._state === 'connected' || this._state === 'connecting')) {
      return;
    }
    if (this.ws) {
      this.disconnect();
    }
    this.connectionParams = params;
    this.reconnectAttempts = 0;
    this._connect();
  }

  /** Close the WS connection and stop reconnecting. */
  disconnect(): void {
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    if (this.ws) {
      // Detach handlers before closing to avoid triggering reconnect
      const ws = this.ws;
      this.ws = null;
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
    this._clientId = null;
    this.setState('disconnected');
  }

  /**
   * Send a REQUEST-phase resolution over the WS.
   *
   * @param correlationId The WebSocketCorrelationId from the paused request.
   * @param result Either an HttpRequest (continue/modify) or an HttpResponse (abort).
   */
  resolveRequest(correlationId: string, result: Record<string, unknown>): void {
    const isResponse = result.statusCode !== undefined;
    const type = isResponse ? TYPE_HTTP_RESPONSE : TYPE_HTTP_REQUEST;
    // Ensure correlation id is echoed
    const headers = (result.headers ?? {}) as Record<string, string[]>;
    headers[CORRELATION_ID_HEADER] = [correlationId];
    const payload = { ...result, headers };
    this.send({ type, value: JSON.stringify(payload) });
  }

  /**
   * Send a RESPONSE-phase resolution over the WS.
   *
   * @param correlationId The WebSocketCorrelationId from the paused request+response.
   * @param httpResponse The response to write to the downstream client.
   */
  resolveResponse(correlationId: string, httpResponse: Record<string, unknown>): void {
    const headers = (httpResponse.headers ?? {}) as Record<string, string[]>;
    headers[CORRELATION_ID_HEADER] = [correlationId];
    const payload = { ...httpResponse, headers };
    this.send({ type: TYPE_HTTP_RESPONSE, value: JSON.stringify(payload) });
  }

  /**
   * Send a FRAME-phase resolution over the WS.
   */
  resolveFrame(decision: StreamFrameDecision): void {
    this.send({ type: TYPE_FRAME_DECISION, value: JSON.stringify(decision) });
  }

  // -------------------------------------------------------------------------
  // Private
  // -------------------------------------------------------------------------

  private _connect(): void {
    if (!this.connectionParams) return;
    const params = this.connectionParams;
    const protocol = params.secure ? 'wss' : 'ws';
    const url = `${protocol}://${params.host}:${params.port}${params.basePath ?? ''}/_mockserver_callback_websocket`;

    this.setState('connecting');
    const ws = new WebSocket(url);
    this.ws = ws;

    ws.onopen = () => {
      this.reconnectAttempts = 0;
      // We wait for the WebSocketClientIdDTO before declaring 'connected'
    };

    ws.onmessage = (event: MessageEvent) => {
      try {
        const envelope = JSON.parse(event.data as string) as WsEnvelope;
        this.handleMessage(envelope);
      } catch {
        // Ignore malformed messages
      }
    };

    ws.onclose = () => {
      this.ws = null;
      this._clientId = null;
      this.setState('disconnected');
      this.scheduleReconnect();
    };

    ws.onerror = () => {
      // onclose will fire after onerror
    };
  }

  private handleMessage(envelope: WsEnvelope): void {
    const { type, value } = envelope;
    if (!type || !value) return;

    if (type === TYPE_CLIENT_ID) {
      const dto = JSON.parse(value) as WebSocketClientIdDTO;
      this._clientId = dto.clientId;
      this.setState('connected');
      return;
    }

    if (type === TYPE_HTTP_REQUEST) {
      const request = JSON.parse(value) as PausedRequest;
      const breakpointId = extractHeader(request.headers, BREAKPOINT_ID_HEADER);
      const correlationId = extractHeader(request.headers, CORRELATION_ID_HEADER);
      const tsRaw = extractHeader(request.headers, REQUEST_TIMESTAMP_HEADER);
      const requestTimestamp = tsRaw != null ? Number(tsRaw) : null;
      if (correlationId) {
        this.pausedItemListener?.({
          phase: 'REQUEST',
          breakpointId,
          correlationId,
          requestTimestamp: requestTimestamp != null && !isNaN(requestTimestamp) ? requestTimestamp : null,
          request,
        });
      }
      return;
    }

    if (type === TYPE_REQUEST_AND_RESPONSE) {
      const pair = JSON.parse(value) as PausedRequestAndResponse;
      const breakpointId = extractHeader(pair.httpRequest?.headers, BREAKPOINT_ID_HEADER);
      const correlationId = extractHeader(pair.httpRequest?.headers, CORRELATION_ID_HEADER);
      const tsRaw = extractHeader(pair.httpRequest?.headers, REQUEST_TIMESTAMP_HEADER);
      const requestTimestamp = tsRaw != null ? Number(tsRaw) : null;
      if (correlationId) {
        this.pausedItemListener?.({
          phase: 'RESPONSE',
          breakpointId,
          correlationId,
          requestTimestamp: requestTimestamp != null && !isNaN(requestTimestamp) ? requestTimestamp : null,
          request: pair.httpRequest ?? {},
          response: pair.httpResponse,
        });
      }
      return;
    }

    if (type === TYPE_PAUSED_FRAME) {
      const frame = JSON.parse(value) as PausedStreamFrame;
      this.pausedItemListener?.({
        phase: frame.phase === 'INBOUND_STREAM' ? 'INBOUND_STREAM' : 'RESPONSE_STREAM',
        breakpointId: frame.breakpointId ?? null,
        frame,
      });
      return;
    }

    // Unknown type — ignore
  }

  private send(envelope: WsEnvelope): void {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(envelope));
    }
  }

  private scheduleReconnect(): void {
    this.reconnectAttempts++;
    if (this.reconnectAttempts > MAX_RECONNECT_ATTEMPTS) return;
    const delay = RECONNECT_DELAY_MS * Math.min(this.reconnectAttempts, 5);
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this._connect();
    }, delay);
  }

  private setState(state: CallbackClientState): void {
    if (this._state !== state) {
      this._state = state;
      this.stateListener?.(state);
    }
  }
}

// ---------------------------------------------------------------------------
// Singleton for the dashboard (one WS connection shared across the panel)
// ---------------------------------------------------------------------------

let _instance: BreakpointCallbackClient | null = null;

export function getBreakpointCallbackClient(): BreakpointCallbackClient {
  if (!_instance) {
    _instance = new BreakpointCallbackClient();
  }
  return _instance;
}

/** Reset the singleton (for tests). */
export function _resetBreakpointCallbackClient(): void {
  if (_instance) {
    _instance.disconnect();
    _instance = null;
  }
}
