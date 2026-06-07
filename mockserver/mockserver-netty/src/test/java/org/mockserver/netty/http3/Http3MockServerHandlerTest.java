package org.mockserver.netty.http3;

import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.incubator.codec.http3.DefaultHttp3DataFrame;
import io.netty.incubator.codec.http3.DefaultHttp3HeadersFrame;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.metrics.Metrics;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * Unit tests for {@link Http3MockServerHandler}, specifically testing ByteBuf
 * lifecycle management (leak prevention, no double-release).
 * <p>
 * These tests do NOT require the native QUIC transport -- they exercise the
 * handler's buffer management by directly invoking the protected methods
 * (accessible from the same package).
 */
public class Http3MockServerHandlerTest {

    private static final Configuration CONFIGURATION = configuration();
    private static final MockServerLogger LOGGER = new MockServerLogger(Http3MockServerHandlerTest.class);

    @Test
    public void shouldReleaseBodyAccumulatorOnHandlerRemoved() throws Exception {
        // given: a handler that has received headers and a data frame
        Metrics metrics = new Metrics(CONFIGURATION);
        Http3MockServerHandler handler = new Http3MockServerHandler(
            CONFIGURATION, LOGGER, mock(HttpState.class), mock(HttpActionHandler.class), metrics
        );

        ChannelHandlerContext ctx = mockChannelHandlerContext();

        DefaultHttp3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().method("GET");
        headersFrame.headers().path("/test");
        headersFrame.headers().scheme("https");
        headersFrame.headers().authority("localhost:8443");

        // invoke protected channelRead to initialise the bodyAccumulator
        handler.channelRead(ctx, headersFrame);

        DefaultHttp3DataFrame dataFrame = new DefaultHttp3DataFrame(
            Unpooled.wrappedBuffer("test-body".getBytes(StandardCharsets.UTF_8))
        );
        handler.channelRead(ctx, dataFrame);

        // capture the bodyAccumulator via reflection to verify its refCnt
        java.lang.reflect.Field accField = Http3MockServerHandler.class.getDeclaredField("bodyAccumulator");
        accField.setAccessible(true);
        CompositeByteBuf accumulator = (CompositeByteBuf) accField.get(handler);
        assertThat("bodyAccumulator should be allocated", accumulator.refCnt(), is(1));

        // when: handlerRemoved fires (simulating abrupt disconnect before channelInputClosed)
        handler.handlerRemoved(ctx);

        // then: the body accumulator should be released
        assertThat("bodyAccumulator should be released after handlerRemoved", accumulator.refCnt(), is(0));

        // and: the field should be nulled out (verify via reflection)
        assertThat("bodyAccumulator field should be null after release", accField.get(handler) == null, is(true));
    }

    @Test
    public void shouldNotDoubleReleaseWhenChannelInputClosedThenHandlerRemoved() throws Exception {
        // given: a handler that processes a complete request via channelInputClosed
        Metrics metrics = new Metrics(CONFIGURATION);
        HttpState httpState = mock(HttpState.class);
        when(httpState.handle(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean()))
            .thenReturn(true); // simulate control-plane handling to avoid needing httpActionHandler

        Http3MockServerHandler handler = new Http3MockServerHandler(
            CONFIGURATION, LOGGER, httpState, mock(HttpActionHandler.class), metrics
        );

        ChannelHandlerContext ctx = mockChannelHandlerContext();

        DefaultHttp3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().method("GET");
        headersFrame.headers().path("/test");
        headersFrame.headers().scheme("https");

        handler.channelRead(ctx, headersFrame);

        java.lang.reflect.Field accField = Http3MockServerHandler.class.getDeclaredField("bodyAccumulator");
        accField.setAccessible(true);
        CompositeByteBuf accumulator = (CompositeByteBuf) accField.get(handler);

        // when: channelInputClosed releases the accumulator
        handler.channelInputClosed(ctx);
        assertThat("bodyAccumulator should be released after channelInputClosed", accumulator.refCnt(), is(0));

        // then: handlerRemoved should not throw (no double-release because field was nulled)
        handler.handlerRemoved(ctx); // should be a no-op since bodyAccumulator is already null
    }

    @Test
    public void shouldReleaseBodyAccumulatorWhenNoHeadersReceivedAndChannelInputClosed() throws Exception {
        // given: a handler that receives no headers but has channelInputClosed called
        // (edge case: stream closes immediately)
        Metrics metrics = new Metrics(CONFIGURATION);
        Http3MockServerHandler handler = new Http3MockServerHandler(
            CONFIGURATION, LOGGER, mock(HttpState.class), mock(HttpActionHandler.class), metrics
        );

        ChannelHandlerContext ctx = mockChannelHandlerContext();

        // when: channelInputClosed fires without prior headers
        handler.channelInputClosed(ctx);

        // then: no exception (bodyAccumulator was never allocated, early-return path handles it)
        handler.handlerRemoved(ctx); // also a no-op
    }

    @Test
    public void shouldReleaseBodyAccumulatorOnEarlyReturnWhenParsedHeadersNull() throws Exception {
        // given: a handler where channelRead(HeadersFrame) was called but parseHeaders returned
        // a valid ParsedHeaders -- this test ensures the finally block runs on the channelInputClosed
        // early-return path when parsedHeaders IS null (only bodyAccumulator was allocated)

        // Simulate the scenario where headers frame was received (so bodyAccumulator is allocated)
        // but parsedHeaders is forcibly set to null (simulating a bizarre edge case)
        Metrics metrics = new Metrics(CONFIGURATION);
        Http3MockServerHandler handler = new Http3MockServerHandler(
            CONFIGURATION, LOGGER, mock(HttpState.class), mock(HttpActionHandler.class), metrics
        );

        ChannelHandlerContext ctx = mockChannelHandlerContext();

        DefaultHttp3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().method("GET");
        headersFrame.headers().path("/test");
        handler.channelRead(ctx, headersFrame);

        // Force parsedHeaders to null via reflection to test the early-return + finally release
        java.lang.reflect.Field parsedField = Http3MockServerHandler.class.getDeclaredField("parsedHeaders");
        parsedField.setAccessible(true);
        parsedField.set(handler, null);

        java.lang.reflect.Field accField = Http3MockServerHandler.class.getDeclaredField("bodyAccumulator");
        accField.setAccessible(true);
        CompositeByteBuf accumulator = (CompositeByteBuf) accField.get(handler);
        assertThat("bodyAccumulator should be allocated", accumulator.refCnt(), is(1));

        // when: channelInputClosed fires with null parsedHeaders (early-return path)
        handler.channelInputClosed(ctx);

        // then: the body accumulator should still be released by the finally block
        assertThat("bodyAccumulator should be released on early-return path", accumulator.refCnt(), is(0));
    }

    private ChannelHandlerContext mockChannelHandlerContext() {
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        when(ctx.alloc()).thenReturn(ByteBufAllocator.DEFAULT);
        return ctx;
    }
}
