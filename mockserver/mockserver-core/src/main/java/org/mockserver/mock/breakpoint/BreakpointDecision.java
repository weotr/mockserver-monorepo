package org.mockserver.mock.breakpoint;

import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

/**
 * The resolution decision for a paused (breakpointed) exchange.
 * <p>
 * Three actions are supported:
 * <ul>
 *     <li>{@link Action#CONTINUE} - forward the original request unchanged</li>
 *     <li>{@link Action#MODIFY} - forward a replacement request</li>
 *     <li>{@link Action#ABORT} - do not forward; return an abort response (or 503) to the client</li>
 * </ul>
 */
public class BreakpointDecision {

    public enum Action {
        CONTINUE,
        MODIFY,
        ABORT
    }

    private final Action action;
    private final HttpRequest modifiedRequest;
    private final HttpResponse modifiedResponse;
    private final HttpResponse abortResponse;

    private BreakpointDecision(Action action, HttpRequest modifiedRequest, HttpResponse modifiedResponse, HttpResponse abortResponse) {
        this.action = action;
        this.modifiedRequest = modifiedRequest;
        this.modifiedResponse = modifiedResponse;
        this.abortResponse = abortResponse;
    }

    public static BreakpointDecision continueOriginal() {
        return new BreakpointDecision(Action.CONTINUE, null, null, null);
    }

    /**
     * Modify a REQUEST-phase exchange: forward a replacement request.
     */
    public static BreakpointDecision modify(HttpRequest modifiedRequest) {
        if (modifiedRequest == null) {
            throw new IllegalArgumentException("modifiedRequest must not be null for MODIFY decision");
        }
        return new BreakpointDecision(Action.MODIFY, modifiedRequest, null, null);
    }

    /**
     * Modify a RESPONSE-phase exchange: write a replacement response to the client.
     */
    public static BreakpointDecision modifyResponse(HttpResponse modifiedResponse) {
        if (modifiedResponse == null) {
            throw new IllegalArgumentException("modifiedResponse must not be null for MODIFY decision");
        }
        return new BreakpointDecision(Action.MODIFY, null, modifiedResponse, null);
    }

    public static BreakpointDecision abort(HttpResponse abortResponse) {
        return new BreakpointDecision(Action.ABORT, null, null, abortResponse);
    }

    public Action getAction() {
        return action;
    }

    public HttpRequest getModifiedRequest() {
        return modifiedRequest;
    }

    /**
     * The modified response (non-null only for RESPONSE-phase MODIFY decisions).
     */
    public HttpResponse getModifiedResponse() {
        return modifiedResponse;
    }

    public HttpResponse getAbortResponse() {
        return abortResponse;
    }
}
