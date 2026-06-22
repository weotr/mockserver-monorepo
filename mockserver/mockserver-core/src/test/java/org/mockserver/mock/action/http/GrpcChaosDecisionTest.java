package org.mockserver.mock.action.http;

import org.junit.After;
import org.junit.Test;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.model.GrpcChaosProfile;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.model.GrpcChaosProfile.grpcChaosProfile;

public class GrpcChaosDecisionTest {

    private final HttpQuotaRegistry quotaRegistry = new HttpQuotaRegistry(System::currentTimeMillis);

    @After
    public void cleanup() {
        quotaRegistry.reset();
    }

    @Test
    public void shouldAlwaysFaultWithProbabilityOne() {
        GrpcChaosProfile profile = grpcChaosProfile()
            .withErrorProbability(1.0)
            .withErrorStatusCode("INTERNAL")
            .withErrorMessage("chaos injected");

        GrpcChaosDecision.GrpcFault fault = GrpcChaosDecision.evaluate(profile, 1, quotaRegistry);

        assertThat(fault, is(notNullValue()));
        assertThat(fault.getStatusCode(), is(GrpcStatusMapper.GrpcStatusCode.INTERNAL));
        assertThat(fault.getMessage(), is("chaos injected"));
    }

    @Test
    public void shouldNeverFaultWithProbabilityZero() {
        GrpcChaosProfile profile = grpcChaosProfile()
            .withErrorProbability(0.0);

        GrpcChaosDecision.GrpcFault fault = GrpcChaosDecision.evaluate(profile, 1, quotaRegistry);

        assertThat(fault, is(nullValue()));
    }

    @Test
    public void shouldDefaultToUnavailableWhenNoErrorStatusCodeSet() {
        GrpcChaosProfile profile = grpcChaosProfile()
            .withErrorProbability(1.0);

        GrpcChaosDecision.GrpcFault fault = GrpcChaosDecision.evaluate(profile, 1, quotaRegistry);

        assertThat(fault, is(notNullValue()));
        assertThat(fault.getStatusCode(), is(GrpcStatusMapper.GrpcStatusCode.UNAVAILABLE));
    }

    @Test
    public void shouldUseDefaultMessageWhenNoneConfigured() {
        GrpcChaosProfile profile = grpcChaosProfile()
            .withErrorProbability(1.0)
            .withErrorStatusCode("DEADLINE_EXCEEDED");

        GrpcChaosDecision.GrpcFault fault = GrpcChaosDecision.evaluate(profile, 1, quotaRegistry);

        assertThat(fault, is(notNullValue()));
        assertThat(fault.getMessage(), is(nullValue()));
    }

    @Test
    public void shouldRespectSucceedFirstWindow() {
        GrpcChaosProfile profile = grpcChaosProfile()
            .withErrorProbability(1.0)
            .withErrorStatusCode("UNAVAILABLE")
            .withSucceedFirst(3);

        // First 3 calls should not fault
        assertThat("match 1 should not fault", GrpcChaosDecision.evaluate(profile, 1, quotaRegistry), is(nullValue()));
        assertThat("match 2 should not fault", GrpcChaosDecision.evaluate(profile, 2, quotaRegistry), is(nullValue()));
        assertThat("match 3 should not fault", GrpcChaosDecision.evaluate(profile, 3, quotaRegistry), is(nullValue()));

        // 4th call should fault
        assertThat("match 4 should fault", GrpcChaosDecision.evaluate(profile, 4, quotaRegistry), is(notNullValue()));
    }

    @Test
    public void shouldRespectFailRequestCountWindow() {
        GrpcChaosProfile profile = grpcChaosProfile()
            .withErrorProbability(1.0)
            .withErrorStatusCode("UNAVAILABLE")
            .withSucceedFirst(2)
            .withFailRequestCount(3);

        // Match 1-2: not eligible (succeedFirst)
        assertThat(GrpcChaosDecision.evaluate(profile, 1, quotaRegistry), is(nullValue()));
        assertThat(GrpcChaosDecision.evaluate(profile, 2, quotaRegistry), is(nullValue()));

        // Match 3-5: eligible
        assertThat(GrpcChaosDecision.evaluate(profile, 3, quotaRegistry), is(notNullValue()));
        assertThat(GrpcChaosDecision.evaluate(profile, 4, quotaRegistry), is(notNullValue()));
        assertThat(GrpcChaosDecision.evaluate(profile, 5, quotaRegistry), is(notNullValue()));

        // Match 6+: not eligible (after window)
        assertThat(GrpcChaosDecision.evaluate(profile, 6, quotaRegistry), is(nullValue()));
    }

    @Test
    public void shouldReturnResourceExhaustedWhenQuotaExceeded() {
        GrpcChaosProfile profile = grpcChaosProfile()
            .withQuotaName("grpc-limit")
            .withQuotaLimit(2)
            .withQuotaWindowMillis(60_000L);

        // First two requests are within quota
        assertThat("first request within quota", GrpcChaosDecision.evaluate(profile, 1, quotaRegistry), is(nullValue()));
        assertThat("second request within quota", GrpcChaosDecision.evaluate(profile, 2, quotaRegistry), is(nullValue()));

        // Third request exceeds quota
        GrpcChaosDecision.GrpcFault fault = GrpcChaosDecision.evaluate(profile, 3, quotaRegistry);
        assertThat(fault, is(notNullValue()));
        assertThat(fault.getStatusCode(), is(GrpcStatusMapper.GrpcStatusCode.RESOURCE_EXHAUSTED));
        assertThat(fault.getMessage(), is("quota exceeded"));
    }

