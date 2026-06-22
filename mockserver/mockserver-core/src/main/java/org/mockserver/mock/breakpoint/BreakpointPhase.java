package org.mockserver.mock.breakpoint;

/**
 * Phases at which a breakpoint matcher can intercept a forwarded exchange.
 *
 * <ul>
 *   <li>{@link #REQUEST} — before the request is forwarded upstream</li>
 *   <li>{@link #RESPONSE} — after the upstream response arrives, before it is written to the client</li>
 *   <li>{@link #RESPONSE_STREAM} — each outbound streaming frame (SSE, chunked, gRPC, WebSocket)</li>
 *   <li>{@link #INBOUND_STREAM} — each inbound frame on a bidirectional connection (WebSocket, gRPC bidi, GraphQL subscription)</li>
 * </ul>
 */
public enum BreakpointPhase {
    REQUEST,
    RESPONSE,
    RESPONSE_STREAM,
    INBOUND_STREAM
}
