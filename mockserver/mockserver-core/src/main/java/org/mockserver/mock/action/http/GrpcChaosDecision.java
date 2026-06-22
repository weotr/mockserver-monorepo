package org.mockserver.mock.action.http;

import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.model.ChaosProbability;
import org.mockserver.model.GrpcChaosProfile;

/**
 * Evaluates a {@link GrpcChaosProfile} against the current request context
 * (match count, quota state) and decides whether to inject a gRPC fault.
 * Returns either {@code null} (no fault, pass through to normal handling) or
 * a {@link GrpcFault} describing the gRPC status code and message to inject.
 *
 * <p>Decision priority:
 * <ol>
 *   <li>Count window: if the match count is outside the eligible window,
 *       no fault is injected.</li>
 *   <li>Quota: if quota fields are configured and the limit is exceeded,
 *       RESOURCE_EXHAUSTED is returned deterministically.</li>
 *   <li>Probability: if a seeded/unseeded random draw is below
 *       {@code errorProbability}, the configured (or default UNAVAILABLE)
 *       error status is returned.</li>
 * </ol>
 */
public class GrpcChaosDecision {

    private GrpcChaosDecision() {
        // utility class
    }

    /**
     * The outcome of a gRPC chaos fault decision.
     */
    public static final class GrpcFault {
        private final GrpcStatusMapper.GrpcStatusCode statusCode;
        private final String message;

        public GrpcFault(GrpcStatusMapper.GrpcStatusCode statusCode, String message) {
            this.statusCode = statusCode;
            this.message = message;
        }

        public GrpcStatusMapper.GrpcStatusCode getStatusCode() {
            return statusCode;
        }

        public String getMessage() {
            return message;
        }
    }

    /**
     * Evaluate the given profile and decide whether to inject a gRPC fault.
     *
     * @param profile    the gRPC chaos profile (must not be null)
     * @param matchCount 1-based per-service match count
     * @param quotaRegistry the quota registry for deterministic rate limiting (may be null for no-quota scenarios)
     * @return a {@link GrpcFault} to inject, or {@code null} if no fault should be injected
     */
    public static GrpcFault evaluate(GrpcChaosProfile profile, int matchCount, HttpQuotaRegistry quotaRegistry) {
        // 1. Count window check
        if (!profile.countWindowEligible(matchCount)) {
            return null;
        }

        // 2. Quota check (deterministic, takes priority over probabilistic error)
        if (profile.getQuotaName() != null && profile.getQuotaLimit() != null && profile.getQuotaWindowMillis() != null) {
            if (quotaRegistry != null && !quotaRegistry.tryAcquire(profile.getQuotaName(), profile.getQuotaLimit(), profile.getQuotaWindowMillis())) {
                return new GrpcFault(
                    GrpcStatusMapper.GrpcStatusCode.RESOURCE_EXHAUSTED,
                    profile.getErrorMessage() != null ? profile.getErrorMessage() : "quota exceeded"
                );
            }
        }

        // 3. Probabilistic error injection.
        //    Route through the shared ChaosProbability helper so the draw is sampled
        //    consistently with the HTTP/TCP/LLM chaos paths: an unseeded profile uses
        //    ThreadLocalRandom (so a 0.5 probability injects ~50% of the time rather than
        //    always drawing the same first value from a per-request Random), and a seeded
        //    profile remains reproducible.
        Double errorProbability = profile.getErrorProbability();
        if (errorProbability != null && errorProbability > 0.0) {
            if (ChaosProbability.shouldInject(errorProbability, profile.getSeed())) {
                GrpcStatusMapper.GrpcStatusCode statusCode = GrpcStatusMapper.GrpcStatusCode.UNAVAILABLE;
                if (profile.getErrorStatusCode() != null) {
                    statusCode = GrpcStatusMapper.GrpcStatusCode.valueOf(profile.getErrorStatusCode().toUpperCase());
                }
                return new GrpcFault(
                    statusCode,
                    profile.getErrorMessage()
                );
            }
        }

        return null;
    }
}
