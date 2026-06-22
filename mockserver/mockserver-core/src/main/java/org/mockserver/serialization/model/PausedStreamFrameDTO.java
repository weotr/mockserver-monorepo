package org.mockserver.serialization.model;

import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;

/**
 * Server-to-client WebSocket message carrying a paused stream frame for interactive
 * breakpoint resolution. Sent to the owning callback client when a stream-frame
 * breakpoint matches and the breakpoint matcher has a non-null {@code clientId}.
 *
 * <p>This DTO is the <b>frozen contract</b> consumed by all language clients (Java,
 * Node, Python, Ruby, Go, .NET, Rust) and the dashboard UI. Field names, types, and
 * encoding MUST NOT change in a backward-incompatible way once shipped.
 *
 * <h3>Fields</h3>
 * <ul>
 *     <li>{@code correlationId} (String) — unique ID for this paused frame; the client
 *         MUST echo it in the reply.</li>
 *     <li>{@code streamId} (String) — the stream this frame belongs to (multiple frames
 *         from the same forwarded response share the same streamId).</li>
 *     <li>{@code sequenceNumber} (int) — 0-based monotonic index within the stream.
 *         Frames MUST be resolved in order.</li>
 *     <li>{@code direction} (String) — {@code "INBOUND"} (client-to-server) or
 *         {@code "OUTBOUND"} (server-to-client).</li>
 *     <li>{@code phase} (String) — {@code "RESPONSE_STREAM"} or {@code "INBOUND_STREAM"}.</li>
 *     <li>{@code body} (String) — the frame payload encoded as <b>Base64</b> (frames are
 *         arbitrary bytes: gRPC length-prefixed, WebSocket text/binary, SSE/chunked).</li>
 *     <li>{@code requestMethod} (String, nullable) — the HTTP method of the original request.</li>
 *     <li>{@code requestPath} (String, nullable) — the path of the original request.</li>
 * </ul>
 *
 * <h3>Encoding</h3>
 * <p>The {@code body} field uses standard Base64 (RFC 4648 section 4, no line breaks).
 * Clients decode with their platform's standard Base64 decoder.
 */
public class PausedStreamFrameDTO extends ObjectWithReflectiveEqualsHashCodeToString {

    private String correlationId;
    private String streamId;
    private int sequenceNumber;
    private String direction;
    private String phase;
    private String body;
    private String requestMethod;
    private String requestPath;
    private String breakpointId;
    private Long requestTimestamp;

    public String getCorrelationId() {
        return correlationId;
    }

    public PausedStreamFrameDTO setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
        return this;
    }

    public String getStreamId() {
        return streamId;
    }

    public PausedStreamFrameDTO setStreamId(String streamId) {
        this.streamId = streamId;
        return this;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public PausedStreamFrameDTO setSequenceNumber(int sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
        return this;
    }

    public String getDirection() {
        return direction;
    }

    public PausedStreamFrameDTO setDirection(String direction) {
        this.direction = direction;
        return this;
    }

    public String getPhase() {
        return phase;
    }

    public PausedStreamFrameDTO setPhase(String phase) {
        this.phase = phase;
        return this;
    }

    public String getBody() {
        return body;
    }

    public PausedStreamFrameDTO setBody(String body) {
        this.body = body;
        return this;
    }

    public String getRequestMethod() {
        return requestMethod;
    }

    public PausedStreamFrameDTO setRequestMethod(String requestMethod) {
        this.requestMethod = requestMethod;
        return this;
    }

    public String getRequestPath() {
        return requestPath;
    }

    public PausedStreamFrameDTO setRequestPath(String requestPath) {
        this.requestPath = requestPath;
        return this;
    }

    public String getBreakpointId() {
        return breakpointId;
    }

    public PausedStreamFrameDTO setBreakpointId(String breakpointId) {
        this.breakpointId = breakpointId;
        return this;
    }

    public Long getRequestTimestamp() {
        return requestTimestamp;
    }

    public PausedStreamFrameDTO setRequestTimestamp(Long requestTimestamp) {
        this.requestTimestamp = requestTimestamp;
        return this;
    }
}
