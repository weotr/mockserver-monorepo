package org.mockserver.mock.action.http;

import org.junit.After;
import org.junit.Test;
import org.mockserver.model.TcpChaosProfile;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.model.TcpChaosProfile.tcpChaosProfile;

public class TcpChaosRegistryTest {

    @After
    public void cleanup() {
        TcpChaosRegistry.getInstance().reset();
    }

    @Test
    public void shouldRegisterAndResolveByHost() {
        TcpChaosProfile profile = tcpChaosProfile().withLatencyMs(500L);
        TcpChaosRegistry.getInstance().put("upstream.svc", profile);
        assertThat(TcpChaosRegistry.getInstance().get("upstream.svc"), is(profile));
    }

    @Test
    public void shouldMatchHostCaseInsensitivelyAndIgnorePort() {
        TcpChaosProfile profile = tcpChaosProfile().withDown(true);
        TcpChaosRegistry.getInstance().put("Upstream.SVC", profile);
        assertThat("lower-cased lookup", TcpChaosRegistry.getInstance().get("upstream.svc"), is(profile));
        assertThat("lookup with port", TcpChaosRegistry.getInstance().get("upstream.svc:8080"), is(profile));
    }

    @Test
    public void shouldRegisterWithPortAndResolveWithoutPort() {
        TcpChaosProfile profile = tcpChaosProfile().withResetPeer(true);
        TcpChaosRegistry.getInstance().put("upstream.svc:8080", profile);
        assertThat(TcpChaosRegistry.getInstance().get("upstream.svc"), is(profile));
    }

    @Test
    public void shouldHandleBracketedIpv6Host() {
        TcpChaosProfile profile = tcpChaosProfile().withLatencyMs(100L);
        TcpChaosRegistry.getInstance().put("[::1]:8080", profile);
        assertThat("lookup without port", TcpChaosRegistry.getInstance().get("[::1]"), is(profile));
        assertThat("lookup with a different port", TcpChaosRegistry.getInstance().get("[::1]:9999"), is(profile));
    }

    @Test
    public void shouldReturnNullForUnknownHost() {
        assertThat(TcpChaosRegistry.getInstance().get("nope"), is(nullValue()));
        assertThat(TcpChaosRegistry.getInstance().get(null), is(nullValue()));
    }

    @Test
    public void shouldIgnoreNullProfileOrHost() {
        TcpChaosRegistry.getInstance().put(null, tcpChaosProfile());
        TcpChaosRegistry.getInstance().put("h", null);
        assertThat(TcpChaosRegistry.getInstance().get("h"), is(nullValue()));
    }

    @Test
    public void shouldRemoveHost() {
        TcpChaosRegistry.getInstance().put("upstream.svc", tcpChaosProfile().withDown(true));
        TcpChaosRegistry.getInstance().remove("UPSTREAM.svc:9999");
        assertThat(TcpChaosRegistry.getInstance().get("upstream.svc"), is(nullValue()));
    }

    @Test
    public void shouldClearAllOnReset() {
        TcpChaosRegistry.getInstance().put("a", tcpChaosProfile().withDown(true));
        TcpChaosRegistry.getInstance().put("b", tcpChaosProfile().withResetPeer(true));
        TcpChaosRegistry.getInstance().reset();
        assertThat(TcpChaosRegistry.getInstance().entries().isEmpty(), is(true));
    }

    @Test
    public void shouldReturnActiveCount() {
        assertThat(TcpChaosRegistry.getInstance().activeCount(), is(0));
        TcpChaosRegistry.getInstance().put("a", tcpChaosProfile().withDown(true));
        TcpChaosRegistry.getInstance().put("b", tcpChaosProfile().withResetPeer(true));
        assertThat(TcpChaosRegistry.getInstance().activeCount(), is(2));
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
        TcpChaosRegistry registry = new TcpChaosRegistry(clock::get);
        TcpChaosProfile profile = tcpChaosProfile().withLatencyMs(500L);
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
        TcpChaosRegistry registry = new TcpChaosRegistry(clock::get);
        registry.put("a", tcpChaosProfile().withDown(true)); // no ttl
        registry.put("b", tcpChaosProfile().withDown(true), 0L); // ttl 0 = no expiry
        clock.advance(1_000_000L);
        assertThat(registry.get("a"), is(notNullValue()));
        assertThat(registry.get("b"), is(notNullValue()));
    }

    @Test
    public void entriesExcludeExpired() {
        FakeClock clock = new FakeClock();
        TcpChaosRegistry registry = new TcpChaosRegistry(clock::get);
        registry.put("ephemeral", tcpChaosProfile().withLatencyMs(200L), 5_000L);
        registry.put("persistent", tcpChaosProfile().withDown(true)); // no ttl

        assertThat(registry.entries().size(), is(2));

        clock.advance(5_000L); // ephemeral expires
        assertThat(registry.entries().keySet(), is(Collections.singleton("persistent")));
    }

