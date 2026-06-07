package org.mockserver.serialization.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.mockserver.model.HttpForwardWithFallback;
import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;

import java.util.List;

public class HttpForwardWithFallbackDTO extends ObjectWithReflectiveEqualsHashCodeToString implements DTO<HttpForwardWithFallback> {

    private HttpForwardDTO httpForward;
    private HttpResponseDTO fallbackResponse;
    private List<Integer> fallbackOnStatusCodes;
    private Boolean fallbackOnTimeout;
    private DelayDTO delay;
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean primary;

    public HttpForwardWithFallbackDTO(HttpForwardWithFallback httpForwardWithFallback) {
        if (httpForwardWithFallback != null) {
            if (httpForwardWithFallback.getHttpForward() != null) {
                httpForward = new HttpForwardDTO(httpForwardWithFallback.getHttpForward());
            }
            if (httpForwardWithFallback.getFallbackResponse() != null) {
                fallbackResponse = new HttpResponseDTO(httpForwardWithFallback.getFallbackResponse());
            }
            fallbackOnStatusCodes = httpForwardWithFallback.getFallbackOnStatusCodes();
            fallbackOnTimeout = httpForwardWithFallback.getFallbackOnTimeout();
            if (httpForwardWithFallback.getDelay() != null) {
                delay = new DelayDTO(httpForwardWithFallback.getDelay());
            }
            primary = httpForwardWithFallback.isPrimary();
        }
    }

    public HttpForwardWithFallbackDTO() {
    }

    public HttpForwardWithFallback buildObject() {
        return HttpForwardWithFallback.forwardWithFallback()
            .withForward(httpForward != null ? httpForward.buildObject() : null)
            .withFallback(fallbackResponse != null ? fallbackResponse.buildObject() : null)
            .withFallbackOnStatusCodes(fallbackOnStatusCodes)
            .withFallbackOnTimeout(fallbackOnTimeout)
            .withDelay(delay != null ? delay.buildObject() : null)
            .withPrimary(primary);
    }

    public HttpForwardDTO getHttpForward() {
        return httpForward;
    }

    public HttpForwardWithFallbackDTO setHttpForward(HttpForwardDTO httpForward) {
        this.httpForward = httpForward;
        return this;
    }

    public HttpResponseDTO getFallbackResponse() {
        return fallbackResponse;
    }

    public HttpForwardWithFallbackDTO setFallbackResponse(HttpResponseDTO fallbackResponse) {
        this.fallbackResponse = fallbackResponse;
        return this;
    }

    public List<Integer> getFallbackOnStatusCodes() {
        return fallbackOnStatusCodes;
    }

    public HttpForwardWithFallbackDTO setFallbackOnStatusCodes(List<Integer> fallbackOnStatusCodes) {
        this.fallbackOnStatusCodes = fallbackOnStatusCodes;
        return this;
    }

    public Boolean getFallbackOnTimeout() {
        return fallbackOnTimeout;
    }

    public HttpForwardWithFallbackDTO setFallbackOnTimeout(Boolean fallbackOnTimeout) {
        this.fallbackOnTimeout = fallbackOnTimeout;
        return this;
    }

    public DelayDTO getDelay() {
        return delay;
    }

    public HttpForwardWithFallbackDTO setDelay(DelayDTO delay) {
        this.delay = delay;
        return this;
    }

    public boolean isPrimary() {
        return primary;
    }

    public HttpForwardWithFallbackDTO setPrimary(boolean primary) {
        this.primary = primary;
        return this;
    }
}
