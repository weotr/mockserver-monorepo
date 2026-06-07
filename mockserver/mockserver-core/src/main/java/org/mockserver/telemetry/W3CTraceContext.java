package org.mockserver.telemetry;

/**
 * Parsed W3C traceparent header. Format: {version}-{traceId}-{parentId}-{flags}
 * Example: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
 *
 * @see <a href="https://www.w3.org/TR/trace-context/">W3C Trace Context specification</a>
 */
public class W3CTraceContext {

    private final String version;
    private final String traceId;
    private final String parentId;
    private final String flags;
    private final String traceState;

    public W3CTraceContext(String version, String traceId, String parentId, String flags, String traceState) {
        this.version = version;
        this.traceId = traceId;
        this.parentId = parentId;
        this.flags = flags;
        this.traceState = traceState;
    }

    /**
     * Parse a W3C traceparent header value and optional tracestate header value
     * into a {@link W3CTraceContext}. Returns null if the traceparent is null,
     * empty, or has fewer than 4 dash-separated parts.
     */
    public static W3CTraceContext parse(String traceparent, String tracestate) {
        if (traceparent == null || traceparent.isEmpty()) {
            return null;
        }
        String[] parts = traceparent.trim().split("-");
        if (parts.length < 4) {
            return null;
        }
        return new W3CTraceContext(parts[0], parts[1], parts[2], parts[3], tracestate);
    }

    /**
     * Reconstruct the traceparent header value from this context.
     */
    public String toTraceparent() {
        return version + "-" + traceId + "-" + parentId + "-" + flags;
    }

    public String getVersion() {
        return version;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getParentId() {
        return parentId;
    }

    public String getFlags() {
        return flags;
    }

    public String getTraceState() {
        return traceState;
    }

    /**
     * Check structural validity: version present, traceId is 32 hex chars,
     * parentId is 16 hex chars, flags present.
     */
    public boolean isValid() {
        return version != null && traceId != null && traceId.length() == 32
            && parentId != null && parentId.length() == 16 && flags != null;
    }
}
