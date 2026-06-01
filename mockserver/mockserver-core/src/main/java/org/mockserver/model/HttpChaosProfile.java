package org.mockserver.model;

import java.util.Objects;

/**
 * Declarative HTTP fault/chaos injection for mocked and forwarded responses:
 * probabilistic connection-drop injection, error status injection (e.g. 500,
 * 503, 429 with an optional {@code Retry-After} header), latency injection, and
 * response-body corruption ({@code truncateBodyAtFraction} keeps only a leading
 * fraction of the body bytes; {@code malformedBody} appends a broken-JSON
 * fragment) for testing client-side body-parsing resilience, and a slow
 * ("dribbled") response ({@code slowResponseChunkSize} + {@code slowResponseChunkDelay}
 * send the body in small chunks with a delay between each) for testing read
 * timeouts on a trickling response.
 * <p>
 * Body corruption and slow-response are deterministic (no probability draw):
 * they apply to the real (non-error) response whenever the count and time
 * windows are eligible, and are skipped for streaming bodies. Connection-drop
 * and error injection take priority — when an error is injected the synthetic
 * error body is returned uncorrupted and at full speed.
 * <p>
 * It also carries an optional <em>stateful</em> request quota (a fixed-window
 * rate limit): when {@code quotaName}, {@code quotaLimit} and
 * {@code quotaWindowMillis} are set, requests beyond {@code quotaLimit} within
 * the window are rejected with {@code quotaErrorStatus} (default 429) and the
 * {@code retryAfter} header. Unlike the probabilistic error this is deterministic
 * and counts real requests across the process (see
 * {@link org.mockserver.mock.action.http.HttpQuotaRegistry}); expectations sharing
 * a {@code quotaName} share one counter. The quota gate takes priority over the
 * probabilistic error and the body/slow faults (after connection-drop).
 * <p>
 * It can also model <em>gradual degradation</em>: when {@code degradationRampMillis}
 * is set, the probabilistic fault rates ({@code errorProbability} and
 * {@code dropConnectionProbability}) ramp linearly from {@code 0.0} at the
 * expectation's first match up to their configured values once the ramp duration
 * has elapsed, so a dependency appears to deteriorate over time (useful for
 * alerting / SLO-burn tests). The ramp is measured with the controllable clock
 * (deterministic under freeze/advance) and does not affect the deterministic
 * faults (latency, body corruption, slow response, quota).
 * <p>
 * Attach to an {@link org.mockserver.mock.Expectation} via
 * {@code expectation.withChaos(httpChaosProfile()...)} to inject faults into
 * the following action types:
 * <ul>
 *   <li>Mocked responses: RESPONSE, RESPONSE_TEMPLATE, RESPONSE_CLASS_CALLBACK</li>
 *   <li>Forward actions: FORWARD, FORWARD_TEMPLATE, FORWARD_CLASS_CALLBACK,
 *       FORWARD_REPLACE, FORWARD_VALIDATE</li>
 * </ul>
 * Not yet covered: RESPONSE_OBJECT_CALLBACK and FORWARD_OBJECT_CALLBACK (both
 * use their own callback-driven write path) and the anonymous/unmatched
 * proxy-pass path.
 * <p>
 * Determinism: with {@code errorProbability} of {@code 1.0} (always) or
 * {@code 0.0}/null (never) the error decision is fully deterministic. A
 * fractional probability draws once per response; set {@code seed} to make that
 * single draw reproducible (note: a fixed seed yields the same decision every
 * time).
 * <p>
 * Count-based stateful fault window: {@code succeedFirst} and
 * {@code failRequestCount} define a window over the expectation's 1-based
 * match count where chaos is eligible:
 * <ul>
 *   <li>Matches 1..succeedFirst are NOT eligible (chaos is suppressed).</li>
 *   <li>Matches (succeedFirst+1)..(succeedFirst+failRequestCount) ARE eligible.</li>
 *   <li>Matches beyond succeedFirst+failRequestCount recover (no chaos).</li>
 * </ul>
 * When both fields are {@code null} every match is eligible, preserving
 * backward compatibility. The window check is deterministic and composes
 * with the probabilistic error draw: a match must be within the window AND
 * pass the probability check to receive an injected fault.
 * <p>
 * Time-based outage window: {@code outageAfterMillis} and
 * {@code outageDurationMillis} define a self-healing window, measured relative
 * to the expectation's first match, during which chaos is active. The window
 * opens {@code outageAfterMillis} ms after the first match and (when a duration
 * is set) closes after {@code outageDurationMillis} ms, after which the service
 * behaves normally again. The window is measured with the controllable clock
 * ({@link org.mockserver.time.TimeService}), so freezing/advancing the clock
 * (e.g. via {@code PUT /mockserver/clock}) makes it deterministic in tests. It
 * composes with the count window and the probability draws: a fault fires only
 * when the request is inside the time window AND the count window AND the draw
 * passes. When both fields are {@code null} there is no time gate.
 * <p>
 * Follows the model field/{@code withX}/getter convention so it round-trips
 * without a bespoke (de)serializer.
 */
