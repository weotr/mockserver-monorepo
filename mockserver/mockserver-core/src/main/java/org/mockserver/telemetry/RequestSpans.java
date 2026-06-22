package org.mockserver.telemetry;

import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Context;

/**
 * Static emit point for request-level OpenTelemetry spans describing HTTP
 * request/response handling by MockServer. Mirrors {@link GenAiSpans} in
 * structure: a process-wide volatile tracer holder, fail-soft recording, and
 * no-op when disabled.
 * <p>
 * No-op unless {@link GenAiSpanExporter} has installed a tracer (i.e. unless
 * {@code mockserver.otelTracesEnabled} is set). Fail-soft: any error while
 * recording is swallowed so telemetry never affects responses.
 */
public final class RequestSpans {

    private static volatile Tracer tracer;

    private RequestSpans() {
    }

    static void setTracer(Tracer newTracer) {
        tracer = newTracer;
    }

    /** True if span emission is currently active. */
    public static boolean isEnabled() {
        return tracer != null;
    }

    /**
     * Emit one SERVER span for a served HTTP request. No-op when disabled.
     *
     * @param method          HTTP method (e.g. "GET", "POST")
     * @param path            request path (matched expectation path or raw request path)
     * @param statusCode      response status code, or null if unknown
     * @param expectationId   the matched expectation ID, or null if none matched
     * @param responseTimeMs  wall-clock response time in milliseconds (0 if unavailable)
     * @param parentContext   parsed W3C trace context from the inbound request, or null
     */
    public static void recordRequest(String method, String path, Integer statusCode,
                                     String expectationId, long responseTimeMs,
                                     W3CTraceContext parentContext) {
        recordRequest(method, path, statusCode, expectationId, responseTimeMs, parentContext, null, null);
    }

    /**
     * Emit one SERVER span for a served HTTP request, additionally carrying the
     * resolved upstream {@code server.address}/{@code server.port} for forward
     * and proxy paths. No-op when disabled.
     *
     * @param serverAddress  the resolved upstream host (no port), or null on the mocked path
     * @param serverPort     the resolved upstream port, or null if unknown
     */
    public static void recordRequest(String method, String path, Integer statusCode,
                                     String expectationId, long responseTimeMs,
                                     W3CTraceContext parentContext,
                                     String serverAddress, Integer serverPort) {
        Tracer current = tracer;
        if (current == null) {
            return;
        }
        recordRequest(current, method, path, statusCode, expectationId, responseTimeMs, parentContext, serverAddress, serverPort);
    }

    /**
     * Record a request span using the given tracer. Package-private to allow
     * tests to call this with a per-test tracer, avoiding the shared
     * process-wide static and the cross-contamination that causes when test
     * classes run in parallel.
     */
    static void recordRequest(Tracer explicitTracer, String method, String path, Integer statusCode,
                              String expectationId, long responseTimeMs,
                              W3CTraceContext parentContext) {
        recordRequest(explicitTracer, method, path, statusCode, expectationId, responseTimeMs, parentContext, null, null);
    }

    /**
     * Record a request span using the given tracer, additionally carrying the
     * resolved upstream {@code server.address}/{@code server.port}. Package-private
     * for tests.
     */
    static void recordRequest(Tracer explicitTracer, String method, String path, Integer statusCode,
                              String expectationId, long responseTimeMs,
                              W3CTraceContext parentContext,
                              String serverAddress, Integer serverPort) {
        try {
            String resolvedMethod = method != null && !method.isEmpty() ? method : "HTTP";
            String spanName = resolvedMethod + " " + (path != null && !path.isEmpty() ? path : "/");

            SpanBuilder spanBuilder = explicitTracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.SERVER);

            // Wire the remote parent context from the inbound W3C traceparent header
            if (parentContext != null && parentContext.isValid()) {
                SpanContext remoteParent = SpanContext.createFromRemoteParent(
                    parentContext.getTraceId(),
                    parentContext.getParentId(),
                    "01".equals(parentContext.getFlags()) ? TraceFlags.getSampled() : TraceFlags.getDefault(),
                    TraceState.getDefault()
                );
                Context parentOtelContext = Context.current().with(Span.wrap(remoteParent));
                spanBuilder.setParent(parentOtelContext);
            }

            Span span = spanBuilder.startSpan();
            try {
                span.setAttribute("http.request.method", resolvedMethod);
                if (path != null && !path.isEmpty()) {
                    span.setAttribute("http.route", path);
                }
                if (statusCode != null) {
                    span.setAttribute("http.response.status_code", (long) statusCode);
                }
                if (expectationId != null && !expectationId.isEmpty()) {
                    span.setAttribute("mockserver.expectation_id", expectationId);
                }
                if (responseTimeMs > 0) {
                    span.setAttribute("mockserver.response_time_ms", responseTimeMs);
                }
                // server.address / server.port — the resolved upstream the request was
                // forwarded/proxied to (OpenTelemetry semantic conventions). Present only
                // on forward/proxy paths; omitted on the mocked-response path.
                if (serverAddress != null && !serverAddress.isEmpty()) {
                    span.setAttribute("server.address", serverAddress);
                }
                if (serverPort != null && serverPort > 0) {
                    span.setAttribute("server.port", (long) serverPort);
                }
            } finally {
                span.end();
            }
        } catch (Exception e) {
            // fail-soft: telemetry must never affect the served response
        }
    }
}
