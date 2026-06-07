package org.mockserver.mock.action.http;

import org.junit.After;
import org.junit.Test;
import org.mockserver.model.GrpcChaosProfile;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.model.GrpcChaosProfile.grpcChaosProfile;

public class GrpcChaosRegistryTest {

    @After
    public void cleanup() {
        GrpcChaosRegistry.getInstance().reset();
    }

    @Test
    public void shouldRegisterAndResolveByService() {
        GrpcChaosProfile profile = grpcChaosProfile().withErrorProbability(1.0).withErrorStatusCode("UNAVAILABLE");
        GrpcChaosRegistry.getInstance().put("my.Service", profile);
        assertThat(GrpcChaosRegistry.getInstance().get("my.Service"), is(profile));
    }

    @Test
    public void shouldMatchServiceCaseInsensitivelyAndTrimmed() {
        GrpcChaosProfile profile = grpcChaosProfile().withErrorProbability(0.5);
        GrpcChaosRegistry.getInstance().put("My.Service", profile);
        assertThat("lower-cased lookup", GrpcChaosRegistry.getInstance().get("my.service"), is(profile));
        assertThat("lookup with whitespace", GrpcChaosRegistry.getInstance().get("  my.service  "), is(profile));
    }

    @Test
    public void shouldFallBackToDefaultProfile() {
        GrpcChaosProfile defaultProfile = grpcChaosProfile().withErrorProbability(0.3);
        GrpcChaosRegistry.getInstance().put("", defaultProfile);
        // no service-specific profile registered — should fall back to default
        assertThat(GrpcChaosRegistry.getInstance().get("any.Service"), is(defaultProfile));
    }

    @Test
    public void shouldPreferServiceSpecificOverDefault() {
        GrpcChaosProfile defaultProfile = grpcChaosProfile().withErrorProbability(0.3);
        GrpcChaosProfile specific = grpcChaosProfile().withErrorProbability(0.9);
        GrpcChaosRegistry.getInstance().put("", defaultProfile);
        GrpcChaosRegistry.getInstance().put("my.Service", specific);
        assertThat(GrpcChaosRegistry.getInstance().get("my.Service"), is(specific));
    }

    @Test
    public void shouldReturnNullForUnknownServiceWithNoDefault() {
        assertThat(GrpcChaosRegistry.getInstance().get("nope"), is(nullValue()));
        assertThat(GrpcChaosRegistry.getInstance().get(null), is(nullValue()));
    }

    @Test
    public void shouldIgnoreNullProfile() {
        GrpcChaosRegistry.getInstance().put("svc", null);
        assertThat(GrpcChaosRegistry.getInstance().get("svc"), is(nullValue()));
    }

    @Test
    public void shouldRemoveService() {
        GrpcChaosRegistry.getInstance().put("my.Service", grpcChaosProfile().withErrorProbability(1.0));
        GrpcChaosRegistry.getInstance().remove("MY.service");
        assertThat(GrpcChaosRegistry.getInstance().get("my.service"), is(nullValue()));
    }

    @Test
    public void shouldClearAllOnReset() {
        GrpcChaosRegistry.getInstance().put("a", grpcChaosProfile().withErrorProbability(1.0));
        GrpcChaosRegistry.getInstance().put("b", grpcChaosProfile().withErrorProbability(0.5));
        GrpcChaosRegistry.getInstance().reset();
        assertThat(GrpcChaosRegistry.getInstance().entries().isEmpty(), is(true));
    }

    @Test
    public void shouldReturnActiveCount() {
        assertThat(GrpcChaosRegistry.getInstance().activeCount(), is(0));
        GrpcChaosRegistry.getInstance().put("a", grpcChaosProfile().withErrorProbability(1.0));
        GrpcChaosRegistry.getInstance().put("b", grpcChaosProfile().withErrorProbability(0.5));
        assertThat(GrpcChaosRegistry.getInstance().activeCount(), is(2));
    }

    // --- TTL / auto-revert tests (use a controllable clock via a local registry instance) ---

    private static final class FakeClock {
        private final AtomicLong now = new AtomicLong(1_000L);

        long get() {
            return now.get();
        }

        void advance(long millis) {
            now.addAndGet(millis);
        }
    }

    @Test
    public void shouldExpireAfterTtlOnGet() {
        FakeClock clock = new FakeClock();
        GrpcChaosRegistry registry = new GrpcChaosRegistry(clock::get);
        GrpcChaosProfile profile = grpcChaosProfile().withErrorProbability(1.0);
        registry.put("my.Service", profile, 5_000L);

        assertThat("within ttl", registry.get("my.service"), is(profile));
        clock.advance(4_999L);
        assertThat("just before expiry", registry.get("my.service"), is(profile));
        clock.advance(1L);
        assertThat("at/after expiry auto-reverts", registry.get("my.service"), is(nullValue()));
    }

    @Test
    public void shouldNotExpireWhenTtlIsZeroOrAbsent() {
        FakeClock clock = new FakeClock();
        GrpcChaosRegistry registry = new GrpcChaosRegistry(clock::get);
        registry.put("a", grpcChaosProfile().withErrorProbability(1.0)); // no ttl
        registry.put("b", grpcChaosProfile().withErrorProbability(0.5), 0L); // ttl 0 = no expiry
        clock.advance(1_000_000L);
        assertThat(registry.get("a"), is(notNullValue()));
        assertThat(registry.get("b"), is(notNullValue()));
    }