public class HttpChaosProfile extends ObjectWithJsonToString {

    private int hashCode;
    private Integer errorStatus;       // HTTP status to inject (e.g. 500, 503, 429)
    private String retryAfter;         // optional Retry-After header value on injected error
    private Double errorProbability;   // 0.0-1.0; null/0 = never inject an error
    private Double dropConnectionProbability; // 0.0-1.0; null/0 = never drop the connection
    private Delay latency;             // optional injected latency
    private Long seed;                 // optional, makes a fractional errorProbability reproducible
    private Integer succeedFirst;      // first N matches are NOT eligible for chaos (>= 0; null = 0)
    private Integer failRequestCount;  // after succeedFirst, next M matches ARE eligible (>= 1; null = unlimited)
    private Long outageAfterMillis;    // chaos starts this many ms after the first match (>= 0; null = 0)
    private Long outageDurationMillis; // after outageAfterMillis, chaos stays active for this long then self-heals (>= 1; null = unbounded)
    private Double truncateBodyAtFraction; // 0.0-1.0 fraction of the response body bytes to keep; null = don't truncate
    private Boolean malformedBody;     // append a broken-JSON fragment to corrupt the response body; null/false = don't corrupt
    private Integer slowResponseChunkSize; // dribble the response body in chunks of this many bytes (>= 1); null = don't dribble
    private Delay slowResponseChunkDelay;  // delay between dribbled chunks; required (with chunkSize) to slow the response
    private String quotaName;          // stateful quota: shared counter key
    private Integer quotaLimit;        // stateful quota: max requests allowed per window (>= 1)
    private Long quotaWindowMillis;    // stateful quota: window length in milliseconds (>= 1)
    private Integer quotaErrorStatus;  // stateful quota: status when exceeded (default 429)
    private Long degradationRampMillis; // gradual degradation: ramp errorProbability/dropConnectionProbability from 0 to full over this many ms from first match (>= 1)
    private Boolean graphqlErrors;     // when true, rewrite the response body as a GraphQL error envelope (HTTP 200 + {"data":null,"errors":[...]})
    private String graphqlErrorMessage; // the message in errors[0].message; default "simulated GraphQL error" when graphqlErrors=true and unset
    private String graphqlErrorCode;   // optional value for errors[0].extensions.code (e.g. "INTERNAL_SERVER_ERROR"); omit extensions when null
    private Boolean graphqlNullifyData; // when true (default), data is null; when false, attempt to preserve original body JSON as data

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

    public HttpChaosProfile withDropConnectionProbability(Double dropConnectionProbability) {
        if (dropConnectionProbability != null && (Double.isNaN(dropConnectionProbability) || dropConnectionProbability < 0.0 || dropConnectionProbability > 1.0)) {
            throw new IllegalArgumentException("dropConnectionProbability must be between 0.0 and 1.0, got " + dropConnectionProbability);
        }
        this.dropConnectionProbability = dropConnectionProbability;
        this.hashCode = 0;
        return this;
    }

