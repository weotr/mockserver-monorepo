package org.mockserver.serialization.model;

import org.mockserver.model.*;

public class ExpectationStepDTO extends ObjectWithJsonToString implements DTO<ExpectationStep> {
    private HttpRequestDTO httpRequest;
    private HttpClassCallbackDTO httpClassCallback;
    private HttpObjectCallbackDTO httpObjectCallback;
    private HttpForwardDTO httpForward;
    private HttpOverrideForwardedRequestDTO httpOverrideForwardedRequest;
    private HttpResponseDTO httpResponse;
    private HttpErrorDTO httpError;
    private Boolean responder;
    private DelayDTO delay;
    private Boolean blocking;
    private DelayDTO timeout;
    private FailurePolicy failurePolicy;

    public ExpectationStepDTO() {
    }

    public ExpectationStepDTO(ExpectationStep step) {
        if (step != null) {
            if (step.getHttpRequest() != null) {
                this.httpRequest = new HttpRequestDTO(step.getHttpRequest());
            }
            if (step.getHttpClassCallback() != null) {
                this.httpClassCallback = new HttpClassCallbackDTO(step.getHttpClassCallback());
            }
            if (step.getHttpObjectCallback() != null) {
                this.httpObjectCallback = new HttpObjectCallbackDTO(step.getHttpObjectCallback());
            }
            if (step.getHttpForward() != null) {
                this.httpForward = new HttpForwardDTO(step.getHttpForward());
            }
            if (step.getHttpOverrideForwardedRequest() != null) {
                this.httpOverrideForwardedRequest = new HttpOverrideForwardedRequestDTO(step.getHttpOverrideForwardedRequest());
            }
            if (step.getHttpResponse() != null) {
                this.httpResponse = new HttpResponseDTO(step.getHttpResponse());
            }
            if (step.getHttpError() != null) {
                this.httpError = new HttpErrorDTO(step.getHttpError());
            }
            this.responder = step.getResponder();
            if (step.getDelay() != null) {
                this.delay = new DelayDTO(step.getDelay());
            }
            this.blocking = step.getBlocking();
            if (step.getTimeout() != null) {
                this.timeout = new DelayDTO(step.getTimeout());
            }
            this.failurePolicy = step.getFailurePolicy();
        }
    }

    @Override
    public ExpectationStep buildObject() {
        ExpectationStep step = new ExpectationStep();
        if (httpRequest != null) {
            step.withHttpRequest(httpRequest.buildObject());
        }
        if (httpClassCallback != null) {
            step.withHttpClassCallback(httpClassCallback.buildObject());
        }
        if (httpObjectCallback != null) {
            step.withHttpObjectCallback(httpObjectCallback.buildObject());
        }
        if (httpForward != null) {
            step.withHttpForward(httpForward.buildObject());
        }
        if (httpOverrideForwardedRequest != null) {
            step.withHttpOverrideForwardedRequest(httpOverrideForwardedRequest.buildObject());
        }
        if (httpResponse != null) {
            step.withHttpResponse(httpResponse.buildObject());
        }
        if (httpError != null) {
            step.withHttpError(httpError.buildObject());
        }
        if (responder != null) {
            step.withResponder(responder);
        }
        if (delay != null) {
            step.withDelay(delay.buildObject());
        }
        if (blocking != null) {
            step.withBlocking(blocking);
        }
        if (timeout != null) {
            step.withTimeout(timeout.buildObject());
        }
        if (failurePolicy != null) {
            step.withFailurePolicy(failurePolicy);
        }
        return step;
    }

    public HttpRequestDTO getHttpRequest() {
        return httpRequest;
    }

    public ExpectationStepDTO setHttpRequest(HttpRequestDTO httpRequest) {
        this.httpRequest = httpRequest;
        return this;
    }

    public HttpClassCallbackDTO getHttpClassCallback() {
        return httpClassCallback;
    }

    public ExpectationStepDTO setHttpClassCallback(HttpClassCallbackDTO httpClassCallback) {
        this.httpClassCallback = httpClassCallback;
        return this;
    }

    public HttpObjectCallbackDTO getHttpObjectCallback() {
        return httpObjectCallback;
    }

    public ExpectationStepDTO setHttpObjectCallback(HttpObjectCallbackDTO httpObjectCallback) {
        this.httpObjectCallback = httpObjectCallback;
        return this;
    }

    public HttpForwardDTO getHttpForward() {
        return httpForward;
    }

    public ExpectationStepDTO setHttpForward(HttpForwardDTO httpForward) {
        this.httpForward = httpForward;
        return this;
    }

    public HttpOverrideForwardedRequestDTO getHttpOverrideForwardedRequest() {
        return httpOverrideForwardedRequest;
    }

    public ExpectationStepDTO setHttpOverrideForwardedRequest(HttpOverrideForwardedRequestDTO httpOverrideForwardedRequest) {
        this.httpOverrideForwardedRequest = httpOverrideForwardedRequest;
        return this;
    }

    public HttpResponseDTO getHttpResponse() {
        return httpResponse;
    }

    public ExpectationStepDTO setHttpResponse(HttpResponseDTO httpResponse) {
        this.httpResponse = httpResponse;
        return this;
    }

    public HttpErrorDTO getHttpError() {
        return httpError;
    }

    public ExpectationStepDTO setHttpError(HttpErrorDTO httpError) {
        this.httpError = httpError;
        return this;
    }

    public Boolean getResponder() {
        return responder;
    }

    public ExpectationStepDTO setResponder(Boolean responder) {
        this.responder = responder;
        return this;
    }

    public DelayDTO getDelay() {
        return delay;
    }

    public ExpectationStepDTO setDelay(DelayDTO delay) {
        this.delay = delay;
        return this;
    }

    public Boolean getBlocking() {
        return blocking;
    }

    public ExpectationStepDTO setBlocking(Boolean blocking) {
        this.blocking = blocking;
        return this;
    }

    public DelayDTO getTimeout() {
        return timeout;
    }

    public ExpectationStepDTO setTimeout(DelayDTO timeout) {
        this.timeout = timeout;
        return this;
    }

    public FailurePolicy getFailurePolicy() {
        return failurePolicy;
    }

    public ExpectationStepDTO setFailurePolicy(FailurePolicy failurePolicy) {
        this.failurePolicy = failurePolicy;
        return this;
    }
}
