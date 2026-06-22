// Pure, `vscode`-free message handling for MockServer's breakpoint callback
// WebSocket protocol (`/_mockserver_callback_websocket`). This is the FROZEN
// contract documented in docs/code/breakpoints.md and implemented identically
// by the Node/Java/dashboard clients (see mockserver-client-node/webSocketClient.js).
//
// Everything here is a pure function over plain objects so the protocol logic can
// be unit-tested without a live socket or the `vscode` API. The thin socket wiring
// lives in breakpointClient.ts; the UI lives in breakpointPanel.ts.

/** JSON-RPC-less `{type, value}` envelope used on the callback WebSocket. */
export interface CallbackEnvelope {
    /** Fully-qualified server DTO type, e.g. `org.mockserver.model.HttpRequest`. */
    type: string;
    /** The payload, JSON-encoded as a string. */
    value: string;
}

// Frozen DTO type strings (must match the server / other clients byte-for-byte).
export const TYPE_CLIENT_ID = "org.mockserver.serialization.model.WebSocketClientIdDTO";
export const TYPE_HTTP_REQUEST = "org.mockserver.model.HttpRequest";
export const TYPE_HTTP_RESPONSE = "org.mockserver.model.HttpResponse";
export const TYPE_REQUEST_AND_RESPONSE = "org.mockserver.model.HttpRequestAndHttpResponse";
export const TYPE_PAUSED_STREAM_FRAME = "org.mockserver.serialization.model.PausedStreamFrameDTO";
export const TYPE_STREAM_FRAME_DECISION = "org.mockserver.serialization.model.StreamFrameDecisionDTO";

// Header names carrying the breakpoint id and the correlation id on the
// dispatched request (canonical and lowercase forms both occur on the wire).
const BREAKPOINT_ID_HEADERS = ["X-MockServer-BreakpointId", "x-mockserver-breakpointid"];
const CORRELATION_ID_HEADERS = ["WebSocketCorrelationId", "websocketcorrelationid"];
const REQUEST_TIMESTAMP_HEADERS = ["X-MockServer-RequestTimestamp", "x-mockserver-requesttimestamp"];

/** A header map as MockServer serialises it: name → string | string[]. */
export type HeaderMap = Record<string, string | string[] | undefined>;

/** The (single) string value of a header, picking the first of an array form. */
function firstHeaderValue(headers: HeaderMap | undefined, names: string[]): string | null {
    if (!headers) {
        return null;
    }
    for (const key of Object.keys(headers)) {
        if (names.includes(key)) {
            const raw = headers[key];
            const value = Array.isArray(raw) ? raw[0] : raw;
            return typeof value === "string" ? value : null;
        }
    }
    return null;
}

/**
 * Pull the breakpoint id, correlation id, and request timestamp from a dispatched
 * request's headers. For REQUEST/RESPONSE phases the server conveys these as
 * headers on the `HttpRequest` (per breakpoints.md "Resolution protocol").
 */
export function extractBreakpointHeaders(headers: HeaderMap | undefined): {
    breakpointId: string | null;
    correlationId: string | null;
    requestTimestamp: number | null;
} {
    const breakpointId = firstHeaderValue(headers, BREAKPOINT_ID_HEADERS);
    const correlationId = firstHeaderValue(headers, CORRELATION_ID_HEADERS);
    const tsRaw = firstHeaderValue(headers, REQUEST_TIMESTAMP_HEADERS);
    const ts = tsRaw !== null ? Number(tsRaw) : NaN;
    return {
        breakpointId,
        correlationId,
        requestTimestamp: Number.isFinite(ts) ? ts : null,
    };
}

/** Set the correlation-id header on a request/response object so the reply correlates. */
export function withCorrelationId<T extends { headers?: HeaderMap }>(
    obj: T,
    correlationId: string | null
): T {
    if (!correlationId) {
        return obj;
    }
    if (!obj.headers) {
        obj.headers = {};
    }
    obj.headers["WebSocketCorrelationId"] = [correlationId];
    return obj;
}

/** Phase of a paused buffered exchange. */
export type BufferedPhase = "REQUEST" | "RESPONSE";

/** A paused buffered exchange (REQUEST or RESPONSE phase) ready for inspection. */
export interface PausedExchange {
    phase: BufferedPhase;
    /** Correlation id that the decision reply MUST echo. */
    correlationId: string | null;
    /** The breakpoint matcher id that paused this exchange (when present). */
    breakpointId: string | null;
    /** Epoch-millis the originating request was first received (when present). */
    requestTimestamp: number | null;
    /** The paused request object. */
    httpRequest: Record<string, unknown>;
    /** The upstream/mocked response object — RESPONSE phase only. */
    httpResponse?: Record<string, unknown>;
}