    public Double getDropConnectionProbability() {
        return dropConnectionProbability;
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

    public HttpChaosProfile withSucceedFirst(Integer succeedFirst) {
        if (succeedFirst != null && succeedFirst < 0) {
            throw new IllegalArgumentException("succeedFirst must be >= 0, got " + succeedFirst);
        }
        this.succeedFirst = succeedFirst;
        this.hashCode = 0;
        return this;
    }

    public Integer getSucceedFirst() {
        return succeedFirst;
    }

    public HttpChaosProfile withFailRequestCount(Integer failRequestCount) {
        if (failRequestCount != null && failRequestCount < 1) {
            throw new IllegalArgumentException("failRequestCount must be >= 1, got " + failRequestCount);
        }
        this.failRequestCount = failRequestCount;
        this.hashCode = 0;
        return this;
    }

    public Integer getFailRequestCount() {
        return failRequestCount;
    }

    public HttpChaosProfile withOutageAfterMillis(Long outageAfterMillis) {
        if (outageAfterMillis != null && outageAfterMillis < 0) {
            throw new IllegalArgumentException("outageAfterMillis must be >= 0, got " + outageAfterMillis);
        }
        this.outageAfterMillis = outageAfterMillis;
        this.hashCode = 0;
        return this;
    }

    public Long getOutageAfterMillis() {
        return outageAfterMillis;
    }

    public HttpChaosProfile withOutageDurationMillis(Long outageDurationMillis) {
        if (outageDurationMillis != null && outageDurationMillis < 1) {
            throw new IllegalArgumentException("outageDurationMillis must be >= 1, got " + outageDurationMillis);
        }
        this.outageDurationMillis = outageDurationMillis;
        this.hashCode = 0;
        return this;
    }

    public Long getOutageDurationMillis() {
        return outageDurationMillis;
    }

    public HttpChaosProfile withTruncateBodyAtFraction(Double truncateBodyAtFraction) {
        if (truncateBodyAtFraction != null && (Double.isNaN(truncateBodyAtFraction) || truncateBodyAtFraction < 0.0 || truncateBodyAtFraction > 1.0)) {
            throw new IllegalArgumentException("truncateBodyAtFraction must be between 0.0 and 1.0, got " + truncateBodyAtFraction);
        }
        this.truncateBodyAtFraction = truncateBodyAtFraction;
        this.hashCode = 0;
        return this;
    }

    public Double getTruncateBodyAtFraction() {
        return truncateBodyAtFraction;
    }

    public HttpChaosProfile withMalformedBody(Boolean malformedBody) {
        this.malformedBody = malformedBody;
        this.hashCode = 0;
        return this;
    }

    public Boolean getMalformedBody() {
        return malformedBody;
    }

    public HttpChaosProfile withSlowResponseChunkSize(Integer slowResponseChunkSize) {
        if (slowResponseChunkSize != null && slowResponseChunkSize < 1) {
            throw new IllegalArgumentException("slowResponseChunkSize must be >= 1, got " + slowResponseChunkSize);
        }
        this.slowResponseChunkSize = slowResponseChunkSize;
        this.hashCode = 0;
        return this;
    }

    public Integer getSlowResponseChunkSize() {
        return slowResponseChunkSize;
    }

    public HttpChaosProfile withSlowResponseChunkDelay(Delay slowResponseChunkDelay) {
        this.slowResponseChunkDelay = slowResponseChunkDelay;
        this.hashCode = 0;
        return this;
    }

    public Delay getSlowResponseChunkDelay() {
        return slowResponseChunkDelay;
    }

    public HttpChaosProfile withQuotaName(String quotaName) {
        this.quotaName = quotaName;
        this.hashCode = 0;
        return this;
    }

    public String getQuotaName() {
        return quotaName;
    }

    public HttpChaosProfile withQuotaLimit(Integer quotaLimit) {
        if (quotaLimit != null && quotaLimit < 1) {
            throw new IllegalArgumentException("quotaLimit must be >= 1, got " + quotaLimit);
        }
        this.quotaLimit = quotaLimit;
        this.hashCode = 0;
        return this;
    }

    public Integer getQuotaLimit() {
        return quotaLimit;
    }

    public HttpChaosProfile withQuotaWindowMillis(Long quotaWindowMillis) {
        if (quotaWindowMillis != null && quotaWindowMillis < 1) {
            throw new IllegalArgumentException("quotaWindowMillis must be >= 1, got " + quotaWindowMillis);
        }
        this.quotaWindowMillis = quotaWindowMillis;
        this.hashCode = 0;
        return this;
    }

    public Long getQuotaWindowMillis() {
        return quotaWindowMillis;
    }

    public HttpChaosProfile withQuotaErrorStatus(Integer quotaErrorStatus) {
        if (quotaErrorStatus != null && (quotaErrorStatus < 100 || quotaErrorStatus > 599)) {
            throw new IllegalArgumentException("quotaErrorStatus must be between 100 and 599, got " + quotaErrorStatus);
        }
        this.quotaErrorStatus = quotaErrorStatus;
        this.hashCode = 0;
        return this;
    }

    public Integer getQuotaErrorStatus() {
        return quotaErrorStatus;
    }

    public HttpChaosProfile withDegradationRampMillis(Long degradationRampMillis) {
        if (degradationRampMillis != null && degradationRampMillis < 1) {
            throw new IllegalArgumentException("degradationRampMillis must be >= 1, got " + degradationRampMillis);
        }
        this.degradationRampMillis = degradationRampMillis;
        this.hashCode = 0;
        return this;
    }

    public Long getDegradationRampMillis() {
        return degradationRampMillis;
    }

    public HttpChaosProfile withGraphqlErrors(Boolean graphqlErrors) {
        this.graphqlErrors = graphqlErrors;
        this.hashCode = 0;
        return this;
    }

    public Boolean getGraphqlErrors() {
        return graphqlErrors;
    }

    public HttpChaosProfile withGraphqlErrorMessage(String graphqlErrorMessage) {
        this.graphqlErrorMessage = graphqlErrorMessage;
        this.hashCode = 0;
        return this;
    }

    public String getGraphqlErrorMessage() {
        return graphqlErrorMessage;
    }

    public HttpChaosProfile withGraphqlErrorCode(String graphqlErrorCode) {
        this.graphqlErrorCode = graphqlErrorCode;
        this.hashCode = 0;
        return this;
    }

    public String getGraphqlErrorCode() {
        return graphqlErrorCode;
    }

    public HttpChaosProfile withGraphqlNullifyData(Boolean graphqlNullifyData) {
        this.graphqlNullifyData = graphqlNullifyData;
        this.hashCode = 0;
        return this;
    }

    public Boolean getGraphqlNullifyData() {
        return graphqlNullifyData;
    }

    /**
     * Returns the gradual-degradation ramp factor in {@code [0.0, 1.0]} for the
     * given timing. When {@code degradationRampMillis} is {@code null} there is no
     * ramp and this returns {@code 1.0} (faults at full configured rate). Otherwise
     * the factor climbs linearly from {@code 0.0} at the first match to {@code 1.0}
     * once {@code degradationRampMillis} has elapsed (and stays at {@code 1.0}
     * after). When the first-match instant is unknown ({@code <= 0}) the factor is
     * {@code 1.0} (degenerate — no ramp data, so do not suppress faults). The
     * instants come from the controllable clock so the ramp is deterministic under
     * clock freeze/advance.
     *
     * @param firstMatchEpochMillis epoch-ms of the expectation's first match (0 when not yet recorded)
     * @param nowEpochMillis        the current epoch-ms (from the controllable clock)
     */
    public double degradationFactor(long firstMatchEpochMillis, long nowEpochMillis) {
        if (degradationRampMillis == null) {
            return 1.0;
        }
        if (firstMatchEpochMillis <= 0L) {
            return 1.0;
        }
        long elapsed = nowEpochMillis - firstMatchEpochMillis;
        if (elapsed <= 0L) {
            return 0.0;
        }
        if (elapsed >= degradationRampMillis) {
            return 1.0;
        }
        return (double) elapsed / (double) degradationRampMillis;
    }

    /**
     * Returns a copy of this profile with all fields duplicated. Used to derive a
     * transient, gradually-degraded variant without mutating the shared profile
     * attached to the expectation.
     */
    public HttpChaosProfile copy() {
        return httpChaosProfile()
            .withErrorStatus(errorStatus)
            .withRetryAfter(retryAfter)
            .withErrorProbability(errorProbability)
            .withDropConnectionProbability(dropConnectionProbability)
            .withLatency(latency)
            .withSeed(seed)
            .withSucceedFirst(succeedFirst)
            .withFailRequestCount(failRequestCount)
            .withOutageAfterMillis(outageAfterMillis)
            .withOutageDurationMillis(outageDurationMillis)
            .withTruncateBodyAtFraction(truncateBodyAtFraction)
            .withMalformedBody(malformedBody)
            .withSlowResponseChunkSize(slowResponseChunkSize)
            .withSlowResponseChunkDelay(slowResponseChunkDelay)
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

    /**
     * Returns {@code true} when the request falls within the time-based outage
     * window defined by {@code outageAfterMillis} and {@code outageDurationMillis},
     * measured relative to the expectation's first match. When both fields are
     * {@code null} there is no time gate and this returns {@code true} (backward
     * compatible). The window opens {@code outageAfterMillis} ms after the first
     * match and, when {@code outageDurationMillis} is set, closes (self-heals)
     * after that duration. The instants are supplied by the controllable clock
     * ({@link org.mockserver.time.TimeService}) so the window is deterministic
     * under clock freeze/advance.
     *
     * @param firstMatchEpochMillis epoch-ms of the expectation's first match (0 when not yet recorded)
     * @param nowEpochMillis        the current epoch-ms (from the controllable clock)
     * @return {@code true} if this request is inside the outage window (or there is no window)
     */
    public boolean timeWindowEligible(long firstMatchEpochMillis, long nowEpochMillis) {
        if (outageAfterMillis == null && outageDurationMillis == null) {
            return true;
        }
        if (firstMatchEpochMillis <= 0L) {
            return true;
        }
        long elapsed = nowEpochMillis - firstMatchEpochMillis;
        long start = outageAfterMillis != null ? outageAfterMillis : 0L;
        if (elapsed < start) {
            return false;
        }
        if (outageDurationMillis != null && elapsed >= start + outageDurationMillis) {
            return false;
        }
        return true;
    }

    /**
     * Returns {@code true} when the given 1-based match count falls within the
     * chaos-eligible window defined by {@code succeedFirst} and
     * {@code failRequestCount}. When both fields are {@code null} this returns
     * {@code true} for any {@code matchCount} (backward compatible), including
     * {@code matchCount == 0} which the handler passes when chaos is null (the
     * no-chaos overloads).
     *
     * @param matchCount 1-based match count from the expectation (0 when unknown)
     * @return {@code true} if this match is eligible for chaos injection
     */
    public boolean countWindowEligible(int matchCount) {
        if (succeedFirst == null && failRequestCount == null) {
            return true;
        }
        int after = succeedFirst != null ? succeedFirst : 0;
        if (matchCount <= after) {
            return false;
        }
        if (failRequestCount != null && (long) matchCount > (long) after + failRequestCount) {
            return false;
        }
        return true;
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
            Objects.equals(dropConnectionProbability, that.dropConnectionProbability) &&
            Objects.equals(latency, that.latency) &&
            Objects.equals(seed, that.seed) &&
            Objects.equals(succeedFirst, that.succeedFirst) &&
            Objects.equals(failRequestCount, that.failRequestCount) &&
            Objects.equals(outageAfterMillis, that.outageAfterMillis) &&
            Objects.equals(outageDurationMillis, that.outageDurationMillis) &&
            Objects.equals(truncateBodyAtFraction, that.truncateBodyAtFraction) &&
            Objects.equals(malformedBody, that.malformedBody) &&
            Objects.equals(slowResponseChunkSize, that.slowResponseChunkSize) &&
            Objects.equals(slowResponseChunkDelay, that.slowResponseChunkDelay) &&
            Objects.equals(quotaName, that.quotaName) &&
            Objects.equals(quotaLimit, that.quotaLimit) &&
            Objects.equals(quotaWindowMillis, that.quotaWindowMillis) &&
            Objects.equals(quotaErrorStatus, that.quotaErrorStatus) &&
            Objects.equals(degradationRampMillis, that.degradationRampMillis) &&
            Objects.equals(graphqlErrors, that.graphqlErrors) &&
            Objects.equals(graphqlErrorMessage, that.graphqlErrorMessage) &&
            Objects.equals(graphqlErrorCode, that.graphqlErrorCode) &&
            Objects.equals(graphqlNullifyData, that.graphqlNullifyData);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(errorStatus, retryAfter, errorProbability, dropConnectionProbability, latency, seed, succeedFirst, failRequestCount, outageAfterMillis, outageDurationMillis, truncateBodyAtFraction, malformedBody, slowResponseChunkSize, slowResponseChunkDelay, quotaName, quotaLimit, quotaWindowMillis, quotaErrorStatus, degradationRampMillis, graphqlErrors, graphqlErrorMessage, graphqlErrorCode, graphqlNullifyData);
        }
        return hashCode;
    }
}
