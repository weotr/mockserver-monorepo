package org.mockserver.model;

import java.util.Objects;

/**
 * Declarative HTTP fault/chaos injection for mocked responses: probabilistic
 * error status injection (e.g. 500, 503, 429 with an optional {@code Retry-After}
 * header) and latency injection.
 * <p>
 * Attach to an {@link org.mockserver.mock.Expectation} via
 * {@code expectation.withChaos(httpChaosProfile()...)} to inject faults into
 * RESPONSE, RESPONSE_TEMPLATE, RESPONSE_CLASS_CALLBACK, and
 * RESPONSE_OBJECT_CALLBACK actions.
 * <p>
 * Determinism: with {@code errorProbability} of {@code 1.0} (always) or
 * {@code 0.0}/null (never) the error decision is fully deterministic. A
 * fractional probability draws once per response; set {@code seed} to make that
 * single draw reproducible (note: a fixed seed yields the same decision every
 * time).
 * <p>
 * Follows the model field/{@code withX}/getter convention so it round-trips
 * without a bespoke (de)serializer.
 */
public class HttpChaosProfile extends ObjectWithJsonToString {

    private int hashCode;
    private Integer errorStatus;       // HTTP status to inject (e.g. 500, 503, 429)
    private String retryAfter;         // optional Retry-After header value on injected error
    private Double errorProbability;   // 0.0-1.0; null/0 = never inject an error
    private Delay latency;             // optional injected latency
    private Long seed;                 // optional, makes a fractional errorProbability reproducible

    public static HttpChaosProfile httpChaosProfile() {
        return new HttpChaosProfile();
    }

    public HttpChaosProfile withErrorStatus(Integer errorStatus) {
        if (errorStatus != null && (errorStatus < 100 || errorStatus > 599)) {
            throw new IllegalArgumentException("errorStatus must be between 100 and 599, got " + errorStatus);
        }
        this.errorStatus = errorStatus;
        this.hashCode = 0;
        return this;
    }

    public Integer getErrorStatus() {
        return errorStatus;
    }

    public HttpChaosProfile withRetryAfter(String retryAfter) {
        this.retryAfter = retryAfter;
        this.hashCode = 0;
        return this;
    }

    public String getRetryAfter() {
        return retryAfter;
    }

    public HttpChaosProfile withErrorProbability(Double errorProbability) {
        if (errorProbability != null && (Double.isNaN(errorProbability) || errorProbability < 0.0 || errorProbability > 1.0)) {
            throw new IllegalArgumentException("errorProbability must be between 0.0 and 1.0, got " + errorProbability);
        }
        this.errorProbability = errorProbability;
        this.hashCode = 0;
        return this;
    }

    public Double getErrorProbability() {
        return errorProbability;
    }

    public HttpChaosProfile withLatency(Delay latency) {
        this.latency = latency;
        this.hashCode = 0;
        return this;
    }

    public Delay getLatency() {
        return latency;
    }

    public HttpChaosProfile withSeed(Long seed) {
        this.seed = seed;
        this.hashCode = 0;
        return this;
    }

    public Long getSeed() {
        return seed;
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
        HttpChaosProfile that = (HttpChaosProfile) o;
        return Objects.equals(errorStatus, that.errorStatus) &&
            Objects.equals(retryAfter, that.retryAfter) &&
            Objects.equals(errorProbability, that.errorProbability) &&
            Objects.equals(latency, that.latency) &&
            Objects.equals(seed, that.seed);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(errorStatus, retryAfter, errorProbability, latency, seed);
        }
        return hashCode;
    }
}
