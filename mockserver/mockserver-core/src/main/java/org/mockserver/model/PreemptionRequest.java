package org.mockserver.model;

import java.util.Objects;

/**
 * Declarative request to simulate a server <em>preemption</em> (Kubernetes node drain / Spot
 * reclamation / a SIGTERM the platform sends before killing the pod): the server cordons itself
 * (rejecting new exchanges), drains in-flight requests for a bounded window, and optionally signals
 * HTTP/2 clients to stop with a GOAWAY. It is a <em>simulation only</em> and never actually stops the
 * JVM or event loops; the cordon auto-clears after {@code ttlMillis} (a dead-man's switch) or on an
 * explicit uncordon.
 *
 * <p>Server-scoped (there is no host key): preemption affects the whole MockServer instance. Held by
 * the {@code PreemptionSimulator} singleton.
 *
 * <p>Follows the model field/{@code withX}/getter convention so it round-trips through Jackson
 * without a bespoke (de)serializer.
 */
public class PreemptionRequest extends ObjectWithJsonToString {

    /**
     * How new exchanges arriving while cordoned are turned away, and how HTTP/2 clients are told to
     * drain.
     * <ul>
     *   <li>{@code reject503} — reject new HTTP/1.1 and HTTP/2 exchanges with 503 + Retry-After +
     *       Connection: close (no GOAWAY).</li>
     *   <li>{@code goaway} — emit an HTTP/2 GOAWAY so clients stop opening streams; HTTP/1.1 has no
     *       GOAWAY so it still degrades to a 503 close.</li>
     *   <li>{@code both} — reject new exchanges with 503 <em>and</em> emit GOAWAY on HTTP/2.</li>
     * </ul>
     */
    public enum Mode {
        reject503,
        goaway,
        both
    }

    private int hashCode;
    private Long drainMillis;
    private Mode mode;
    private Long ttlMillis;
    private Long lastStreamId;

    public static PreemptionRequest preemptionRequest() {
        return new PreemptionRequest();
    }

    public Long getDrainMillis() {
        return drainMillis;
    }

    public PreemptionRequest withDrainMillis(Long drainMillis) {
        this.drainMillis = drainMillis;
        this.hashCode = 0;
        return this;
    }

    public Mode getMode() {
        return mode;
    }

    public PreemptionRequest withMode(Mode mode) {
        this.mode = mode;
        this.hashCode = 0;
        return this;
    }

    public Long getTtlMillis() {
        return ttlMillis;
    }

    public PreemptionRequest withTtlMillis(Long ttlMillis) {
        this.ttlMillis = ttlMillis;
        this.hashCode = 0;
        return this;
    }

    public Long getLastStreamId() {
        return lastStreamId;
    }

    public PreemptionRequest withLastStreamId(Long lastStreamId) {
        this.lastStreamId = lastStreamId;
        this.hashCode = 0;
        return this;
    }

    /** {@code true} when the mode signals HTTP/2 clients to drain via a GOAWAY frame. */
    public boolean emitsGoAway() {
        return mode == Mode.goaway || mode == Mode.both;
    }

    /** {@code true} when the mode rejects new exchanges with a 503. */
    public boolean rejectsNew() {
        return mode == null || mode == Mode.reject503 || mode == Mode.both;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (hashCode() != o.hashCode()) {
            return false;
        }
        PreemptionRequest that = (PreemptionRequest) o;
        return Objects.equals(drainMillis, that.drainMillis)
            && mode == that.mode
            && Objects.equals(ttlMillis, that.ttlMillis)
            && Objects.equals(lastStreamId, that.lastStreamId);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(drainMillis, mode, ttlMillis, lastStreamId);
        }
        return hashCode;
    }
}
