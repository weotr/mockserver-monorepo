package org.mockserver.serialization.model;

import org.mockserver.model.HttpChaosProfile;
import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;

public class HttpChaosProfileDTO extends ObjectWithReflectiveEqualsHashCodeToString implements DTO<HttpChaosProfile> {

    private Integer errorStatus;
    private String retryAfter;
    private Double errorProbability;
    private Double dropConnectionProbability;
    private DelayDTO latency;
    private Long seed;
    private Integer succeedFirst;
    private Integer failRequestCount;
    private Long outageAfterMillis;
    private Long outageDurationMillis;
    private Double truncateBodyAtFraction;
    private Boolean malformedBody;
    private Integer slowResponseChunkSize;
    private DelayDTO slowResponseChunkDelay;
    private String quotaName;
    private Integer quotaLimit;
    private Long quotaWindowMillis;
    private Integer quotaErrorStatus;
    private Long degradationRampMillis;
    private Boolean graphqlErrors;
    private String graphqlErrorMessage;
    private String graphqlErrorCode;
    private Boolean graphqlNullifyData;

    public HttpChaosProfileDTO(HttpChaosProfile httpChaosProfile) {
        if (httpChaosProfile != null) {
            errorStatus = httpChaosProfile.getErrorStatus();
            retryAfter = httpChaosProfile.getRetryAfter();
            errorProbability = httpChaosProfile.getErrorProbability();
            dropConnectionProbability = httpChaosProfile.getDropConnectionProbability();
            if (httpChaosProfile.getLatency() != null) {
                latency = new DelayDTO(httpChaosProfile.getLatency());
            }
            seed = httpChaosProfile.getSeed();
            succeedFirst = httpChaosProfile.getSucceedFirst();
            failRequestCount = httpChaosProfile.getFailRequestCount();
            outageAfterMillis = httpChaosProfile.getOutageAfterMillis();
            outageDurationMillis = httpChaosProfile.getOutageDurationMillis();
            truncateBodyAtFraction = httpChaosProfile.getTruncateBodyAtFraction();
            malformedBody = httpChaosProfile.getMalformedBody();
            slowResponseChunkSize = httpChaosProfile.getSlowResponseChunkSize();
            if (httpChaosProfile.getSlowResponseChunkDelay() != null) {
                slowResponseChunkDelay = new DelayDTO(httpChaosProfile.getSlowResponseChunkDelay());
            }
            quotaName = httpChaosProfile.getQuotaName();
            quotaLimit = httpChaosProfile.getQuotaLimit();
            quotaWindowMillis = httpChaosProfile.getQuotaWindowMillis();
            quotaErrorStatus = httpChaosProfile.getQuotaErrorStatus();
            degradationRampMillis = httpChaosProfile.getDegradationRampMillis();
            graphqlErrors = httpChaosProfile.getGraphqlErrors();
            graphqlErrorMessage = httpChaosProfile.getGraphqlErrorMessage();
            graphqlErrorCode = httpChaosProfile.getGraphqlErrorCode();
            graphqlNullifyData = httpChaosProfile.getGraphqlNullifyData();
        }
    }

    public HttpChaosProfileDTO() {
    }

    public HttpChaosProfile buildObject() {
        return HttpChaosProfile.httpChaosProfile()
            .withErrorStatus(errorStatus)
            .withRetryAfter(retryAfter)
            .withErrorProbability(errorProbability)
            .withDropConnectionProbability(dropConnectionProbability)
            .withLatency(latency != null ? latency.buildObject() : null)
            .withSeed(seed)
            .withSucceedFirst(succeedFirst)
            .withFailRequestCount(failRequestCount)
            .withOutageAfterMillis(outageAfterMillis)
            .withOutageDurationMillis(outageDurationMillis)
            .withTruncateBodyAtFraction(truncateBodyAtFraction)
            .withMalformedBody(malformedBody)
            .withSlowResponseChunkSize(slowResponseChunkSize)
            .withSlowResponseChunkDelay(slowResponseChunkDelay != null ? slowResponseChunkDelay.buildObject() : null)
            .withQuotaName(quotaName)
            .withQuotaLimit(quotaLimit)
            .withQuotaWindowMillis(quotaWindowMillis)
            .withQuotaErrorStatus(quotaErrorStatus)
            .withDegradationRampMillis(degradationRampMillis)
            .withGraphqlErrors(graphqlErrors)
            .withGraphqlErrorMessage(graphqlErrorMessage)
            .withGraphqlErrorCode(graphqlErrorCode)
            .withGraphqlNullifyData(graphqlNullifyData);
    }

    public Integer getErrorStatus() {
        return errorStatus;
    }

    public HttpChaosProfileDTO setErrorStatus(Integer errorStatus) {
        this.errorStatus = errorStatus;
        return this;
    }

    public String getRetryAfter() {
        return retryAfter;
    }

    public HttpChaosProfileDTO setRetryAfter(String retryAfter) {
        this.retryAfter = retryAfter;
        return this;
    }

    public Double getErrorProbability() {
        return errorProbability;
    }

