package org.mockserver.mock.breakpoint;

import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.time.TimeService;

import java.util.concurrent.CompletableFuture;

/**
 * Represents a single proxied exchange that has been paused at a breakpoint,
 * awaiting external resolution (continue / modify / abort) via the control-plane
 * REST API or an automatic timeout.
 *
 * <p>A paused exchange can be in one of two phases:
 * <ul>
 *     <li>{@link Phase#REQUEST} — the outbound request is held before it is sent upstream</li>
 *     <li>{@link Phase#RESPONSE} — the upstream response is held before it is written to the client</li>
 * </ul>
 */
public class PausedExchange {

    /**
     * The phase at which the exchange is paused.
     */
    public enum Phase {
        REQUEST,
        RESPONSE
    }

    private final String correlationId;
    private final Phase phase;
    private final HttpRequest capturedRequest;
    private final HttpResponse capturedResponse;
    private final CompletableFuture<BreakpointDecision> decisionFuture;
    private final long createdAtMillis;
    private final String matchedExpectationId;

    /**
     * Creates a REQUEST-phase paused exchange (A1a behaviour — backward compatible).
     */
    public PausedExchange(String correlationId, HttpRequest capturedRequest, String matchedExpectationId) {
        this(correlationId, Phase.REQUEST, capturedRequest, null, matchedExpectationId);
    }

    /**
     * Creates a RESPONSE-phase paused exchange holding the upstream response.
     */
    public PausedExchange(String correlationId, HttpRequest capturedRequest, HttpResponse capturedResponse, String matchedExpectationId) {
        this(correlationId, Phase.RESPONSE, capturedRequest, capturedResponse, matchedExpectationId);
    }

    private PausedExchange(String correlationId, Phase phase, HttpRequest capturedRequest, HttpResponse capturedResponse, String matchedExpectationId) {
        this.correlationId = correlationId;
        this.phase = phase;
        this.capturedRequest = capturedRequest;
        this.capturedResponse = capturedResponse;
        this.decisionFuture = new CompletableFuture<>();
        this.createdAtMillis = TimeService.currentTimeMillis();
        this.matchedExpectationId = matchedExpectationId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Phase getPhase() {
        return phase;
    }

    public HttpRequest getCapturedRequest() {
        return capturedRequest;
    }

    /**
     * The captured upstream response (non-null only for {@link Phase#RESPONSE} exchanges).
     */
    public HttpResponse getCapturedResponse() {
        return capturedResponse;
    }

    public CompletableFuture<BreakpointDecision> getDecisionFuture() {
        return decisionFuture;
    }

    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    public String getMatchedExpectationId() {
        return matchedExpectationId;
    }

    /**
     * Age in milliseconds since this exchange was paused.
     */
    public long ageMillis() {
        return TimeService.currentTimeMillis() - createdAtMillis;
    }
}
