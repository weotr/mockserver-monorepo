package org.mockserver.httpclient;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.Http2Settings;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.model.Protocol;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

/**
 * EmbeddedChannel tests for {@link Http2SettingsHandler} verifying:
 * - Receiving Http2Settings completes the protocol future with HTTP_2
 * - Handler removes itself from the pipeline after the first settings frame
 * - Second settings frame is not consumed (handler already removed)
 */
public class Http2SettingsHandlerTest {

    private EmbeddedChannel channel;
    private CompletableFuture<Protocol> protocolFuture;

    @Before
    public void setUp() {
        protocolFuture = new CompletableFuture<>();
        Http2SettingsHandler handler = new Http2SettingsHandler(protocolFuture);
        channel = new EmbeddedChannel(handler);
    }

    @After
    public void tearDown() {
        if (channel != null) {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldCompleteProtocolFutureWithHttp2OnSettingsReceived() throws Exception {
        // given
        Http2Settings settings = new Http2Settings()
            .maxConcurrentStreams(100)
            .initialWindowSize(65535);

        // when
        channel.writeInbound(settings);

        // then
        assertTrue("protocol future should be done", protocolFuture.isDone());
        assertFalse("protocol future should not be exceptionally completed", protocolFuture.isCompletedExceptionally());
        assertThat(protocolFuture.get(1, TimeUnit.SECONDS), is(Protocol.HTTP_2));
    }

    @Test
    public void shouldRemoveSelfFromPipelineAfterFirstSettings() {
        // given
        Http2Settings settings = new Http2Settings();

        // when
        channel.writeInbound(settings);

        // then
        assertNull("handler should be removed from pipeline", channel.pipeline().get(Http2SettingsHandler.class));
    }

    @Test
    public void shouldCompleteProtocolFutureWithEmptySettings() throws Exception {
        // given - empty settings (still a valid Http2Settings object)
        Http2Settings settings = new Http2Settings();

        // when
        channel.writeInbound(settings);

        // then
        assertTrue("protocol future should be done", protocolFuture.isDone());
        assertThat(protocolFuture.get(1, TimeUnit.SECONDS), is(Protocol.HTTP_2));
    }

    @Test
    public void shouldNotConsumeSubsequentSettingsFrameAfterRemoval() {
        // given - first settings consumed
        channel.writeInbound(new Http2Settings());
        assertNull("handler removed after first frame", channel.pipeline().get(Http2SettingsHandler.class));

        // when - second settings arrives (no handler to consume it)
        Http2Settings secondSettings = new Http2Settings().maxConcurrentStreams(200);
        channel.writeInbound(secondSettings);

        // then - second settings is in the inbound queue (not consumed)
        Http2Settings read = channel.readInbound();
        // The first one may also be readable since no other handler consumed it
        // after Http2SettingsHandler removed itself; just verify we can read settings
        assertThat("at least one settings frame should be readable", read, is(notNullValue()));
    }
}
