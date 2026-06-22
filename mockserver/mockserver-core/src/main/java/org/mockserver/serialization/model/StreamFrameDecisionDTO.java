package org.mockserver.serialization.model;

import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;

/**
 * Client-to-server WebSocket reply carrying the resolution decision for a paused
 * stream frame. The client sends this after receiving a {@link PausedStreamFrameDTO}
 * and inspecting/modifying the frame.
 *
 * <p>This DTO is the <b>frozen contract</b> consumed by all language clients (Java,
 * Node, Python, Ruby, Go, .NET, Rust) and the dashboard UI. Field names, types, and
 * encoding MUST NOT change in a backward-incompatible way once shipped.
 *
 * <h3>Fields</h3>
 * <ul>
 *     <li>{@code correlationId} (String) — MUST match the {@code correlationId} from the
 *         corresponding {@link PausedStreamFrameDTO}.</li>
 *     <li>{@code action} (String) — one of {@code "CONTINUE"}, {@code "MODIFY"},
 *         {@code "DROP"}, {@code "INJECT"}, {@code "CLOSE"}.</li>
 *     <li>{@code body} (String, nullable) — <b>Base64</b>-encoded replacement/injected
 *         frame bytes. Required for {@code MODIFY} and {@code INJECT}; ignored for
 *         {@code CONTINUE}, {@code DROP}, and {@code CLOSE}.</li>
 * </ul>
 *
 * <h3>Action semantics</h3>
 * <ul>
 *     <li>{@code CONTINUE} — write the original frame unchanged.</li>
 *     <li>{@code MODIFY} — write the {@code body} bytes instead of the original.</li>
 *     <li>{@code DROP} — discard the frame (do not write to client/process inbound).</li>
 *     <li>{@code INJECT} — write the original frame AND an additional frame with {@code body}
 *         bytes.</li>
 *     <li>{@code CLOSE} — end the stream (drop the frame, send stream-end signal, evict
 *         remaining frames).</li>
 * </ul>
 *
 * <h3>Encoding</h3>
 * <p>The {@code body} field uses standard Base64 (RFC 4648 section 4, no line breaks).
 */
public class StreamFrameDecisionDTO extends ObjectWithReflectiveEqualsHashCodeToString {

    private String correlationId;
    private String action;
    private String body;

    public String getCorrelationId() {
        return correlationId;
    }

    public StreamFrameDecisionDTO setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
        return this;
    }

    public String getAction() {
        return action;
    }

    public StreamFrameDecisionDTO setAction(String action) {
        this.action = action;
        return this;
    }

    public String getBody() {
        return body;
    }

    public StreamFrameDecisionDTO setBody(String body) {
        this.body = body;
        return this;
    }
}
