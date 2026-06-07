package org.mockserver.httpclient;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.DefaultHttp2GoAwayFrame;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static org.mockserver.httpclient.TimeToFirstByteHandler.FIRST_BYTE_MILLIS;

/**
 * EmbeddedChannel tests for {@link TimeToFirstByteHandler} verifying:
 * - TTFB is stamped on the first HttpObject received
 * - Handler removes itself after the first HttpObject
 * - Non-HttpObject messages do not trigger TTFB stamping
 * - Messages are forwarded downstream regardless
 */
public class TimeToFirstByteHandlerTest {

    private EmbeddedChannel channel;
    private AtomicLong firstByteMillis;
    private TimeToFirstByteHandler handler;

    @Before
    public void setUp() {
        firstByteMillis = new AtomicLong(0);
        handler = new TimeToFirstByteHandler();
        channel = new EmbeddedChannel(handler);
        channel.attr(FIRST_BYTE_MILLIS).set(firstByteMillis);
    }

    @After
    public void tearDown() {
        if (channel != null) {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldStampFirstByteMillisOnHttpResponse() {
        // given
        long before = System.currentTimeMillis();
        DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);

        // when
        channel.writeInbound(response);

        // then
        long after = System.currentTimeMillis();
        long stamped = firstByteMillis.get();
        assertThat("TTFB should be stamped", stamped, greaterThan(0L));
        assertThat("TTFB should be >= before time", stamped, greaterThanOrEqualTo(before));
        assertThat("TTFB should be <= after time", stamped, lessThanOrEqualTo(after));
    }

    @Test
    public void shouldRemoveSelfAfterFirstHttpObject() {
        // given
        DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);

        // when
        channel.writeInbound(response);

        // then
        assertNull("handler should be removed from pipeline", channel.pipeline().get(TimeToFirstByteHandler.class));
    }

    @Test
    public void shouldForwardHttpObjectDownstream() {
        // given
        DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);

        // when
        channel.writeInbound(response);

        // then
        DefaultHttpResponse read = channel.readInbound();
        assertThat("response should be forwarded", read, is(notNullValue()));
        assertThat(read.status(), is(HttpResponseStatus.OK));
    }

    @Test
    public void shouldNotStampOnNonHttpObject() {
        // given - a non-HttpObject message (raw ByteBuf)
        io.netty.buffer.ByteBuf rawMsg = Unpooled.copiedBuffer("not http", java.nio.charset.StandardCharsets.UTF_8);

        // when
        channel.writeInbound(rawMsg);

        // then - TTFB not stamped
        assertThat("TTFB should remain 0 for non-HTTP message", firstByteMillis.get(), is(0L));
        // handler should still be in pipeline
        assertNotNull("handler should NOT be removed for non-HTTP message", channel.pipeline().get(TimeToFirstByteHandler.class));

        // message should still be forwarded
        io.netty.buffer.ByteBuf read = channel.readInbound();
        assertThat(read, is(notNullValue()));
        read.release();
    }

    @Test
    public void shouldNotOverwriteFirstByteOnSubsequentReads() {
        // given - simulate first TTFB already set
        firstByteMillis.set(12345L);
        DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);

        // when
        channel.writeInbound(response);

        // then - compareAndSet(0, ...) should not overwrite
        assertThat("TTFB should not be overwritten", firstByteMillis.get(), is(12345L));
    }

    @Test
    public void shouldHandleMissingFirstByteMillisAttribute() {
        // given - channel without the FIRST_BYTE_MILLIS attribute set
        EmbeddedChannel bareChannel = new EmbeddedChannel(new TimeToFirstByteHandler());
        // do not set the attribute
        DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);

        // when - should not throw
        bareChannel.writeInbound(response);

        // then - message still forwarded
        DefaultHttpResponse read = bareChannel.readInbound();
        assertThat(read, is(notNullValue()));
        bareChannel.finishAndReleaseAll();
    }
}