    @Test
    public void entriesExcludeExpired() {
        FakeClock clock = new FakeClock();
        GrpcChaosRegistry registry = new GrpcChaosRegistry(clock::get);
        registry.put("ephemeral", grpcChaosProfile().withErrorProbability(1.0), 5_000L);
        registry.put("persistent", grpcChaosProfile().withErrorProbability(0.5)); // no ttl

        assertThat(registry.entries().size(), is(2));

        clock.advance(5_000L); // ephemeral expires
        assertThat(registry.entries().keySet(), is(Collections.singleton("persistent")));
    }

    @Test
    public void ttlRemainingMillisReportsOnlyTtlBearingLiveEntries() {
        FakeClock clock = new FakeClock();
        GrpcChaosRegistry registry = new GrpcChaosRegistry(clock::get);
        registry.put("ephemeral", grpcChaosProfile().withErrorProbability(1.0), 5_000L);
        registry.put("persistent", grpcChaosProfile().withErrorProbability(0.5)); // no ttl -> not reported

        assertThat(registry.ttlRemainingMillis().keySet(), is(Collections.singleton("ephemeral")));
        assertThat(registry.ttlRemainingMillis().get("ephemeral"), is(5_000L));
        clock.advance(2_000L);
        assertThat(registry.ttlRemainingMillis().get("ephemeral"), is(3_000L));
        clock.advance(3_000L); // expired
        assertThat(registry.ttlRemainingMillis().isEmpty(), is(true));
    }

    @Test
    public void hugeTtlSaturatesInsteadOfOverflowing() {
        FakeClock clock = new FakeClock();
        GrpcChaosRegistry registry = new GrpcChaosRegistry(clock::get);
        GrpcChaosProfile profile = grpcChaosProfile().withErrorProbability(1.0);
        registry.put("my.service", profile, Long.MAX_VALUE);
        clock.advance(1_000_000_000L);
        assertThat("a saturating expiry never silently flips to non-expiring-via-negative", registry.get("my.service"), is(profile));
    }

    @Test
    public void shouldPatchExistingProfile() {
        FakeClock clock = new FakeClock();
        GrpcChaosRegistry registry = new GrpcChaosRegistry(clock::get);
        registry.put("my.service", grpcChaosProfile().withErrorProbability(0.5).withErrorStatusCode("UNAVAILABLE"));

        GrpcChaosProfile partial = grpcChaosProfile().withErrorStatusCode("INTERNAL");
        GrpcChaosProfile updated = registry.patch("my.service", partial);

        assertThat("errorProbability preserved from base", updated.getErrorProbability(), is(0.5));
        assertThat("errorStatusCode overwritten by patch", updated.getErrorStatusCode(), is("INTERNAL"));
    }

    @Test
    public void shouldPatchCreatesNewWhenMissing() {
        GrpcChaosRegistry registry = new GrpcChaosRegistry(() -> 0L);
        GrpcChaosProfile partial = grpcChaosProfile().withErrorProbability(0.7);
        GrpcChaosProfile result = registry.patch("new-service", partial);

        assertThat(result.getErrorProbability(), is(0.7));
        assertThat(registry.get("new-service"), is(notNullValue()));
    }

    @Test
    public void shouldIncrementMatchCount() {
        assertThat(GrpcChaosRegistry.getInstance().incrementMatchCount("my.service"), is(1));
        assertThat(GrpcChaosRegistry.getInstance().incrementMatchCount("my.service"), is(2));
        assertThat(GrpcChaosRegistry.getInstance().incrementMatchCount("other.service"), is(1));
    }

    @Test
    public void resetClearsMatchCounters() {
        GrpcChaosRegistry.getInstance().incrementMatchCount("my.service");
        GrpcChaosRegistry.getInstance().reset();
        assertThat(GrpcChaosRegistry.getInstance().incrementMatchCount("my.service"), is(1));
    }

    @Test
    public void activeCountByFaultTypeCountsEachFaultAndExcludesExpired() {
        FakeClock clock = new FakeClock();
        GrpcChaosRegistry registry = new GrpcChaosRegistry(clock::get);
        registry.put("ephemeral", grpcChaosProfile().withErrorProbability(1.0).withLatencyMs(200L), 5_000L);
        registry.put("persistent", grpcChaosProfile().withQuotaName("q").withQuotaLimit(10).withQuotaWindowMillis(60_000L));

        Map<String, Integer> counts = registry.activeCountByFaultType();
        assertThat("one injects error", counts.get("error"), is(1));
        assertThat("one injects latency", counts.get("latency"), is(1));
        assertThat("one injects quota", counts.get("quota"), is(1));
        assertThat("all fault types reported", counts.keySet(), is(new LinkedHashSet<>(GrpcChaosRegistry.FAULT_TYPES)));

        clock.advance(5_000L); // ephemeral expires
        Map<String, Integer> afterExpiry = registry.activeCountByFaultType();
        assertThat("expired entry not counted for error", afterExpiry.get("error"), is(0));
        assertThat("expired entry not counted for latency", afterExpiry.get("latency"), is(0));
        assertThat("persistent quota still counted", afterExpiry.get("quota"), is(1));
    }

    @Test
    public void defaultProfileFallbackWithExpiredServiceSpecific() {
        FakeClock clock = new FakeClock();
        GrpcChaosRegistry registry = new GrpcChaosRegistry(clock::get);
        GrpcChaosProfile defaultProfile = grpcChaosProfile().withErrorProbability(0.1);
        GrpcChaosProfile specific = grpcChaosProfile().withErrorProbability(0.9);
        registry.put("", defaultProfile);
        registry.put("my.service", specific, 5_000L);

        // service-specific takes precedence
        assertThat(registry.get("my.service"), is(specific));

        // after TTL expires, should fall back to default
        clock.advance(5_000L);
        assertThat(registry.get("my.service"), is(defaultProfile));
    }
}