    @Test
    public void shouldReturnCustomMessageOnQuotaExceeded() {
        GrpcChaosProfile profile = grpcChaosProfile()
            .withQuotaName("custom-msg-quota")
            .withQuotaLimit(1)
            .withQuotaWindowMillis(60_000L)
            .withErrorMessage("rate limited");

        // First request OK
        assertThat(GrpcChaosDecision.evaluate(profile, 1, quotaRegistry), is(nullValue()));

        // Second exceeds
        GrpcChaosDecision.GrpcFault fault = GrpcChaosDecision.evaluate(profile, 2, quotaRegistry);
        assertThat(fault, is(notNullValue()));
        assertThat(fault.getStatusCode(), is(GrpcStatusMapper.GrpcStatusCode.RESOURCE_EXHAUSTED));
        assertThat(fault.getMessage(), is("rate limited"));
    }

    @Test
    public void shouldBeDeterministicWithFixedSeed() {
        GrpcChaosProfile profile = grpcChaosProfile()
            .withErrorProbability(1.0)
            .withSeed(42L)
            .withErrorStatusCode("INTERNAL");

        // With probability 1.0 and a seed, should always produce the same result
        GrpcChaosDecision.GrpcFault fault1 = GrpcChaosDecision.evaluate(profile, 1, quotaRegistry);
        GrpcChaosDecision.GrpcFault fault2 = GrpcChaosDecision.evaluate(profile, 2, quotaRegistry);

        assertThat(fault1.getStatusCode(), is(fault2.getStatusCode()));
        assertThat(fault1.getStatusCode(), is(GrpcStatusMapper.GrpcStatusCode.INTERNAL));
    }

    @Test
    public void shouldInjectRoughlyHalfTheTimeWithProbabilityOneHalf() {
        // Regression guard: previously a new Random(seed)/new Random() was constructed per request,
        // so the first draw was effectively constant — turning a 0.5 probability into an
        // all-or-nothing switch (0% or 100%). With the shared ChaosProbability draw an unseeded 0.5
        // probability must inject roughly half the time over many independent calls.
        GrpcChaosProfile profile = grpcChaosProfile()
            .withErrorProbability(0.5)
            .withErrorStatusCode("UNAVAILABLE");

        int iterations = 5_000;
        int faults = 0;
        for (int i = 0; i < iterations; i++) {
            // Use a count window of 1-and-out is not possible here; matchCount is always eligible.
            if (GrpcChaosDecision.evaluate(profile, 1, quotaRegistry) != null) {
                faults++;
            }
        }

        // With 5000 draws at p=0.5 the count is overwhelmingly within [40%, 60%]; the key assertion
        // is that it is neither ~0% nor ~100% (the pre-fix degenerate behaviour).
        assertThat("injection rate should be well above 0%", faults, is(greaterThan((int) (iterations * 0.40))));
        assertThat("injection rate should be well below 100%", faults, is(lessThan((int) (iterations * 0.60))));
    }

    @Test
    public void shouldHandleNullQuotaRegistry() {
        GrpcChaosProfile profile = grpcChaosProfile()
            .withQuotaName("test")
            .withQuotaLimit(1)
            .withQuotaWindowMillis(60_000L);

        // With null quota registry, quota is not enforced
        assertThat(GrpcChaosDecision.evaluate(profile, 1, null), is(nullValue()));
        assertThat(GrpcChaosDecision.evaluate(profile, 2, null), is(nullValue()));
    }

    @Test
    public void shouldHandleProfileWithNoFaults() {
        GrpcChaosProfile profile = grpcChaosProfile();
        assertThat(GrpcChaosDecision.evaluate(profile, 1, quotaRegistry), is(nullValue()));
    }

    @Test
    public void quotaTakesPriorityOverProbabilisticError() {
        GrpcChaosProfile profile = grpcChaosProfile()
            .withErrorProbability(1.0)
            .withErrorStatusCode("INTERNAL")
            .withQuotaName("priority-test")
            .withQuotaLimit(1)
            .withQuotaWindowMillis(60_000L);

        // First request: within quota, but probability fires -> INTERNAL
        GrpcChaosDecision.GrpcFault first = GrpcChaosDecision.evaluate(profile, 1, quotaRegistry);
        // Quota consumes 1 of 1 -> allowed, so probabilistic error fires
        assertThat(first, is(notNullValue()));
        assertThat(first.getStatusCode(), is(GrpcStatusMapper.GrpcStatusCode.INTERNAL));

        // Second request: quota exceeded -> RESOURCE_EXHAUSTED (takes priority)
        GrpcChaosDecision.GrpcFault second = GrpcChaosDecision.evaluate(profile, 2, quotaRegistry);
        assertThat(second, is(notNullValue()));
        assertThat(second.getStatusCode(), is(GrpcStatusMapper.GrpcStatusCode.RESOURCE_EXHAUSTED));
    }
}
