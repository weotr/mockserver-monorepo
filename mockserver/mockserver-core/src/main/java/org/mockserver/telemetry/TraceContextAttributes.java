package org.mockserver.telemetry;

import io.netty.util.AttributeKey;

/**
 * Shared Netty channel {@link AttributeKey} constants for W3C trace context.
 * Lives in {@code mockserver-core} so both core (HttpActionHandler) and netty
 * (TraceContextHandler) can reference the same key without creating a
 * core-to-netty dependency.
 */
public final class TraceContextAttributes {

    /**
     * Channel attribute holding the parsed {@link W3CTraceContext} for the
     * current inbound request. Set by the Netty pipeline's trace context
     * handler; read by the action handler to attach a remote parent to
     * request-level OpenTelemetry spans.
     */
    public static final AttributeKey<W3CTraceContext> TRACE_CONTEXT =
        AttributeKey.valueOf("mockserver_trace_context");

    private TraceContextAttributes() {
    }
}