/**
 * Parse an inbound callback envelope into either a client-id registration, a
 * paused buffered exchange (REQUEST/RESPONSE), a paused stream frame, or `null`
 * for anything unrecognised. Pure: never touches a socket.
 */
export function parseInboundMessage(
    raw: string
):
    | { kind: "clientId"; clientId: string }
    | { kind: "exchange"; exchange: PausedExchange }
    | { kind: "streamFrame"; frame: PausedStreamFrame }
    | { kind: "ignored" } {
    let envelope: CallbackEnvelope;
    try {
        envelope = JSON.parse(raw) as CallbackEnvelope;
    } catch {
        return { kind: "ignored" };
    }
    if (!envelope || typeof envelope.type !== "string") {
        return { kind: "ignored" };
    }

    if (envelope.type === TYPE_CLIENT_ID) {
        try {
            const reg = JSON.parse(envelope.value) as { clientId?: string };
            if (reg && typeof reg.clientId === "string") {
                return { kind: "clientId", clientId: reg.clientId };
            }
        } catch {
            /* fall through to ignored */
        }
        return { kind: "ignored" };
    }

    if (envelope.type === TYPE_HTTP_REQUEST) {
        const request = safeParseObject(envelope.value);
        if (!request) {
            return { kind: "ignored" };
        }
        const { breakpointId, correlationId, requestTimestamp } = extractBreakpointHeaders(
            request.headers as HeaderMap | undefined
        );
        return {
            kind: "exchange",
            exchange: { phase: "REQUEST", correlationId, breakpointId, requestTimestamp, httpRequest: request },
        };
    }

    if (envelope.type === TYPE_REQUEST_AND_RESPONSE) {
        const pair = safeParseObject(envelope.value);
        if (!pair) {
            return { kind: "ignored" };
        }
        const httpRequest = (pair.httpRequest as Record<string, unknown>) ?? {};
        const httpResponse = (pair.httpResponse as Record<string, unknown>) ?? {};
        const { breakpointId, correlationId, requestTimestamp } = extractBreakpointHeaders(
            httpRequest.headers as HeaderMap | undefined
        );
        return {
            kind: "exchange",
            exchange: {
                phase: "RESPONSE",
                correlationId,
                breakpointId,
                requestTimestamp,
                httpRequest,
                httpResponse,
            },
        };
    }

    if (envelope.type === TYPE_PAUSED_STREAM_FRAME) {
        const frame = safeParseObject(envelope.value);
        if (!frame) {
            return { kind: "ignored" };
        }
        return { kind: "streamFrame", frame: frame as unknown as PausedStreamFrame };
    }

    return { kind: "ignored" };
}

function safeParseObject(value: string): Record<string, unknown> | null {
    try {
        const parsed = JSON.parse(value);
        return parsed && typeof parsed === "object" && !Array.isArray(parsed)
            ? (parsed as Record<string, unknown>)
            : null;
    } catch {
        return null;
    }
}

/** A buffered-exchange resolution decision the user makes in the debug pane. */
export type BufferedDecision =
    | { action: "CONTINUE" }
    | { action: "MODIFY"; replacement: Record<string, unknown> }
    | { action: "ABORT"; response: Record<string, unknown> };

/**
 * Build the reply envelope for a paused REQUEST-phase exchange.
 *
 * Per breakpoints.md "REQUEST phase reply semantics":
 *  - replying with an `HttpRequest` means CONTINUE (original) or MODIFY (replacement);
 *  - replying with an `HttpResponse` means ABORT (write that response, do not forward).
 * The correlation id is echoed onto the replied object so the server matches it.
 */
export function buildRequestPhaseReply(
    exchange: PausedExchange,
    decision: BufferedDecision
): CallbackEnvelope {
    if (decision.action === "ABORT") {
        const response = withCorrelationId({ ...decision.response }, exchange.correlationId);
        return { type: TYPE_HTTP_RESPONSE, value: JSON.stringify(response) };
    }
    const request =
        decision.action === "MODIFY"
            ? { ...decision.replacement }
            : { ...exchange.httpRequest };
    withCorrelationId(request, exchange.correlationId);
    // A MODIFY whose replacement carries a statusCode is, by the frozen protocol,
    // an HttpResponse → ABORT. Mirror the Node client's discrimination so a user
    // who edits a request into a response shape still gets abort semantics.
    const isResponseShape = typeof (request as { statusCode?: unknown }).statusCode !== "undefined";
    return {
        type: isResponseShape ? TYPE_HTTP_RESPONSE : TYPE_HTTP_REQUEST,
        value: JSON.stringify(request),
    };
}