    public HttpChaosProfileDTO setErrorProbability(Double errorProbability) {
        this.errorProbability = errorProbability;
        return this;
    }

    public Double getDropConnectionProbability() {
        return dropConnectionProbability;
    }

    public HttpChaosProfileDTO setDropConnectionProbability(Double dropConnectionProbability) {
        this.dropConnectionProbability = dropConnectionProbability;
        return this;
    }

    public DelayDTO getLatency() {
        return latency;
    }

    public HttpChaosProfileDTO setLatency(DelayDTO latency) {
        this.latency = latency;
        return this;
    }

    public Long getSeed() {
        return seed;
    }

    public HttpChaosProfileDTO setSeed(Long seed) {
        this.seed = seed;
        return this;
    }

    public Integer getSucceedFirst() {
        return succeedFirst;
    }

    public HttpChaosProfileDTO setSucceedFirst(Integer succeedFirst) {
        this.succeedFirst = succeedFirst;
        return this;
    }

    public Integer getFailRequestCount() {
        return failRequestCount;
    }

    public HttpChaosProfileDTO setFailRequestCount(Integer failRequestCount) {
        this.failRequestCount = failRequestCount;
        return this;
    }

    public Long getOutageAfterMillis() {
        return outageAfterMillis;
    }

    public HttpChaosProfileDTO setOutageAfterMillis(Long outageAfterMillis) {
        this.outageAfterMillis = outageAfterMillis;
        return this;
    }

    public Long getOutageDurationMillis() {
        return outageDurationMillis;
    }

    public HttpChaosProfileDTO setOutageDurationMillis(Long outageDurationMillis) {
        this.outageDurationMillis = outageDurationMillis;
        return this;
    }

    public Double getTruncateBodyAtFraction() {
        return truncateBodyAtFraction;
    }

    public HttpChaosProfileDTO setTruncateBodyAtFraction(Double truncateBodyAtFraction) {
        this.truncateBodyAtFraction = truncateBodyAtFraction;
        return this;
    }

    public Boolean getMalformedBody() {
        return malformedBody;
    }

    public HttpChaosProfileDTO setMalformedBody(Boolean malformedBody) {
        this.malformedBody = malformedBody;
        return this;
    }

    public Integer getSlowResponseChunkSize() {
        return slowResponseChunkSize;
    }

    public HttpChaosProfileDTO setSlowResponseChunkSize(Integer slowResponseChunkSize) {
        this.slowResponseChunkSize = slowResponseChunkSize;
        return this;
    }

    public DelayDTO getSlowResponseChunkDelay() {
        return slowResponseChunkDelay;
    }

    public HttpChaosProfileDTO setSlowResponseChunkDelay(DelayDTO slowResponseChunkDelay) {
        this.slowResponseChunkDelay = slowResponseChunkDelay;
        return this;
    }

    public String getQuotaName() {
        return quotaName;
    }

    public HttpChaosProfileDTO setQuotaName(String quotaName) {
        this.quotaName = quotaName;
        return this;
    }

    public Integer getQuotaLimit() {
        return quotaLimit;
    }

    public HttpChaosProfileDTO setQuotaLimit(Integer quotaLimit) {
        this.quotaLimit = quotaLimit;
        return this;
    }

    public Long getQuotaWindowMillis() {
        return quotaWindowMillis;
    }

    public HttpChaosProfileDTO setQuotaWindowMillis(Long quotaWindowMillis) {
        this.quotaWindowMillis = quotaWindowMillis;
        return this;
    }

    public Integer getQuotaErrorStatus() {
        return quotaErrorStatus;
    }

    public HttpChaosProfileDTO setQuotaErrorStatus(Integer quotaErrorStatus) {
        this.quotaErrorStatus = quotaErrorStatus;
        return this;
    }

    public Long getDegradationRampMillis() {
        return degradationRampMillis;
    }

    public HttpChaosProfileDTO setDegradationRampMillis(Long degradationRampMillis) {
        this.degradationRampMillis = degradationRampMillis;
        return this;
    }

    public Boolean getGraphqlErrors() {
        return graphqlErrors;
    }

    public HttpChaosProfileDTO setGraphqlErrors(Boolean graphqlErrors) {
        this.graphqlErrors = graphqlErrors;
        return this;
    }

    public String getGraphqlErrorMessage() {
        return graphqlErrorMessage;
    }

    public HttpChaosProfileDTO setGraphqlErrorMessage(String graphqlErrorMessage) {
        this.graphqlErrorMessage = graphqlErrorMessage;
        return this;
    }

    public String getGraphqlErrorCode() {
        return graphqlErrorCode;
    }

    public HttpChaosProfileDTO setGraphqlErrorCode(String graphqlErrorCode) {
        this.graphqlErrorCode = graphqlErrorCode;
        return this;
    }

    public Boolean getGraphqlNullifyData() {
        return graphqlNullifyData;
    }

    public HttpChaosProfileDTO setGraphqlNullifyData(Boolean graphqlNullifyData) {
        this.graphqlNullifyData = graphqlNullifyData;
        return this;
    }
}
