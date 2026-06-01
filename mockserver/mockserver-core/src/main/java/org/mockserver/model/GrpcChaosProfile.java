package org.mockserver.model;

import org.mockserver.grpc.GrpcStatusMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Declarative gRPC fault/chaos injection profile for probabilistically returning
 * gRPC error statuses (UNAVAILABLE, DEADLINE_EXCEEDED, RESOURCE_EXHAUSTED,
 * INTERNAL, etc.) on matched gRPC method calls, with latency, quota, and
 * count-window controls. This is distinct from the existing gRPC health-check
 * serving-status feature ({@link org.mockserver.grpc.GrpcHealthRegistry}).
 * <p>
 * Profiles are registered in the
 * {@link org.mockserver.mock.action.http.GrpcChaosRegistry} keyed by gRPC
 * service name, and applied by the {@code GrpcToHttpRequestHandler} Netty
 * handler before normal gRPC request conversion. A default profile (keyed by
 * empty string) applies to all services unless overridden by a service-specific
 * profile.
 * <p>
 * Count-based stateful fault window: {@code succeedFirst} and
 * {@code failRequestCount} define a window over the per-service 1-based
 * match count where chaos is eligible:
 * <ul>
 *   <li>Matches 1..succeedFirst are NOT eligible (chaos is suppressed).</li>
 *   <li>Matches (succeedFirst+1)..(succeedFirst+failRequestCount) ARE eligible.</li>
 *   <li>Matches beyond succeedFirst+failRequestCount recover (no chaos).</li>
 * </ul>
 * When both fields are {@code null} every match is eligible.
 * <p>
 * Stateful request quota: when {@code quotaName}, {@code quotaLimit} and
 * {@code quotaWindowMillis} are set, requests beyond {@code quotaLimit} within
 * the window are rejected with RESOURCE_EXHAUSTED — a deterministic, hard
 * rate limit (see {@link org.mockserver.mock.action.http.HttpQuotaRegistry}).
 * <p>
 * Determinism: with {@code errorProbability} of {@code 1.0} (always) or
 * {@code 0.0}/null (never) the error decision is fully deterministic. A
 * fractional probability draws once per request; set {@code seed} to make that
 * draw reproducible (note: a fixed seed yields the same decision every time).
 * <p>
 * Follows the model field/{@code withX}/getter convention so it round-trips
 * through Jackson without a bespoke (de)serializer.
 */
public class GrpcChaosProfile extends ObjectWithJsonToString {

    private int hashCode;
    private String errorStatusCode;    // gRPC status code NAME (e.g. "UNAVAILABLE"); default UNAVAILABLE when probability fires but code unset
    private String errorMessage;       // grpc-message text (optional)
    private Double errorProbability;   // 0.0-1.0; null/0 = never inject an error
    private Long seed;                 // optional, makes a fractional errorProbability reproducible
    private Long latencyMs;            // delay before the response (>= 0); null = no delay
    private Integer succeedFirst;      // first N matches are NOT eligible for chaos (>= 0; null = 0)
    private Integer failRequestCount;  // after succeedFirst, next M matches ARE eligible (>= 1; null = unlimited)
    private String quotaName;          // stateful quota: shared counter key
    private Integer quotaLimit;        // stateful quota: max requests allowed per window (>= 1)
    private Long quotaWindowMillis;    // stateful quota: window length in milliseconds (>= 1)
    private Boolean omitGrpcStatus;    // fault: omit grpc-status trailer entirely (broken/incomplete RPC)
    private Boolean corruptGrpcStatus; // fault: send non-numeric grpc-status value ("malformed") — a genuine protocol violation since grpc-status must be an integer
    private Map<String, String> customTrailers; // fault: inject arbitrary trailer key/value pairs
    private Integer abortAfterMessages; // fault: abort with ABORTED status when client-streaming message count >= threshold (>= 1)

    public static GrpcChaosProfile grpcChaosProfile() {
        return new GrpcChaosProfile();
    }

