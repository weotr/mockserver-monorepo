package org.mockserver.model;

import java.util.Objects;

/**
 * Represents a single step in an ordered multi-action expectation pipeline.
 *
 * <p>Each step carries exactly ONE action target and a {@code responder} flag.
 * Steps without {@code responder = true} are side-effects (like beforeActions:
 * fire-and-forget webhooks/callbacks). Exactly one step in the list must be
 * marked as the responder; that step's action produces the HTTP response.</p>
 *
 * <h3>Side-effect targets (responder = false)</h3>
 * <ul>
 *   <li>{@code httpRequest} &mdash; webhook (HTTP call to an external URL)</li>
 *   <li>{@code httpClassCallback} &mdash; server-side class callback</li>
 *   <li>{@code httpObjectCallback} &mdash; WebSocket object callback</li>
 *   <li>{@code httpForward} &mdash; side-effect forward (response discarded)</li>
 *   <li>{@code httpOverrideForwardedRequest} &mdash; side-effect forward-replace</li>
 * </ul>
 *
 * <h3>Responder targets (responder = true)</h3>
 * <ul>
 *   <li>{@code httpResponse} &mdash; static response</li>
 *   <li>{@code httpForward} &mdash; proxy forward (response returned to client)</li>
 *   <li>{@code httpOverrideForwardedRequest} &mdash; forward-replace (response returned)</li>
 *   <li>{@code httpClassCallback} &mdash; class callback response</li>
 *   <li>{@code httpObjectCallback} &mdash; object callback response</li>
 *   <li>{@code httpError} &mdash; connection-level error (cannot combine with other steps)</li>
 * </ul>
 *
 * <h3>Side-effect step controls</h3>
 * <ul>
 *   <li>{@code blocking} &mdash; when true (default) the pipeline waits for completion</li>
 *   <li>{@code timeout} &mdash; max wait for a blocking side-effect</li>
 *   <li>{@code failurePolicy} &mdash; FAIL_FAST or BEST_EFFORT on failure/timeout</li>
 * </ul>
 */
public class ExpectationStep extends ObjectWithJsonToString {

    private int hashCode;

    // Side-effect / webhook target
    private HttpRequest httpRequest;
    private HttpClassCallback httpClassCallback;
    private HttpObjectCallback httpObjectCallback;

    // Forward targets (usable as side-effect or responder)
    private HttpForward httpForward;
    private HttpOverrideForwardedRequest httpOverrideForwardedRequest;

    // Response targets (responder only)
    private HttpResponse httpResponse;
    private HttpError httpError;

    // Step metadata
    private Boolean responder;
    private Delay delay;
    private Boolean blocking;
    private Delay timeout;
    private FailurePolicy failurePolicy;

    public static ExpectationStep step() {
        return new ExpectationStep();
    }

    // --- action target accessors ---

    public HttpRequest getHttpRequest() {
        return httpRequest;
    }

    public ExpectationStep withHttpRequest(HttpRequest httpRequest) {
        if (httpRequest != null) {
            clearTargets();
        }
        this.httpRequest = httpRequest;
        this.hashCode = 0;
        return this;
    }

    public HttpClassCallback getHttpClassCallback() {
        return httpClassCallback;
    }

    public ExpectationStep withHttpClassCallback(HttpClassCallback httpClassCallback) {
        if (httpClassCallback != null) {
            clearTargets();
        }
        this.httpClassCallback = httpClassCallback;
        this.hashCode = 0;
        return this;
    }

    public HttpObjectCallback getHttpObjectCallback() {
        return httpObjectCallback;
    }

    public ExpectationStep withHttpObjectCallback(HttpObjectCallback httpObjectCallback) {
        if (httpObjectCallback != null) {
            clearTargets();
        }
        this.httpObjectCallback = httpObjectCallback;
        this.hashCode = 0;
        return this;
    }

    public HttpForward getHttpForward() {
        return httpForward;
    }