/** A RESPONSE-phase decision — ABORT is excluded (it is REQUEST-phase only). */
export type ResponseDecision =
    | { action: "CONTINUE" }
    | { action: "MODIFY"; replacement: Record<string, unknown> };

/**
 * Build the reply envelope for a paused RESPONSE-phase exchange.
 *
 * Per breakpoints.md "RESPONSE phase reply semantics": the client replies with an
 * `HttpResponse` and the server writes it downstream (CONTINUE = original,
 * MODIFY = replacement). ABORT is NOT a RESPONSE-phase action — it is rejected at
 * compile time via {@link ResponseDecision} and at runtime as a defensive guard.
 */
export function buildResponsePhaseReply(
    exchange: PausedExchange,
    decision: BufferedDecision
): CallbackEnvelope {
    if (decision.action === "ABORT") {
        throw new Error("ABORT is not a valid RESPONSE-phase decision (REQUEST phase only).");
    }
    const response =
        decision.action === "MODIFY"
            ? { ...decision.replacement }
            : { ...(exchange.httpResponse ?? {}) };
    withCorrelationId(response, exchange.correlationId);
    return { type: TYPE_HTTP_RESPONSE, value: JSON.stringify(response) };
}

/** Build the correct reply envelope for whichever phase the exchange is in. */
export function buildDecisionReply(
    exchange: PausedExchange,
    decision: BufferedDecision
): CallbackEnvelope {
    return exchange.phase === "REQUEST"
        ? buildRequestPhaseReply(exchange, decision)
        : buildResponsePhaseReply(exchange, decision);
}

/** Whether ABORT is a valid decision for a phase (REQUEST only — see breakpoints.md). */
export function abortAllowed(phase: BufferedPhase): boolean {
    return phase === "REQUEST";
}

// ---------------------------------------------------------------------------
// Stream-frame phase (Phase 7) — RESPONSE_STREAM / INBOUND_STREAM
// ---------------------------------------------------------------------------

/** A paused stream frame (PausedStreamFrameDTO) — the frozen server-to-client shape. */
export interface PausedStreamFrame {
    correlationId: string;
    streamId: string;
    sequenceNumber: number;
    direction: "INBOUND" | "OUTBOUND";
    phase: "RESPONSE_STREAM" | "INBOUND_STREAM";
    /** Frame payload, Base64-encoded. */
    body: string;
    requestMethod?: string | null;
    requestPath?: string | null;
    breakpointId?: string | null;
    requestTimestamp?: number | null;
}

/** Per-frame resolution actions (StreamFrameDecisionDTO.action). */
export type StreamFrameAction = "CONTINUE" | "MODIFY" | "DROP" | "INJECT" | "CLOSE";

/** A per-frame decision the user makes in the Live Streams pane. */
export interface StreamFrameDecisionInput {
    action: StreamFrameAction;
    /** Base64-encoded replacement/injected bytes — required for MODIFY and INJECT. */
    body?: string;
}

/**
 * Build the `StreamFrameDecisionDTO` reply envelope for a paused frame. The
 * `correlationId` is always echoed from the frame. `body` is only included for
 * MODIFY/INJECT (the actions that carry replacement/extra bytes).
 */
export function buildStreamFrameReply(
    frame: PausedStreamFrame,
    decision: StreamFrameDecisionInput
): CallbackEnvelope {
    const needsBody = decision.action === "MODIFY" || decision.action === "INJECT";
    if (needsBody && typeof decision.body !== "string") {
        // The frozen contract requires `body` (Base64) for MODIFY and INJECT.
        // Reject rather than silently emit a body-less DTO the server can't apply.
        throw new Error(`A Base64 body is required for stream-frame ${decision.action}.`);
    }
    const reply: { correlationId: string; action: StreamFrameAction; body?: string } = {
        correlationId: frame.correlationId,
        action: decision.action,
    };
    if (needsBody && typeof decision.body === "string") {
        reply.body = decision.body;
    }
    return { type: TYPE_STREAM_FRAME_DECISION, value: JSON.stringify(reply) };
}

/**
 * Build the breakpoint-matcher registration body for
 * `PUT /mockserver/breakpoint/matcher`. `httpRequest` and `phases` are required;
 * `clientId` is required by the server (the callback WS client this matcher
 * dispatches to); `skipCount` is included only when a positive Nth-hit value.
 */
export function buildMatcherRegistrationBody(
    httpRequest: Record<string, unknown>,
    phases: string[],
    clientId: string,
    skipCount?: number
): Record<string, unknown> {
    const body: Record<string, unknown> = { httpRequest, phases, clientId };
    if (typeof skipCount === "number" && skipCount > 0) {
        body.skipCount = skipCount;
    }
    return body;
}