    public GrpcChaosProfile withErrorStatusCode(String errorStatusCode) {
        if (errorStatusCode != null) {
            // validate that it is a known gRPC status code name
            try {
                GrpcStatusMapper.GrpcStatusCode.valueOf(errorStatusCode.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("errorStatusCode must be a valid gRPC status code name, got '" + errorStatusCode + "'");
            }
        }
        this.errorStatusCode = errorStatusCode;
        this.hashCode = 0;
        return this;
    }

    public String getErrorStatusCode() {
        return errorStatusCode;
    }

    public GrpcChaosProfile withErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
        this.hashCode = 0;
        return this;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public GrpcChaosProfile withErrorProbability(Double errorProbability) {
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

    public GrpcChaosProfile withSeed(Long seed) {
        this.seed = seed;
        this.hashCode = 0;
        return this;
    }

    public Long getSeed() {
        return seed;
    }

    public GrpcChaosProfile withLatencyMs(Long latencyMs) {
        if (latencyMs != null && latencyMs < 0) {
            throw new IllegalArgumentException("latencyMs must be >= 0, got " + latencyMs);
        }
        this.latencyMs = latencyMs;
        this.hashCode = 0;
        return this;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public GrpcChaosProfile withSucceedFirst(Integer succeedFirst) {
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

    public GrpcChaosProfile withFailRequestCount(Integer failRequestCount) {
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

    public GrpcChaosProfile withQuotaName(String quotaName) {
        this.quotaName = quotaName;
        this.hashCode = 0;
        return this;
    }

    public String getQuotaName() {
        return quotaName;
    }

    public GrpcChaosProfile withQuotaLimit(Integer quotaLimit) {
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

    public GrpcChaosProfile withQuotaWindowMillis(Long quotaWindowMillis) {
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

    public GrpcChaosProfile withOmitGrpcStatus(Boolean omitGrpcStatus) {
        this.omitGrpcStatus = omitGrpcStatus;
        this.hashCode = 0;
        return this;
    }

    public Boolean getOmitGrpcStatus() {
        return omitGrpcStatus;
    }

    public GrpcChaosProfile withCorruptGrpcStatus(Boolean corruptGrpcStatus) {
        this.corruptGrpcStatus = corruptGrpcStatus;
        this.hashCode = 0;
        return this;
    }

    public Boolean getCorruptGrpcStatus() {
        return corruptGrpcStatus;
    }

    /**
     * Sets arbitrary trailer key/value pairs to inject on fault responses.
     * Accepts {@code null} (meaning "no custom trailers"). Rejects any entry
     * whose key or value contains {@code \r} or {@code \n} (header/response
     * splitting risk), and rejects null or empty keys.
     *
     * @throws IllegalArgumentException if any key is null/empty or any key/value contains CR/LF
     */
    public GrpcChaosProfile withCustomTrailers(Map<String, String> customTrailers) {
        if (customTrailers != null) {
            for (Map.Entry<String, String> entry : customTrailers.entrySet()) {
                String key = entry.getKey();
                if (key == null || key.isEmpty()) {
                    throw new IllegalArgumentException("customTrailers key must not be null or empty");
                }
                if (containsCrLf(key)) {
                    throw new IllegalArgumentException("customTrailers key must not contain CR or LF characters, got '" + key + "'");
                }
                if (!isValidHttp2HeaderToken(key)) {
                    throw new IllegalArgumentException("customTrailers key must be a valid lowercase HTTP/2 header token ([a-z0-9!#$%&'*+\\-.^_`|~]), got '" + key + "'");
                }
                String value = entry.getValue();
                if (value != null && containsCrLf(value)) {
                    throw new IllegalArgumentException("customTrailers value must not contain CR or LF characters, got value for key '" + key + "'");
                }
            }
        }
        this.customTrailers = customTrailers != null ? new LinkedHashMap<>(customTrailers) : null;
        this.hashCode = 0;
        return this;
    }

    private static boolean containsCrLf(String s) {
        return s.indexOf('\r') >= 0 || s.indexOf('\n') >= 0;
    }

    /**
     * Returns {@code true} when every character in {@code token} is a valid lowercase
     * HTTP/2 header-name character: {@code [a-z0-9!#$%&'*+\-.^_`|~]}.
     * HTTP/2 requires header names to be lowercase; an uppercase or illegal-char key
     * would fail obscurely at Netty write time.
     */
    private static boolean isValidHttp2HeaderToken(String token) {
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                continue;
            }
            if ("!#$%&'*+-.^_`|~".indexOf(c) >= 0) {
                continue;
            }
            return false;
        }
        return true;
    }

    public Map<String, String> getCustomTrailers() {
        return customTrailers == null ? null : java.util.Collections.unmodifiableMap(customTrailers);
    }

    /**
     * Sets the client-streaming message count threshold for abort injection.
     * Only meaningful for client-streaming (multi-frame) requests: when the
     * decoded gRPC frame count in the request body is &gt;= this threshold,
     * the handler responds with {@code ABORTED}. For unary (single-frame)
     * requests the message count is always 1, so setting this to 1 aborts
     * every request while values &gt; 1 only trigger on multi-frame bodies.
     *
     * @throws IllegalArgumentException if abortAfterMessages is non-null and &lt; 1
     */
    public GrpcChaosProfile withAbortAfterMessages(Integer abortAfterMessages) {
        if (abortAfterMessages != null && abortAfterMessages < 1) {
            throw new IllegalArgumentException("abortAfterMessages must be >= 1, got " + abortAfterMessages);
        }
        this.abortAfterMessages = abortAfterMessages;
        this.hashCode = 0;
        return this;
    }

    public Integer getAbortAfterMessages() {
        return abortAfterMessages;
    }

    /**
     * Returns {@code true} when the given 1-based match count falls within the
     * chaos-eligible window defined by {@code succeedFirst} and
     * {@code failRequestCount}. When both fields are {@code null} this returns
     * {@code true} for any {@code matchCount} (backward compatible).
     *
     * @param matchCount 1-based match count (0 when unknown)
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

    /**
     * Returns {@code true} when this profile has at least one fault-producing
     * field configured: an error probability, an error status code, a latency,
     * quota fields, trailer faults (omit/corrupt/custom), or stream abort.
     */
    public boolean hasAnyFault() {
        return (errorProbability != null && errorProbability > 0.0)
            || errorStatusCode != null
            || (latencyMs != null && latencyMs > 0)
            || (quotaName != null && quotaLimit != null && quotaWindowMillis != null)
            || Boolean.TRUE.equals(omitGrpcStatus)
            || Boolean.TRUE.equals(corruptGrpcStatus)
            || (customTrailers != null && !customTrailers.isEmpty())
            || abortAfterMessages != null;
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
        GrpcChaosProfile that = (GrpcChaosProfile) o;
        return Objects.equals(errorStatusCode, that.errorStatusCode)
            && Objects.equals(errorMessage, that.errorMessage)
            && Objects.equals(errorProbability, that.errorProbability)
            && Objects.equals(seed, that.seed)
            && Objects.equals(latencyMs, that.latencyMs)
            && Objects.equals(succeedFirst, that.succeedFirst)
            && Objects.equals(failRequestCount, that.failRequestCount)
            && Objects.equals(quotaName, that.quotaName)
            && Objects.equals(quotaLimit, that.quotaLimit)
            && Objects.equals(quotaWindowMillis, that.quotaWindowMillis)
            && Objects.equals(omitGrpcStatus, that.omitGrpcStatus)
            && Objects.equals(corruptGrpcStatus, that.corruptGrpcStatus)
            && Objects.equals(customTrailers, that.customTrailers)
            && Objects.equals(abortAfterMessages, that.abortAfterMessages);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(errorStatusCode, errorMessage, errorProbability, seed, latencyMs, succeedFirst, failRequestCount, quotaName, quotaLimit, quotaWindowMillis, omitGrpcStatus, corruptGrpcStatus, customTrailers, abortAfterMessages);
        }
        return hashCode;
    }
}
