package org.mockserver.serialization.model;

import org.mockserver.model.HttpResponseModifierCondition;
import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;

public class HttpResponseModifierConditionDTO extends ObjectWithReflectiveEqualsHashCodeToString implements DTO<HttpResponseModifierCondition> {

    private Integer statusCode;
    private String statusCodeRange;
    private String responseHasHeader;
    private String requestHasHeader;

    public HttpResponseModifierConditionDTO() {
    }

    public HttpResponseModifierConditionDTO(HttpResponseModifierCondition condition) {
        if (condition != null) {
            statusCode = condition.getStatusCode();
            statusCodeRange = condition.getStatusCodeRange();
            responseHasHeader = condition.getResponseHasHeader();
            requestHasHeader = condition.getRequestHasHeader();
        }
    }

    public HttpResponseModifierCondition buildObject() {
        return new HttpResponseModifierCondition()
            .withStatusCode(statusCode)
            .withStatusCodeRange(statusCodeRange)
            .withResponseHasHeader(responseHasHeader)
            .withRequestHasHeader(requestHasHeader);
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public HttpResponseModifierConditionDTO setStatusCode(Integer statusCode) {
        this.statusCode = statusCode;
        return this;
    }

    public String getStatusCodeRange() {
        return statusCodeRange;
    }

    public HttpResponseModifierConditionDTO setStatusCodeRange(String statusCodeRange) {
        this.statusCodeRange = statusCodeRange;
        return this;
    }

    public String getResponseHasHeader() {
        return responseHasHeader;
    }

    public HttpResponseModifierConditionDTO setResponseHasHeader(String responseHasHeader) {
        this.responseHasHeader = responseHasHeader;
        return this;
    }

    public String getRequestHasHeader() {
        return requestHasHeader;
    }

    public HttpResponseModifierConditionDTO setRequestHasHeader(String requestHasHeader) {
        this.requestHasHeader = requestHasHeader;
        return this;
    }
}
