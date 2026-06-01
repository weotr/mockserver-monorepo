package org.mockserver.mock.action.http;

import org.junit.After;
import org.junit.Test;
import org.mockserver.model.HttpChaosProfile;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.model.HttpChaosProfile.httpChaosProfile;

public class ServiceChaosRegistryTest {

    @After
    public void cleanup() {
        ServiceChaosRegistry.getInstance().reset();
    }

    @Test
    public void shouldRegisterAndResolveByHost() {
        HttpChaosProfile profile = httpChaosProfile().withErrorStatus(503).withErrorProbability(1.0);
        ServiceChaosRegistry.getInstance().put("upstream.svc", profile);
        assertThat(ServiceChaosRegistry.getInstance().get("upstream.svc"), is(profile));
    }

    @Test
    public void shouldMatchHostCaseInsensitivelyAndIgnorePort() {
        HttpChaosProfile profile = httpChaosProfile().withErrorStatus(503);
        ServiceChaosRegistry.getInstance().put("Upstream.SVC", profile);
        assertThat("lower-cased lookup", ServiceChaosRegistry.getInstance().get("upstream.svc"), is(profile));
        assertThat("lookup with port", ServiceChaosRegistry.getInstance().get("upstream.svc:8080"), is(profile));
    }

    @Test
    public void shouldRegisterWithPortAndResolveWithoutPort() {
        HttpChaosProfile profile = httpChaosProfile().withErrorStatus(503);
        ServiceChaosRegistry.getInstance().put("upstream.svc:8080", profile);
        assertThat(ServiceChaosRegistry.getInstance().get("upstream.svc"), is(profile));
    }

    @Test
    public void shouldHandleBracketedIpv6Host() {
        HttpChaosProfile profile = httpChaosProfile().withErrorStatus(503);
        ServiceChaosRegistry.getInstance().put("[::1]:8080", profile);
        assertThat("lookup without port", ServiceChaosRegistry.getInstance().get("[::1]"), is(profile));
        assertThat("lookup with a different port", ServiceChaosRegistry.getInstance().get("[::1]:9999"), is(profile));
    }

    @Test
    public void shouldReturnNullForUnknownHost() {
        assertThat(ServiceChaosRegistry.getInstance().get("nope"), is(nullValue()));
        assertThat(ServiceChaosRegistry.getInstance().get(null), is(nullValue()));
    }

    @Test
    public void shouldIgnoreNullProfileOrHost() {
        ServiceChaosRegistry.getInstance().put(null, httpChaosProfile());
        ServiceChaosRegistry.getInstance().put("h", null);
        assertThat(ServiceChaosRegistry.getInstance().get("h"), is(nullValue()));
    }