    public ExpectationStep withHttpForward(HttpForward httpForward) {
        if (httpForward != null) {
            clearTargets();
        }
        this.httpForward = httpForward;
        this.hashCode = 0;
        return this;
    }

    public HttpOverrideForwardedRequest getHttpOverrideForwardedRequest() {
        return httpOverrideForwardedRequest;
    }

    public ExpectationStep withHttpOverrideForwardedRequest(HttpOverrideForwardedRequest httpOverrideForwardedRequest) {
        if (httpOverrideForwardedRequest != null) {
            clearTargets();
        }
        this.httpOverrideForwardedRequest = httpOverrideForwardedRequest;
        this.hashCode = 0;
        return this;
    }

    public HttpResponse getHttpResponse() {
        return httpResponse;
    }

    public ExpectationStep withHttpResponse(HttpResponse httpResponse) {
        if (httpResponse != null) {
            clearTargets();
        }
        this.httpResponse = httpResponse;
        this.hashCode = 0;
        return this;
    }

    public HttpError getHttpError() {
        return httpError;
    }

    public ExpectationStep withHttpError(HttpError httpError) {
        if (httpError != null) {
            clearTargets();
        }
        this.httpError = httpError;
        this.hashCode = 0;
        return this;
    }

    private void clearTargets() {
        this.httpRequest = null;
        this.httpClassCallback = null;
        this.httpObjectCallback = null;
        this.httpForward = null;
        this.httpOverrideForwardedRequest = null;
        this.httpResponse = null;
        this.httpError = null;
    }

    // --- step metadata ---

    public Boolean getResponder() {
        return responder;
    }

    public ExpectationStep withResponder(Boolean responder) {
        this.responder = responder;
        this.hashCode = 0;
        return this;
    }

    public Delay getDelay() {
        return delay;
    }

    public ExpectationStep withDelay(Delay delay) {
        this.delay = delay;
        this.hashCode = 0;
        return this;
    }

    /**
     * Side-effect steps only: when {@code true} (the default when unset) the pipeline
     * waits for this step to complete before proceeding; when {@code false} the step is
     * started but not awaited. Ignored for responder steps.
     */
    public Boolean getBlocking() {
        return blocking;
    }

    public ExpectationStep withBlocking(Boolean blocking) {
        this.blocking = blocking;
        this.hashCode = 0;
        return this;
    }

    /**
     * Side-effect steps only: maximum time to wait for a blocking step.
     */
    public Delay getTimeout() {
        return timeout;
    }

    public ExpectationStep withTimeout(Delay timeout) {
        this.timeout = timeout;
        this.hashCode = 0;
        return this;
    }

    /**
     * Side-effect steps only: what to do when a blocking step fails or times out.
     */
    public FailurePolicy getFailurePolicy() {
        return failurePolicy;
    }

    public ExpectationStep withFailurePolicy(FailurePolicy failurePolicy) {
        this.failurePolicy = failurePolicy;
        this.hashCode = 0;
        return this;
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
        ExpectationStep that = (ExpectationStep) o;
        return Objects.equals(httpRequest, that.httpRequest) &&
            Objects.equals(httpClassCallback, that.httpClassCallback) &&
            Objects.equals(httpObjectCallback, that.httpObjectCallback) &&
            Objects.equals(httpForward, that.httpForward) &&
            Objects.equals(httpOverrideForwardedRequest, that.httpOverrideForwardedRequest) &&
            Objects.equals(httpResponse, that.httpResponse) &&
            Objects.equals(httpError, that.httpError) &&
            Objects.equals(responder, that.responder) &&
            Objects.equals(delay, that.delay) &&
            Objects.equals(blocking, that.blocking) &&
            Objects.equals(timeout, that.timeout) &&
            failurePolicy == that.failurePolicy;
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(httpRequest, httpClassCallback, httpObjectCallback, httpForward,
                httpOverrideForwardedRequest, httpResponse, httpError, responder, delay, blocking,
                timeout, failurePolicy);
        }
        return hashCode;
    }
}