    @Test
    public void ttlRemainingMillisReportsOnlyTtlBearingLiveEntries() {
        FakeClock clock = new FakeClock();
        TcpChaosRegistry registry = new TcpChaosRegistry(clock::get);
        registry.put("ephemeral", tcpChaosProfile().withLatencyMs(100L), 5_000L);
        registry.put("persistent", tcpChaosProfile().withDown(true)); // no ttl -> not reported

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
        TcpChaosRegistry registry = new TcpChaosRegistry(clock::get);
        TcpChaosProfile profile = tcpChaosProfile().withDown(true);
        registry.put("upstream.svc", profile, Long.MAX_VALUE);
        clock.advance(1_000_000_000L);
        assertThat("a saturating expiry never silently flips to non-expiring-via-negative", registry.get("upstream.svc"), is(profile));
    }

    @Test
    public void activeCountByFaultTypeCountsEachFaultAndExcludesExpired() {
        FakeClock clock = new FakeClock();
        TcpChaosRegistry registry = new TcpChaosRegistry(clock::get);
        registry.put("ephemeral", tcpChaosProfile().withLatencyMs(200L).withDown(true), 5_000L);
        registry.put("persistent", tcpChaosProfile().withResetPeer(true).withSlicerChunkSize(64));

        Map<String, Integer> counts = registry.activeCountByFaultType();
        assertThat("one injects latency", counts.get("latency"), is(1));
        assertThat("one injects down", counts.get("down"), is(1));
        assertThat("one injects reset_peer", counts.get("reset_peer"), is(1));
        assertThat("one injects slicer", counts.get("slicer"), is(1));
        assertThat("none inject bandwidth", counts.get("bandwidth"), is(0));
        assertThat("all fault types reported", counts.keySet(), is(new LinkedHashSet<>(TcpChaosRegistry.FAULT_TYPES)));

        clock.advance(5_000L); // ephemeral expires
        Map<String, Integer> afterExpiry = registry.activeCountByFaultType();
        assertThat("expired entry not counted for latency", afterExpiry.get("latency"), is(0));
        assertThat("expired entry not counted for down", afterExpiry.get("down"), is(0));
        assertThat("persistent reset_peer still counted", afterExpiry.get("reset_peer"), is(1));
    }

    @Test
    public void getDoesNotEvictARefreshedEntry() {
        FakeClock clock = new FakeClock();
        TcpChaosRegistry registry = new TcpChaosRegistry(clock::get);
        registry.put("upstream.svc", tcpChaosProfile().withDown(true), 5_000L);
        clock.advance(6_000L); // old entry now expired
        TcpChaosProfile fresh = tcpChaosProfile().withResetPeer(true);
        registry.put("upstream.svc", fresh); // re-register without ttl
        assertThat("the refreshed entry is not evicted by the stale-entry cleanup", registry.get("upstream.svc"), is(fresh));
    }

    @Test
    public void shouldPatchExistingProfile() {
        FakeClock clock = new FakeClock();
        TcpChaosRegistry registry = new TcpChaosRegistry(clock::get);
        registry.put("upstream.svc", tcpChaosProfile().withLatencyMs(100L).withDown(false));

        TcpChaosProfile partial = tcpChaosProfile().withDown(true);
        TcpChaosProfile updated = registry.patch("upstream.svc", partial);

        assertThat("latency preserved from base", updated.getLatencyMs(), is(100L));
        assertThat("down overwritten by patch", updated.getDown(), is(true));
    }

    @Test
    public void shouldPatchCreatesNewWhenMissing() {
        TcpChaosRegistry registry = new TcpChaosRegistry(() -> 0L);
        TcpChaosProfile partial = tcpChaosProfile().withResetPeer(true);
        TcpChaosProfile result = registry.patch("new-host", partial);

        assertThat(result.getResetPeer(), is(true));
        assertThat(registry.get("new-host"), is(notNullValue()));
    }

    @Test
    public void hasAnyFaultReturnsFalseForEmptyProfile() {
        TcpChaosProfile profile = tcpChaosProfile();
        assertThat(profile.hasAnyFault(), is(false));
    }

    @Test
    public void hasAnyFaultReturnsTrueForEachFault() {
        assertThat(tcpChaosProfile().withLatencyMs(100L).hasAnyFault(), is(true));
        assertThat(tcpChaosProfile().withDown(true).hasAnyFault(), is(true));
        assertThat(tcpChaosProfile().withBandwidthBytesPerSec(1024L).hasAnyFault(), is(true));
        assertThat(tcpChaosProfile().withSlowClose(true).hasAnyFault(), is(true));
        assertThat(tcpChaosProfile().withTimeout(true).hasAnyFault(), is(true));
        assertThat(tcpChaosProfile().withResetPeer(true).hasAnyFault(), is(true));
        assertThat(tcpChaosProfile().withSlicerChunkSize(64).hasAnyFault(), is(true));
        assertThat(tcpChaosProfile().withLimitDataBytes(1024L).hasAnyFault(), is(true));
    }

    @Test
    public void profileValidationRejectsBadValues() {
        try {
            tcpChaosProfile().withBandwidthBytesPerSec(0L);
            assertThat("should have thrown", false, is(true));
        } catch (IllegalArgumentException e) {
            // expected
        }
        try {
            tcpChaosProfile().withSlicerChunkSize(0);
            assertThat("should have thrown", false, is(true));
        } catch (IllegalArgumentException e) {
            // expected
        }
        try {
            tcpChaosProfile().withLimitDataBytes(0L);
            assertThat("should have thrown", false, is(true));
        } catch (IllegalArgumentException e) {
            // expected
        }
    }
}
