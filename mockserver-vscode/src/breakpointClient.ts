// Callback-WebSocket client for the MockServer breakpoint debugger (Phase 5).
//
// This wires the pure protocol in breakpointProtocol.ts to a socket. The socket
// is injected via a `SocketFactory` so the client's message handling and
// lifecycle can be unit-tested with a fake socket — no real network. The default
// factory (used at runtime) builds a `ws` WebSocket.

import {
    CallbackEnvelope,
    PausedExchange,
    PausedStreamFrame,
    parseInboundMessage,
} from "./breakpointProtocol";

/** The minimal socket surface the client needs — satisfied by a `ws` WebSocket. */
export interface CallbackSocket {
    /** Send a UTF-8 text frame (the JSON-encoded reply envelope). */
    send(data: string): void;
    /** Close the connection. */
    close(): void;
    /** Register an event listener. Mirrors `ws`'s `on(event, cb)`. */
    on(event: "open" | "message" | "close" | "error", listener: (arg?: unknown) => void): void;
}

/** Builds a {@link CallbackSocket} for a `ws://host:port/_mockserver_callback_websocket` URL. */
export type SocketFactory = (url: string) => CallbackSocket;

/** Callbacks the panel registers to react to connection + protocol events. */
export interface BreakpointClientHandlers {
    /** The server-assigned clientId arrived (register matchers with it). */
    onClientId(clientId: string): void;
    /** A buffered REQUEST/RESPONSE exchange paused — show it for resolution. */
    onExchange(exchange: PausedExchange): void;
    /** A stream frame paused (Phase 7) — show it for resolution. */
    onStreamFrame(frame: PausedStreamFrame): void;
    /** Connection state changed (for the UI status dot). */
    onState(state: BreakpointConnectionState): void;
}

export type BreakpointConnectionState = "connecting" | "connected" | "closed" | "error";

/**
 * A live callback-WS client. Opens one connection, reads the server-assigned
 * clientId from the first message, dispatches paused exchanges/frames to the
 * handlers, and sends decision replies. Replies are built by the pure protocol
 * module so this class holds no protocol logic beyond routing.
 */
export class BreakpointClient {
    private socket: CallbackSocket | undefined;
    private clientId: string | undefined;
    private closedByUser = false;
    private errored = false;

    constructor(
        private readonly url: string,
        private readonly socketFactory: SocketFactory,
        private readonly handlers: BreakpointClientHandlers
    ) {}

    /** The server-assigned clientId once the registration message has arrived. */
    getClientId(): string | undefined {
        return this.clientId;
    }

    /** Open the connection and wire the event handlers. Idempotent-ish: re-opening replaces the socket. */
    connect(): void {
        this.closedByUser = false;
        this.errored = false;
        this.handlers.onState("connecting");
        const socket = this.socketFactory(this.url);
        this.socket = socket;
        socket.on("open", () => {
            this.errored = false;
            this.handlers.onState("connected");
        });
        socket.on("message", (data) => this.handleMessage(data));
        socket.on("error", () => {
            this.errored = true;
            this.handlers.onState("error");
        });
        socket.on("close", () => {
            // A `close` that immediately follows an `error` (e.g. connection
            // refused) would otherwise overwrite the more-informative "error"
            // state — suppress it so the UI shows the error, not a bare "closed".
            if (!this.closedByUser && !this.errored) {
                this.handlers.onState("closed");
            }
        });
    }

    /** Route one inbound text frame through the pure parser to the handlers. */
    handleMessage(data: unknown): void {
        const text = typeof data === "string" ? data : String(data ?? "");
        const parsed = parseInboundMessage(text);
        switch (parsed.kind) {
            case "clientId":
                this.clientId = parsed.clientId;
                this.handlers.onClientId(parsed.clientId);
                break;
            case "exchange":
                this.handlers.onExchange(parsed.exchange);
                break;
            case "streamFrame":
                this.handlers.onStreamFrame(parsed.frame);
                break;
            case "ignored":
                break;
        }
    }

    /**
     * Send a decision reply envelope (built by the pure protocol module). Returns
     * `false` when there is no live socket to send on (the caller should warn the
     * user; the server will auto-continue the exchange after its timeout).
     */
    reply(envelope: CallbackEnvelope): boolean {
        if (this.socket) {
            this.socket.send(JSON.stringify(envelope));
            return true;
        }
        return false;
    }

    /** Close the connection (user-initiated — suppresses the "closed" state event). */
    close(): void {
        this.closedByUser = true;
        this.handlers.onState("closed");
        if (this.socket) {
            this.socket.close();
            this.socket = undefined;
        }
    }
}

/**
 * Build the callback-WebSocket URL for a MockServer on `port`. Uses `ws://` and
 * localhost (the extension only ever talks to a local/configured MockServer —
 * nothing phones home). The path is the frozen callback endpoint.
 */
export function buildCallbackWsUrl(port: number): string {
    return `ws://localhost:${port}/_mockserver_callback_websocket`;
}
