package org.mockserver.serialization.model;

import org.mockserver.model.GrpcChaosProfile;
import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;

public class GrpcChaosProfileDTO extends ObjectWithReflectiveEqualsHashCodeToString implements DTO<GrpcChaosProfile> {

    private String errorStatusCode;
    private String errorMessage;
    private Double errorProbability;
    private Long seed;
    private Long latencyMs;
    private Integer succeedFirst;
    private Integer failRequestCount;
    private String quotaName;
    private Integer quotaLimit;
    private Long quotaWindowMillis;

    public GrpcChaosProfileDTO(GrpcChaosProfile grpcChaosProfile) {
        if (grpcChaosProfile != null) {
            errorStatusCode = grpcChaosProfile.getErrorStatusCode();
            errorMessage = grpcChaosProfile.getErrorMessage();
            errorProbability = grpcChaosProfile.getErrorProbability();
            seed = grpcChaosProfile.getSeed();
            latencyMs = grpcChaosProfile.getLatencyMs();
            succeedFirst = grpcChaosProfile.getSucceedFirst();
            failRequestCount = grpcChaosProfile.getFailRequestCount();
            quotaName = grpcChaosProfile.getQuotaName();
            quotaLimit = grpcChaosProfile.getQuotaLimit();
            quotaWindowMillis = grpcChaosProfile.getQuotaWindowMillis();
        }
    }

    public GrpcChaosProfileDTO() {
    }

    @Override
    public GrpcChaosProfile buildObject() {
        return GrpcChaosProfile.grpcChaosProfile()
            .withErrorStatusCode(errorStatusCode)
            .withErrorMessage(errorMessage)
            .withErrorProbability(errorProbability)
            .withSeed(seed)
            .withLatencyMs(latencyMs)
            .withSucceedFirst(succeedFirst)
            .withFailRequestCount(failRequestCount)
            .withQuotaName(quotaName)
            .withQuotaLimit(quotaLimit)
            .withQuotaWindowMillis(quotaWindowMillis);
    }

    public String getErrorStatusCode() {
        return errorStatusCode;
    }

    public GrpcChaosProfileDTO setErrorStatusCode(String errorStatusCode) {
        this.errorStatusCode = errorStatusCode;
        return this;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public GrpcChaosProfileDTO setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
        return this;
    }

    public Double getErrorProbability() {
        return errorProbability;
    }

    public GrpcChaosProfileDTO setErrorProbability(Double errorProbability) {
        this.errorProbability = errorProbability;
        return this;
    }

    public Long getSeed() {
        return seed;
    }

    public GrpcChaosProfileDTO setSeed(Long seed) {
        this.seed = seed;
        return this;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public GrpcChaosProfileDTO setLatencyMs(Long latencyMs) {
        this.latencyMs = latencyMs;
        return this;
    }

    public Integer getSucceedFirst() {
        return succeedFirst;
    }

    public GrpcChaosProfileDTO setSucceedFirst(Integer succeedFirst) {
        this.succeedFirst = succeedFirst;
        return this;
    }

    public Integer getFailRequestCount() {
        return failRequestCount;
    }

    public GrpcChaosProfileDTO setFailRequestCount(Integer failRequestCount) {
        this.failRequestCount = failRequestCount;
        return this;
    }

    public String getQuotaName() {
        return quotaName;
    }

    public GrpcChaosProfileDTO setQuotaName(String quotaName) {
        this.quotaName = quotaName;
        return this;
    }

    public Integer getQuotaLimit() {
        return quotaLimit;
    }

    public GrpcChaosProfileDTO setQuotaLimit(Integer quotaLimit) {
        this.quotaLimit = quotaLimit;
        return this;
    }

    public Long getQuotaWindowMillis() {
        return quotaWindowMillis;
    }

    public GrpcChaosProfileDTO setQuotaWindowMillis(Long quotaWindowMillis) {
        this.quotaWindowMillis = quotaWindowMillis;
        return this;
    }
}
