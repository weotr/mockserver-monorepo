package org.mockserver.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Objects;

/**
 * Declarative, protocol-agnostic rate-limiting / quota action attached to an
 * {@link org.mockserver.mock.Expectation} (a sibling of {@code chaos}). When a
 * matched expectation carries a {@code rateLimit} clause and the request is
 * over-limit for the current window, a deterministic {@code errorStatus}
 * (default {@code 429}) response is returned <em>instead of</em> the normal
 * response, carrying {@code Retry-After} and {@code X-RateLimit-*} headers; when
 * the request is within the limit the normal response is returned unchanged.
 * <p>
 * Counting is backed by a shared, named in-process registry
 * ({@link org.mockserver.ratelimit.RateLimitRegistry}): expectations that share
 * a {@code name} share one counter (model an upstream account limit); when
 * {@code name} is null the expectation's own id is used as the counter key so a
 * single expectation rate-limits in isolation. v1 supports two algorithms:
 * <ul>
 *   <li>{@code FIXED_WINDOW} — at most {@code limit} requests per
 *       {@code windowMillis}; the window starts on the first request and resets
 *       once it elapses.</li>
 *   <li>{@code TOKEN_BUCKET} — a bucket of capacity {@code burst} refilling at
 *       {@code refillPerSecond} tokens/second; a request is allowed when at least
 *       one token is available (and consumes it).</li>
 * </ul>
 * v1 is node-local. Follows the model field/{@code withX}/getter convention so it
 * round-trips without a bespoke (de)serializer.
 */
public class RateLimit extends ObjectWithJsonToString {

    public enum Algorithm {
        FIXED_WINDOW,
        TOKEN_BUCKET;

        @JsonValue
        public String value() {
            return name().toLowerCase();
        }

        @JsonCreator
        public static Algorithm fromValue(String value) {
            if (value == null) {
                return null;
            }
            return Algorithm.valueOf(value.trim().toUpperCase());
        }
    }

    private int hashCode;
    private String name;              // shared counter key; null => use the expectation id
    private Algorithm algorithm;      // FIXED_WINDOW (default) or TOKEN_BUCKET
    private Integer limit;            // FIXED_WINDOW: max requests allowed per window (>= 1)
    private Long windowMillis;        // FIXED_WINDOW: window length in milliseconds (>= 1)
    private Long burst;               // TOKEN_BUCKET: bucket capacity in tokens (>= 1)
    private Double refillPerSecond;   // TOKEN_BUCKET: token refill rate per second (> 0)
    private Integer errorStatus;      // status when over-limit (default 429)
    private String retryAfter;        // literal Retry-After override; else computed

    public static RateLimit rateLimit() {
        return new RateLimit();
    }

    public RateLimit withName(String name) {
        this.name = name;
        this.hashCode = 0;
        return this;
    }

    public String getName() {
        return name;
    }

    public RateLimit withAlgorithm(Algorithm algorithm) {
        this.algorithm = algorithm;
        this.hashCode = 0;
        return this;
    }

    public Algorithm getAlgorithm() {
        return algorithm;
    }

    public RateLimit withLimit(Integer limit) {
        if (limit != null && limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1, got " + limit);
        }
        this.limit = limit;
        this.hashCode = 0;
        return this;
    }

    public Integer getLimit() {
        return limit;
    }

    public RateLimit withWindowMillis(Long windowMillis) {
        if (windowMillis != null && windowMillis < 1) {
            throw new IllegalArgumentException("windowMillis must be >= 1, got " + windowMillis);
        }
        this.windowMillis = windowMillis;
        this.hashCode = 0;
        return this;
    }

    public Long getWindowMillis() {
        return windowMillis;
    }

    public RateLimit withBurst(Long burst) {
        if (burst != null && burst < 1) {
            throw new IllegalArgumentException("burst must be >= 1, got " + burst);
        }
        this.burst = burst;
        this.hashCode = 0;
        return this;
    }

    public Long getBurst() {
        return burst;
    }

    public RateLimit withRefillPerSecond(Double refillPerSecond) {
        if (refillPerSecond != null && (Double.isNaN(refillPerSecond) || refillPerSecond <= 0.0)) {
            throw new IllegalArgumentException("refillPerSecond must be > 0, got " + refillPerSecond);
        }
        this.refillPerSecond = refillPerSecond;
        this.hashCode = 0;
        return this;
    }

    public Double getRefillPerSecond() {
        return refillPerSecond;
    }

    public RateLimit withErrorStatus(Integer errorStatus) {
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

    public RateLimit withRetryAfter(String retryAfter) {
        this.retryAfter = retryAfter;
        this.hashCode = 0;
        return this;
    }

    public String getRetryAfter() {
        return retryAfter;
    }

    /**
     * The effective algorithm, defaulting to {@link Algorithm#FIXED_WINDOW} when unset.
     */
    public Algorithm effectiveAlgorithm() {
        return algorithm != null ? algorithm : Algorithm.FIXED_WINDOW;
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
        RateLimit that = (RateLimit) o;
        return Objects.equals(name, that.name) &&
            algorithm == that.algorithm &&
            Objects.equals(limit, that.limit) &&
            Objects.equals(windowMillis, that.windowMillis) &&
            Objects.equals(burst, that.burst) &&
            Objects.equals(refillPerSecond, that.refillPerSecond) &&
            Objects.equals(errorStatus, that.errorStatus) &&
            Objects.equals(retryAfter, that.retryAfter);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(name, algorithm, limit, windowMillis, burst, refillPerSecond, errorStatus, retryAfter);
        }
        return hashCode;
    }
}
