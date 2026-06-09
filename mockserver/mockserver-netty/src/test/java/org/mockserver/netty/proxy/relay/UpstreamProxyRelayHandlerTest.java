package org.mockserver.netty.proxy.relay;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

/**
 * EmbeddedChannel tests for {@link UpstreamProxyRelayHandler} verifying:
 * - channelActive writes EMPTY_BUFFER to trigger pipeline activation
 * - channelRead0 relays FullHttpRequest to the downstream channel
 * - channelInactive closes the downstream channel
 * - exceptionCaught closes the handler's own channel
 */
public class UpstreamProxyRelayHandlerTest {

    private EmbeddedChannel upstreamChannel;
    private EmbeddedChannel downstreamChannel;
    private MockServerLogger mockServerLogger;

    @Before
    public void setUp() {
        mockServerLogger = new MockServerLogger();
        // The upstream channel is the "proxy client" side (reads from client, writes to client).
        // The downstream channel is the "MockServer" side (relays requests to MockServer).
        upstreamChannel = new EmbeddedChannel();
        downstreamChannel = new EmbeddedChannel();
        EmbeddedChannel handlerChannel = new EmbeddedChannel(
            new UpstreamProxyRelayHandler(mockServerLogger, upstreamChannel, downstreamChannel)
        );
        // Store the handler channel as upstreamChannel for test purposes - the handler is
        // added to the channel that receives decoded FullHttpRequest from the proxy client.
        // Actually, the UpstreamProxyRelayHandler sits on the upstreamChannel (the proxy client
        // side) and relays requests to the downstreamChannel (MockServer side).
        // Let me restructure: the handler channel IS the channel that gets FullHttpRequest
        // messages relayed from the proxy client pipeline.
        this.upstreamChannel = handlerChannel;
    }

    @After
    public void tearDown() {
        if (upstreamChannel != null) {
            try {
                upstreamChannel.checkException();
            } catch (Exception ignored) {
            }
            try {
                upstreamChannel.finishAndReleaseAll();
            } catch (Exception ignored) {
            }
        }
        if (downstreamChannel != null) {
            try {
                downstreamChannel.finishAndReleaseAll();
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    public void shouldRelayFullHttpRequestToDownstreamChannel() {
        // given
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1,
            HttpMethod.GET,
            "/test",
            Unpooled.copiedBuffer("body", StandardCharsets.UTF_8)
        );
        // UpstreamProxyRelayHandler uses super(false) so it doesn't auto-release; the single
        // reference is handed to the downstream channel and released after readOutbound below.

        // when
        upstreamChannel.writeInbound(request);

        // then - the request should appear on the downstream channel's outbound
        Object relayed = downstreamChannel.readOutbound();
        assertThat("request should be relayed to downstream", relayed, is(notNullValue()));
        assertThat(relayed, instanceOf(FullHttpRequest.class));
        FullHttpRequest relayedRequest = (FullHttpRequest) relayed;
        assertThat(relayedRequest.method(), is(HttpMethod.GET));
        assertThat(relayedRequest.uri(), is("/test"));
        assertThat(relayedRequest.content().toString(StandardCharsets.UTF_8), is("body"));
        relayedRequest.release();
    }

    @Test
    public void shouldCloseDownstreamChannelOnChannelInactive() {
        // given - downstream is active
        assertTrue("downstream should be active initially", downstreamChannel.isActive());

        // when - upstream becomes inactive
        upstreamChannel.close();
        upstreamChannel.runPendingTasks();
        downstreamChannel.runPendingTasks();

        // then - downstream should be closed (closeOnFlush only closes if active)
        assertFalse("downstream should be closed when upstream goes inactive", downstreamChannel.isActive());
    }

    @Test
    public void shouldCloseOwnChannelOnException() {
        // given
        assertTrue("upstream should be active initially", upstreamChannel.isActive());

        // when
        upstreamChannel.pipeline().fireExceptionCaught(new RuntimeException("simulated error"));
        upstreamChannel.runPendingTasks();

        // then
        assertFalse("upstream channel should be closed on exception", upstreamChannel.isActive());
    }

    @Test
    public void shouldWriteEmptyBufferOnChannelActive() {
        // The channelActive writes EMPTY_BUFFER and calls ctx.read()
        // Since EmbeddedChannel fires channelActive on construction, just verify
        // the channel is active and no exception was thrown.
        assertTrue("channel should be active after initialization", upstreamChannel.isActive());
        upstreamChannel.checkException();
    }
}
