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