    @Test
    public void shouldRemoveHost() {
        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));
        ServiceChaosRegistry.getInstance().remove("UPSTREAM.svc:9999");
        assertThat(ServiceChaosRegistry.getInstance().get("upstream.svc"), is(nullValue()));
    }

    @Test
    public void shouldClearAllOnReset() {
        ServiceChaosRegistry.getInstance().put("a", httpChaosProfile());
        ServiceChaosRegistry.getInstance().put("b", httpChaosProfile());
        ServiceChaosRegistry.getInstance().reset();
        assertThat(ServiceChaosRegistry.getInstance().entries().isEmpty(), is(true));
    }

    // --- TTL / auto-revert tests (use a controllable clock via a local registry instance) ---

    /** A controllable clock so TTL expiry is deterministic without sleeping. */
    private static final class FakeClock {
        private final java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1_000L);

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
        ServiceChaosRegistry registry = new ServiceChaosRegistry(clock::get);
        HttpChaosProfile profile = httpChaosProfile().withErrorStatus(503);
        registry.put("upstream.svc", profile, 5_000L);

        assertThat("within ttl", registry.get("upstream.svc"), is(profile));
        clock.advance(4_999L);
        assertThat("just before expiry", registry.get("upstream.svc"), is(profile));
        clock.advance(1L);
        assertThat("at/after expiry auto-reverts", registry.get("upstream.svc"), is(nullValue()));
    }

    @Test
    public void shouldNotExpireWhenTtlIsZeroOrAbsent() {
        FakeClock clock = new FakeClock();
        ServiceChaosRegistry registry = new ServiceChaosRegistry(clock::get);
        registry.put("a", httpChaosProfile().withErrorStatus(503)); // no ttl
        registry.put("b", httpChaosProfile().withErrorStatus(503), 0L); // ttl 0 = no expiry
        clock.advance(1_000_000L);
        assertThat(registry.get("a"), is(notNullValue()));
        assertThat(registry.get("b"), is(notNullValue()));
    }

    @Test
    public void entriesExcludeExpired() {
        FakeClock clock = new FakeClock();
        ServiceChaosRegistry registry = new ServiceChaosRegistry(clock::get);
        registry.put("ephemeral", httpChaosProfile().withErrorStatus(503), 5_000L);
        registry.put("persistent", httpChaosProfile().withErrorStatus(500)); // no ttl

        assertThat(registry.entries().size(), is(2));

        clock.advance(5_000L); // ephemeral expires
        assertThat(registry.entries().keySet(), is(java.util.Collections.singleton("persistent")));
    }

    @Test
    public void ttlRemainingMillisReportsOnlyTtlBearingLiveEntries() {
        FakeClock clock = new FakeClock();
        ServiceChaosRegistry registry = new ServiceChaosRegistry(clock::get);
        registry.put("ephemeral", httpChaosProfile().withErrorStatus(503), 5_000L);
        registry.put("persistent", httpChaosProfile().withErrorStatus(500)); // no ttl -> not reported

        assertThat(registry.ttlRemainingMillis().keySet(), is(java.util.Collections.singleton("ephemeral")));
        assertThat(registry.ttlRemainingMillis().get("ephemeral"), is(5_000L));
        clock.advance(2_000L);
        assertThat(registry.ttlRemainingMillis().get("ephemeral"), is(3_000L));
        clock.advance(3_000L); // expired
        assertThat(registry.ttlRemainingMillis().isEmpty(), is(true));
    }

    @Test
    public void hugeTtlSaturatesInsteadOfOverflowing() {
        FakeClock clock = new FakeClock();
        ServiceChaosRegistry registry = new ServiceChaosRegistry(clock::get);
        HttpChaosProfile profile = httpChaosProfile().withErrorStatus(503);
        registry.put("upstream.svc", profile, Long.MAX_VALUE); // would overflow now+ttl
        clock.advance(1_000_000_000L);
        assertThat("a saturating expiry never silently flips to non-expiring-via-negative", registry.get("upstream.svc"), is(profile));
    }

    @Test
    public void activeCountByFaultTypeCountsEachFaultAndExcludesExpired() {
        FakeClock clock = new FakeClock();
        ServiceChaosRegistry registry = new ServiceChaosRegistry(clock::get);
        // ephemeral injects error + latency; persistent injects error + drop
        registry.put("ephemeral", httpChaosProfile().withErrorStatus(503)
            .withLatency(org.mockserver.model.Delay.milliseconds(200)), 5_000L);
        registry.put("persistent", httpChaosProfile().withErrorStatus(500).withDropConnectionProbability(0.5));

        java.util.Map<String, Integer> counts = registry.activeCountByFaultType();
        assertThat("both inject error", counts.get("error"), is(2));
        assertThat("one injects latency", counts.get("latency"), is(1));
        assertThat("one injects drop", counts.get("drop"), is(1));
        assertThat("none inject quota", counts.get("quota"), is(0));
        assertThat("all fault types reported", counts.keySet(), is(new java.util.LinkedHashSet<>(ServiceChaosRegistry.FAULT_TYPES)));

        clock.advance(5_000L); // ephemeral expires (still in the map until lazily evicted)
        java.util.Map<String, Integer> afterExpiry = registry.activeCountByFaultType();
        assertThat("expired entry not counted for error", afterExpiry.get("error"), is(1));
        assertThat("expired entry not counted for latency", afterExpiry.get("latency"), is(0));
        assertThat("persistent drop still counted", afterExpiry.get("drop"), is(1));
    }

    @Test
    public void activeCountByFaultTypeRequiresCompleteSlowAndQuotaConfig() {
        ServiceChaosRegistry registry = new ServiceChaosRegistry(() -> 0L);
        // incomplete (no-op) configs that cannot actually fire are not counted
        registry.put("a", httpChaosProfile().withSlowResponseChunkSize(8)); // no chunk delay
        registry.put("b", httpChaosProfile().withQuotaName("acct")); // no limit / window
        java.util.Map<String, Integer> partial = registry.activeCountByFaultType();
        assertThat("slow needs a chunk delay", partial.get("slow"), is(0));
        assertThat("quota needs a limit + window", partial.get("quota"), is(0));

        // complete configs are counted
        registry.put("c", httpChaosProfile().withSlowResponseChunkSize(8)
            .withSlowResponseChunkDelay(org.mockserver.model.Delay.milliseconds(50)));
        registry.put("d", httpChaosProfile().withQuotaName("acct").withQuotaLimit(100).withQuotaWindowMillis(60_000L));
        java.util.Map<String, Integer> complete = registry.activeCountByFaultType();
        assertThat("complete slow counted", complete.get("slow"), is(1));
        assertThat("complete quota counted", complete.get("quota"), is(1));
    }

    // --- PATCH / merge tests ---

    @Test
    public void patchPreservesGraphqlFieldsWhenPatchChangesUnrelatedField() {
        ServiceChaosRegistry registry = new ServiceChaosRegistry(() -> 0L);
        // base profile with all four graphql fields set
        HttpChaosProfile base = httpChaosProfile()
            .withGraphqlErrors(true)
            .withGraphqlErrorMessage("downstream failure")
            .withGraphqlErrorCode("INTERNAL_SERVER_ERROR")
            .withGraphqlNullifyData(false)
            .withErrorStatus(503);
        registry.put("gql.svc", base);

        // patch only changes errorStatus — graphql fields must survive
        HttpChaosProfile partial = httpChaosProfile().withErrorStatus(500);
        HttpChaosProfile merged = registry.patch("gql.svc", partial);

        assertThat("graphqlErrors survives unrelated patch", merged.getGraphqlErrors(), is(true));
        assertThat("graphqlErrorMessage survives", merged.getGraphqlErrorMessage(), is("downstream failure"));
        assertThat("graphqlErrorCode survives", merged.getGraphqlErrorCode(), is("INTERNAL_SERVER_ERROR"));
        assertThat("graphqlNullifyData survives", merged.getGraphqlNullifyData(), is(false));
        assertThat("errorStatus was updated by patch", merged.getErrorStatus(), is(500));
    }

    @Test
    public void patchCanSetAndOverrideGraphqlFields() {
        ServiceChaosRegistry registry = new ServiceChaosRegistry(() -> 0L);
        // base has graphqlErrors=true and a message
        registry.put("gql.svc", httpChaosProfile()
            .withGraphqlErrors(true)
            .withGraphqlErrorMessage("original message"));

        // patch overrides message and sets code
        HttpChaosProfile partial = httpChaosProfile()
            .withGraphqlErrorMessage("patched message")
            .withGraphqlErrorCode("BAD_USER_INPUT")
            .withGraphqlNullifyData(true);
        HttpChaosProfile merged = registry.patch("gql.svc", partial);

        assertThat("graphqlErrors not overridden (null in patch)", merged.getGraphqlErrors(), is(true));
        assertThat("graphqlErrorMessage overridden by patch", merged.getGraphqlErrorMessage(), is("patched message"));
        assertThat("graphqlErrorCode set by patch", merged.getGraphqlErrorCode(), is("BAD_USER_INPUT"));
        assertThat("graphqlNullifyData set by patch", merged.getGraphqlNullifyData(), is(true));
    }

    @Test
    public void activeCountByFaultTypeReportsGraphqlWhenEnabled() {
        ServiceChaosRegistry registry = new ServiceChaosRegistry(() -> 0L);
        registry.put("gql1.svc", httpChaosProfile().withGraphqlErrors(true));
        registry.put("gql2.svc", httpChaosProfile().withGraphqlErrors(true).withErrorStatus(503));
        registry.put("http.svc", httpChaosProfile().withErrorStatus(500)); // no graphql

        java.util.Map<String, Integer> counts = registry.activeCountByFaultType();
        assertThat("two hosts with graphqlErrors=true", counts.get("graphql"), is(2));
        assertThat("graphql is a recognised fault type", counts.containsKey("graphql"), is(true));
    }

    @Test
    public void activeCountByFaultTypeDoesNotCountGraphqlWhenFalseOrNull() {
        ServiceChaosRegistry registry = new ServiceChaosRegistry(() -> 0L);
        registry.put("a.svc", httpChaosProfile().withGraphqlErrors(false));
        registry.put("b.svc", httpChaosProfile()); // graphqlErrors is null

        java.util.Map<String, Integer> counts = registry.activeCountByFaultType();
        assertThat("graphqlErrors=false is not counted", counts.get("graphql"), is(0));
    }

    @Test
    public void getDoesNotEvictARefreshedEntry() {
        // a re-registration after expiry must survive the lazy-eviction of the old entry
        FakeClock clock = new FakeClock();
        ServiceChaosRegistry registry = new ServiceChaosRegistry(clock::get);
        registry.put("upstream.svc", httpChaosProfile().withErrorStatus(503), 5_000L);
        clock.advance(6_000L); // old entry now expired
        HttpChaosProfile fresh = httpChaosProfile().withErrorStatus(500);
        registry.put("upstream.svc", fresh); // re-register without ttl
        assertThat("the refreshed entry is not evicted by the stale-entry cleanup", registry.get("upstream.svc"), is(fresh));
    }
}
