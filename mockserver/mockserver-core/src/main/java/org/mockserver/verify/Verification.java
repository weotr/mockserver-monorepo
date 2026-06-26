package org.mockserver.verify;

import org.mockserver.model.ExpectationId;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.ObjectWithJsonToString;
import org.mockserver.model.RequestDefinition;


/**
 * @author jamesdbloom
 */
public class Verification extends ObjectWithJsonToString {
    private RequestDefinition httpRequest;
    private HttpResponse httpResponse;
    private ExpectationId expectationId;
    private VerificationTimes times = VerificationTimes.atLeast(1);
    private Integer maximumNumberOfRequestToReturnInVerificationFailure;
    private Disposition disposition;
    private Long timeout;

    public static Verification verification() {
        return new Verification();
    }

    public Verification withRequest(RequestDefinition requestDefinition) {
        this.httpRequest = requestDefinition;
        return this;
    }

    public RequestDefinition getHttpRequest() {
        return httpRequest;
    }

    public Verification withResponse(HttpResponse httpResponse) {
        this.httpResponse = httpResponse;
        return this;
    }

    public HttpResponse getHttpResponse() {
        return httpResponse;
    }

    public Verification withExpectationId(ExpectationId expectationId) {
        this.expectationId = expectationId;
        return this;
    }

    public ExpectationId getExpectationId() {
        return expectationId;
    }

    public Verification withTimes(VerificationTimes times) {
        this.times = times;
        return this;
    }

    public VerificationTimes getTimes() {
        return times;
    }

    public Integer getMaximumNumberOfRequestToReturnInVerificationFailure() {
        return maximumNumberOfRequestToReturnInVerificationFailure;
    }

    public Verification withMaximumNumberOfRequestToReturnInVerificationFailure(Integer maximumNumberOfRequestToReturnInVerificationFailure) {
        this.maximumNumberOfRequestToReturnInVerificationFailure = maximumNumberOfRequestToReturnInVerificationFailure;
        return this;
    }

    public Disposition getDisposition() {
        return disposition;
    }

    /**
     * Narrow this verification to count only requests that were handled with the given
     * {@link Disposition} — {@link Disposition#FORWARDED forwarded/proxied} or
     * {@link Disposition#MOCKED mocked}. When unset, all received requests are counted
     * regardless of how they were handled (the original behaviour).
     *
     * @param disposition the request handling outcome to filter by, or {@code null} for no filter
     * @return this verification for chaining
     */
    public Verification withDisposition(Disposition disposition) {
        this.disposition = disposition;
        return this;
    }

    public Long getTimeout() {
        return timeout;
    }

    /**
     * Enable server-side eventual verification: when set to a positive number of milliseconds the
     * server waits (asynchronously) and re-evaluates this verification until it passes or the timeout
     * elapses, rather than evaluating the event log once and immediately accepting/rejecting. The
     * server returns success as soon as the verification is satisfied, or the last failure message when
     * the timeout elapses. {@code null}, absent, or {@code 0} preserves the original single-shot
     * behaviour. The accepted value is capped server-side (see
     * {@code MockServerEventLog.MAX_VERIFY_TIMEOUT_MILLIS}) so a client cannot tie up server resources
     * indefinitely. The wait is fully asynchronous and never holds a Netty I/O thread.
     *
     * @param timeout the maximum time, in milliseconds, to wait for the verification to pass, or
     *                {@code null}/{@code 0} for the original single-shot behaviour
     * @return this verification for chaining
     */
    public Verification withTimeout(Long timeout) {
        this.timeout = timeout;
        return this;
    }
}
