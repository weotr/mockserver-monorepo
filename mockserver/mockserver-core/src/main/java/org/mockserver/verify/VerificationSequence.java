package org.mockserver.verify;

import org.mockserver.model.ExpectationId;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.ObjectWithJsonToString;
import org.mockserver.model.RequestDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @author jamesdbloom
 */
public class VerificationSequence extends ObjectWithJsonToString {
    private List<RequestDefinition> httpRequests = new ArrayList<>();
    private List<HttpResponse> httpResponses = new ArrayList<>();
    private List<ExpectationId> expectationIds = new ArrayList<>();
    private Integer maximumNumberOfRequestToReturnInVerificationFailure;
    private Long timeout;

    public static VerificationSequence verificationSequence() {
        return new VerificationSequence();
    }

    public VerificationSequence withRequests(RequestDefinition... httpRequests) {
        Collections.addAll(this.httpRequests, httpRequests);
        return this;
    }

    public VerificationSequence withRequests(List<RequestDefinition> httpRequests) {
        this.httpRequests = httpRequests;
        return this;
    }

    public List<RequestDefinition> getHttpRequests() {
        return httpRequests;
    }

    public VerificationSequence withResponses(HttpResponse... httpResponses) {
        Collections.addAll(this.httpResponses, httpResponses);
        return this;
    }

    public VerificationSequence withResponses(List<HttpResponse> httpResponses) {
        this.httpResponses = httpResponses;
        return this;
    }

    public List<HttpResponse> getHttpResponses() {
        return httpResponses;
    }

    public VerificationSequence withExpectationIds(ExpectationId... expectationIds) {
        Collections.addAll(this.expectationIds, expectationIds);
        return this;
    }

    public VerificationSequence withExpectationIds(List<ExpectationId> expectationIds) {
        this.expectationIds = expectationIds;
        return this;
    }

    public List<ExpectationId> getExpectationIds() {
        return expectationIds;
    }

    public Integer getMaximumNumberOfRequestToReturnInVerificationFailure() {
        return maximumNumberOfRequestToReturnInVerificationFailure;
    }

    public VerificationSequence withMaximumNumberOfRequestToReturnInVerificationFailure(Integer maximumNumberOfRequestToReturnInVerificationFailure) {
        this.maximumNumberOfRequestToReturnInVerificationFailure = maximumNumberOfRequestToReturnInVerificationFailure;
        return this;
    }

    public Long getTimeout() {
        return timeout;
    }

    /**
     * Enable server-side eventual sequence verification: when set to a positive number of
     * milliseconds the server waits (asynchronously) and re-evaluates this sequence verification until
     * it passes or the timeout elapses, rather than evaluating the event log once. The server returns
     * success as soon as the sequence is satisfied, or the last failure message when the timeout
     * elapses. {@code null}, absent, or {@code 0} preserves the original single-shot behaviour. The
     * accepted value is capped server-side (see {@code MockServerEventLog.MAX_VERIFY_TIMEOUT_MILLIS}).
     * The wait is fully asynchronous and never holds a Netty I/O thread.
     *
     * @param timeout the maximum time, in milliseconds, to wait for the sequence verification to
     *                pass, or {@code null}/{@code 0} for the original single-shot behaviour
     * @return this verification sequence for chaining
     */
    public VerificationSequence withTimeout(Long timeout) {
        this.timeout = timeout;
        return this;
    }
}
