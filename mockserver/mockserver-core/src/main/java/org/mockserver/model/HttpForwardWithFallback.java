package org.mockserver.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Forward a request to an upstream host; if upstream returns a configured
 * status code (default 500-599) or times out, return a fallback mock response.
 */
public class HttpForwardWithFallback extends Action<HttpForwardWithFallback> {

    private int hashCode;
    private HttpForward httpForward;
    private HttpResponse fallbackResponse;
    private List<Integer> fallbackOnStatusCodes;
    private Boolean fallbackOnTimeout;

    /**
     * Static builder to create a forward-with-fallback action.
     */
    public static HttpForwardWithFallback forwardWithFallback() {
        return new HttpForwardWithFallback();
    }

    @Override
    @JsonIgnore
    public Type getType() {
        return Type.FORWARD_WITH_FALLBACK;
    }

    public HttpForward getHttpForward() {
        return httpForward;
    }

    /**
     * The forward definition specifying the upstream host, port and scheme.
     *
     * @param httpForward the forward target
     */
    public HttpForwardWithFallback withForward(HttpForward httpForward) {
        this.httpForward = httpForward;
        this.hashCode = 0;
        return this;
    }

    public HttpResponse getFallbackResponse() {
        return fallbackResponse;
    }

    /**
     * The mock response to return when the upstream forward fails or returns
     * a status code that matches the fallback criteria.
     *
     * @param fallbackResponse the fallback response
     */
    public HttpForwardWithFallback withFallback(HttpResponse fallbackResponse) {
        this.fallbackResponse = fallbackResponse;
        this.hashCode = 0;
        return this;
    }

    public List<Integer> getFallbackOnStatusCodes() {
        return fallbackOnStatusCodes;
    }

    /**
     * The HTTP status codes that should trigger the fallback response.
     * Defaults to 500-599 when not set.
     *
     * @param statusCodes one or more HTTP status codes
     */
    public HttpForwardWithFallback withFallbackOnStatusCodes(Integer... statusCodes) {
        this.fallbackOnStatusCodes = Arrays.asList(statusCodes);
        this.hashCode = 0;
        return this;
    }

    /**
     * The HTTP status codes that should trigger the fallback response.
     * Defaults to 500-599 when not set.
     *
     * @param statusCodes list of HTTP status codes
     */
    public HttpForwardWithFallback withFallbackOnStatusCodes(List<Integer> statusCodes) {
        this.fallbackOnStatusCodes = statusCodes;
        this.hashCode = 0;
        return this;
    }

    public Boolean getFallbackOnTimeout() {
        return fallbackOnTimeout;
    }

    /**
     * Whether to return the fallback response when the upstream request times out
     * or a connection error occurs. Defaults to true when not set.
     *
     * @param fallbackOnTimeout true to fall back on timeout/connection errors
     */
    public HttpForwardWithFallback withFallbackOnTimeout(Boolean fallbackOnTimeout) {
        this.fallbackOnTimeout = fallbackOnTimeout;
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
        if (!super.equals(o)) {
            return false;
        }
        HttpForwardWithFallback that = (HttpForwardWithFallback) o;
        return Objects.equals(httpForward, that.httpForward) &&
            Objects.equals(fallbackResponse, that.fallbackResponse) &&
            Objects.equals(fallbackOnStatusCodes, that.fallbackOnStatusCodes) &&
            Objects.equals(fallbackOnTimeout, that.fallbackOnTimeout);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(super.hashCode(), httpForward, fallbackResponse, fallbackOnStatusCodes, fallbackOnTimeout);
        }
        return hashCode;
    }
}
