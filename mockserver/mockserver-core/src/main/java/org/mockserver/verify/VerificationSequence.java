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
}
