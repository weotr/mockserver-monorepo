package org.mockserver.serialization.model;

import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;
import org.mockserver.model.RateLimit;

public class RateLimitDTO extends ObjectWithReflectiveEqualsHashCodeToString implements DTO<RateLimit> {

    private String name;
    private RateLimit.Algorithm algorithm;
    private Integer limit;
    private Long windowMillis;
    private Long burst;
    private Double refillPerSecond;
    private Integer errorStatus;
    private String retryAfter;

    public RateLimitDTO(RateLimit rateLimit) {
        if (rateLimit != null) {
            name = rateLimit.getName();
            algorithm = rateLimit.getAlgorithm();
            limit = rateLimit.getLimit();
            windowMillis = rateLimit.getWindowMillis();
            burst = rateLimit.getBurst();
            refillPerSecond = rateLimit.getRefillPerSecond();
            errorStatus = rateLimit.getErrorStatus();
            retryAfter = rateLimit.getRetryAfter();
        }
    }

    public RateLimitDTO() {
    }

    public RateLimit buildObject() {
        return RateLimit.rateLimit()
            .withName(name)
            .withAlgorithm(algorithm)
            .withLimit(limit)
            .withWindowMillis(windowMillis)
            .withBurst(burst)
            .withRefillPerSecond(refillPerSecond)
            .withErrorStatus(errorStatus)
            .withRetryAfter(retryAfter);
    }

    public String getName() {
        return name;
    }

    public RateLimitDTO setName(String name) {
        this.name = name;
        return this;
    }

    public RateLimit.Algorithm getAlgorithm() {
        return algorithm;
    }

    public RateLimitDTO setAlgorithm(RateLimit.Algorithm algorithm) {
        this.algorithm = algorithm;
        return this;
    }

    public Integer getLimit() {
        return limit;
    }

    public RateLimitDTO setLimit(Integer limit) {
        this.limit = limit;
        return this;
    }

    public Long getWindowMillis() {
        return windowMillis;
    }

    public RateLimitDTO setWindowMillis(Long windowMillis) {
        this.windowMillis = windowMillis;
        return this;
    }

    public Long getBurst() {
        return burst;
    }

    public RateLimitDTO setBurst(Long burst) {
        this.burst = burst;
        return this;
    }

    public Double getRefillPerSecond() {
        return refillPerSecond;
    }

    public RateLimitDTO setRefillPerSecond(Double refillPerSecond) {
        this.refillPerSecond = refillPerSecond;
        return this;
    }

    public Integer getErrorStatus() {
        return errorStatus;
    }

    public RateLimitDTO setErrorStatus(Integer errorStatus) {
        this.errorStatus = errorStatus;
        return this;
    }

    public String getRetryAfter() {
        return retryAfter;
    }

    public RateLimitDTO setRetryAfter(String retryAfter) {
        this.retryAfter = retryAfter;
        return this;
    }
}
