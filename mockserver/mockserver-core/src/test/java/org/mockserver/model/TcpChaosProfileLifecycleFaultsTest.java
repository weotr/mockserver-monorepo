package org.mockserver.model;

import org.junit.Test;
import org.mockserver.serialization.model.TcpChaosProfileDTO;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.TcpChaosProfile.tcpChaosProfile;

/**
 * Covers the connection-lifecycle response-path fault fields added to {@link TcpChaosProfile}:
 * mid-response RST, host-scoped slow close, and HTTP/2 GOAWAY. Asserts they participate in
 * {@code equals}/{@code hashCode}/{@code hasAnyFault} and round-trip cleanly through
 * {@link TcpChaosProfileDTO} (the Jackson DTO).
 *
 * <p>Pure-POJO — no global state — so it runs in the default parallel Surefire phase.
 */
public class TcpChaosProfileLifecycleFaultsTest {

    private TcpChaosProfile fullyPopulatedLifecycleProfile() {
        return tcpChaosProfile()
            .withResetMidResponse(true)
            .withResetAfterResponseChunks(3)
            .withSlowCloseDelay(Delay.milliseconds(250))
            .withHttp2GoAway(true)
            .withHttp2GoAwayErrorCode(7L)
            .withHttp2GoAwayLastStreamId(11L);
    }

    @Test
    public void shouldRoundTripLifecycleFaultsThroughDto() {
        // given
        TcpChaosProfile original = fullyPopulatedLifecycleProfile()
            .withLatencyMs(100L)
            .withDown(false)
            .withBandwidthBytesPerSec(1024L)
            .withSlicerChunkSize(8)
            .withLimitDataBytes(4096L);

        // when
        TcpChaosProfile rebuilt = new TcpChaosProfileDTO(original).buildObject();

        // then - the rebuilt object is equal to the original (every field survives the round-trip)
        assertThat(rebuilt, is(original));
        assertThat(rebuilt.getResetMidResponse(), is(true));
        assertThat(rebuilt.getResetAfterResponseChunks(), is(3));
        assertThat(rebuilt.getSlowCloseDelay(), is(Delay.milliseconds(250)));
        assertThat(rebuilt.getHttp2GoAway(), is(true));
        assertThat(rebuilt.getHttp2GoAwayErrorCode(), is(7L));
        assertThat(rebuilt.getHttp2GoAwayLastStreamId(), is(11L));
    }

    @Test
    public void shouldRoundTripEmptyProfileThroughDto() {
        // given an empty profile (every field null)
        TcpChaosProfile empty = tcpChaosProfile();

        // when
        TcpChaosProfile rebuilt = new TcpChaosProfileDTO(empty).buildObject();

        // then
        assertThat(rebuilt, is(empty));
        assertThat(rebuilt.getSlowCloseDelay(), is(nullValue()));
        assertThat(rebuilt.getHttp2GoAway(), is(nullValue()));
        assertThat(rebuilt.hasAnyFault(), is(false));
    }

    @Test
    public void shouldIncludeLifecycleFaultsInEqualsAndHashCode() {
        TcpChaosProfile a = fullyPopulatedLifecycleProfile();
        TcpChaosProfile b = fullyPopulatedLifecycleProfile();
        assertThat(a, is(b));
        assertThat(a.hashCode(), is(b.hashCode()));

        // a single lifecycle field differing breaks equality
        assertThat(fullyPopulatedLifecycleProfile().withResetMidResponse(false), is(not(b)));
        assertThat(fullyPopulatedLifecycleProfile().withSlowCloseDelay(Delay.milliseconds(999)), is(not(b)));
        assertThat(fullyPopulatedLifecycleProfile().withHttp2GoAway(false), is(not(b)));
        assertThat(fullyPopulatedLifecycleProfile().withHttp2GoAwayErrorCode(0L), is(not(b)));
        assertThat(fullyPopulatedLifecycleProfile().withHttp2GoAwayLastStreamId(0L), is(not(b)));
        assertThat(fullyPopulatedLifecycleProfile().withResetAfterResponseChunks(0), is(not(b)));
    }

    @Test
    public void hasAnyFaultShouldDetectEachLifecycleFaultIndependently() {
        assertThat(tcpChaosProfile().withResetMidResponse(true).hasAnyFault(), is(true));
        assertThat(tcpChaosProfile().withSlowCloseDelay(Delay.milliseconds(1)).hasAnyFault(), is(true));
        assertThat(tcpChaosProfile().withHttp2GoAway(true).hasAnyFault(), is(true));

        // resetAfterResponseChunks / goaway metadata alone (without the boolean trigger) are NOT a fault
        assertThat(tcpChaosProfile().withResetAfterResponseChunks(5).hasAnyFault(), is(false));
        assertThat(tcpChaosProfile().withHttp2GoAwayErrorCode(7L).hasAnyFault(), is(false));
        assertThat(tcpChaosProfile().withHttp2GoAwayLastStreamId(11L).hasAnyFault(), is(false));

        // explicit false does not count
        assertThat(tcpChaosProfile().withResetMidResponse(false).hasAnyFault(), is(false));
        assertThat(tcpChaosProfile().withHttp2GoAway(false).hasAnyFault(), is(false));
    }

    @Test
    public void shouldRejectNegativeGoAwayValues() {
        try {
            tcpChaosProfile().withHttp2GoAwayErrorCode(-1L);
            org.junit.Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage(), containsString("http2GoAwayErrorCode"));
        }
        try {
            tcpChaosProfile().withResetAfterResponseChunks(-1);
            org.junit.Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage(), containsString("resetAfterResponseChunks"));
        }
    }
}
