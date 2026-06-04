package org.mockserver.netty.http3;

import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.util.Attribute;
import io.netty.util.DefaultAttributeMap;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.metrics.Metrics;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.telemetry.TraceContextAttributes;
import org.mockserver.telemetry.W3CTraceContext;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * Unit tests for W3C trace-context extraction and propagation over HTTP/3.
 * <p>
 * These tests verify that {@link Http3MockServerHandler} parses traceparent /
 * tracestate headers from inbound requests (or generates a context when
 * {@code otelGenerateTraceId} is enabled) and stores the result on the channel
 * attribute -- identical behaviour to the TCP path's {@code TraceContextHandler}.
 * <p>
 * No native QUIC transport is needed -- the handler's protected methods are
 * invoked directly with a mocked {@link ChannelHandlerContext}.
 */
public class Http3TraceContextTest {

    private static final String VALID_TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    private static final String VALID_TRACESTATE = "rojo=00f067aa0ba902b7";
    private static final MockServerLogger LOGGER = new MockServerLogger(Http3TraceContextTest.class);

    @Test
    public void shouldExtractTraceContextFromInboundRequest() throws Exception {
        // given
        Configuration config = configuration();
        ChannelHandlerContext ctx = mockCtxWithAttributeMap();
        Http3MockServerHandler handler = createHandler(config, ctx);

        // when -- send request with traceparent header
        DefaultHttp3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().method("GET");
        headersFrame.headers().path("/test");
        headersFrame.headers().scheme("https");
        headersFrame.headers().add("traceparent", VALID_TRACEPARENT);
        handler.channelRead(ctx, headersFrame);

        // trigger channelInputClosed to process the request
        HttpState httpState = getMockedHttpState(handler);
        when(httpState.handle(any(), any(), anyBoolean())).thenReturn(true);
        handler.channelInputClosed(ctx);

        // then -- TRACE_CONTEXT channel attribute should be set
        W3CTraceContext traceCtx = ctx.channel().attr(TraceContextAttributes.TRACE_CONTEXT).get();
        assertThat("trace context should be set", traceCtx, is(notNullValue()));
        assertThat("traceId should match", traceCtx.getTraceId(), is("4bf92f3577b34da6a3ce929d0e0e4736"));
        assertThat("parentId should match", traceCtx.getParentId(), is("00f067aa0ba902b7"));
        assertThat("flags should match", traceCtx.getFlags(), is("01"));
    }

    @Test
    public void shouldExtractTraceContextWithTracestate() throws Exception {
        // given
        Configuration config = configuration();
        ChannelHandlerContext ctx = mockCtxWithAttributeMap();
        Http3MockServerHandler handler = createHandler(config, ctx);

        // when -- send request with traceparent AND tracestate
        DefaultHttp3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().method("GET");
        headersFrame.headers().path("/test");
        headersFrame.headers().scheme("https");
        headersFrame.headers().add("traceparent", VALID_TRACEPARENT);
        headersFrame.headers().add("tracestate", VALID_TRACESTATE);
        handler.channelRead(ctx, headersFrame);

        HttpState httpState = getMockedHttpState(handler);
        when(httpState.handle(any(), any(), anyBoolean())).thenReturn(true);
        handler.channelInputClosed(ctx);

        // then
        W3CTraceContext traceCtx = ctx.channel().attr(TraceContextAttributes.TRACE_CONTEXT).get();
        assertThat("trace context should be set", traceCtx, is(notNullValue()));
        assertThat("tracestate should match", traceCtx.getTraceState(), is(VALID_TRACESTATE));
    }

    @Test
    public void shouldNotSetTraceContextWhenNoTraceparentHeader() throws Exception {
        // given -- default config (no otelGenerateTraceId)
        Configuration config = configuration();
        ChannelHandlerContext ctx = mockCtxWithAttributeMap();
        Http3MockServerHandler handler = createHandler(config, ctx);

        // when -- send request WITHOUT traceparent
        DefaultHttp3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().method("GET");
        headersFrame.headers().path("/test");
        headersFrame.headers().scheme("https");
        handler.channelRead(ctx, headersFrame);

        HttpState httpState = getMockedHttpState(handler);
        when(httpState.handle(any(), any(), anyBoolean())).thenReturn(true);
        handler.channelInputClosed(ctx);

        // then -- no trace context set
        W3CTraceContext traceCtx = ctx.channel().attr(TraceContextAttributes.TRACE_CONTEXT).get();
        assertThat("trace context should not be set", traceCtx, is(nullValue()));
    }

    @Test
    public void shouldNotSetTraceContextForInvalidTraceparent() throws Exception {
        // given
        Configuration config = configuration();
        ChannelHandlerContext ctx = mockCtxWithAttributeMap();
        Http3MockServerHandler handler = createHandler(config, ctx);

        // when -- send request with INVALID traceparent (traceId too short)
        DefaultHttp3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().method("GET");
        headersFrame.headers().path("/test");
        headersFrame.headers().scheme("https");
        headersFrame.headers().add("traceparent", "00-short-00f067aa0ba902b7-01");
        handler.channelRead(ctx, headersFrame);

        HttpState httpState = getMockedHttpState(handler);
        when(httpState.handle(any(), any(), anyBoolean())).thenReturn(true);
        handler.channelInputClosed(ctx);

        // then -- no trace context set (invalid traceparent)
        W3CTraceContext traceCtx = ctx.channel().attr(TraceContextAttributes.TRACE_CONTEXT).get();
        assertThat("trace context should not be set for invalid traceparent", traceCtx, is(nullValue()));
    }

    @Test
    public void shouldGenerateTraceContextWhenOtelGenerateTraceIdEnabled() throws Exception {
        // given -- otelGenerateTraceId enabled
        Configuration config = configuration().otelGenerateTraceId(true);
        ChannelHandlerContext ctx = mockCtxWithAttributeMap();
        Http3MockServerHandler handler = createHandler(config, ctx);

        // when -- send request WITHOUT traceparent
        DefaultHttp3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().method("GET");
        headersFrame.headers().path("/test");
        headersFrame.headers().scheme("https");
        handler.channelRead(ctx, headersFrame);

        HttpState httpState = getMockedHttpState(handler);
        when(httpState.handle(any(), any(), anyBoolean())).thenReturn(true);
        handler.channelInputClosed(ctx);

        // then -- a trace context is generated
        W3CTraceContext traceCtx = ctx.channel().attr(TraceContextAttributes.TRACE_CONTEXT).get();
        assertThat("trace context should be generated", traceCtx, is(notNullValue()));
        assertThat("generated context should be valid", traceCtx.isValid(), is(true));
        assertThat("version should be 00", traceCtx.getVersion(), is("00"));
        assertThat("flags should be 01 (sampled)", traceCtx.getFlags(), is("01"));
        assertThat("traceId should be 32 hex chars", traceCtx.getTraceId().length(), is(32));
        assertThat("parentId should be 16 hex chars", traceCtx.getParentId().length(), is(16));
    }

    @Test
    public void shouldNotGenerateTraceIdWhenDisabledAndNoTraceparent() throws Exception {
        // given -- default config (otelGenerateTraceId disabled)
        Configuration config = configuration();
        ChannelHandlerContext ctx = mockCtxWithAttributeMap();
        Http3MockServerHandler handler = createHandler(config, ctx);

        // when
        DefaultHttp3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().method("GET");
        headersFrame.headers().path("/test");
        headersFrame.headers().scheme("https");
        handler.channelRead(ctx, headersFrame);

        HttpState httpState = getMockedHttpState(handler);
        when(httpState.handle(any(), any(), anyBoolean())).thenReturn(true);
        handler.channelInputClosed(ctx);

        // then -- no trace context
        W3CTraceContext traceCtx = ctx.channel().attr(TraceContextAttributes.TRACE_CONTEXT).get();
        assertThat("trace context should not be generated when disabled", traceCtx, is(nullValue()));
    }

    @Test
    public void shouldPreferExistingTraceparentOverGeneration() throws Exception {
        // given -- otelGenerateTraceId enabled AND request has traceparent
        Configuration config = configuration().otelGenerateTraceId(true);
        ChannelHandlerContext ctx = mockCtxWithAttributeMap();
        Http3MockServerHandler handler = createHandler(config, ctx);

        // when
        DefaultHttp3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().method("GET");
        headersFrame.headers().path("/test");
        headersFrame.headers().scheme("https");
        headersFrame.headers().add("traceparent", VALID_TRACEPARENT);
        handler.channelRead(ctx, headersFrame);

        HttpState httpState = getMockedHttpState(handler);
        when(httpState.handle(any(), any(), anyBoolean())).thenReturn(true);
        handler.channelInputClosed(ctx);

        // then -- uses the existing trace context, not a generated one
        W3CTraceContext traceCtx = ctx.channel().attr(TraceContextAttributes.TRACE_CONTEXT).get();
        assertThat("should use existing traceparent", traceCtx, is(notNullValue()));
        assertThat("traceId should match the provided header", traceCtx.getTraceId(),
            is("4bf92f3577b34da6a3ce929d0e0e4736"));
    }

    // ---- helper methods ----

    /**
     * Create a handler with a mocked HttpState (that we can retrieve later).
     */
    private Http3MockServerHandler createHandler(Configuration config, ChannelHandlerContext ctx) {
        HttpState httpState = mock(HttpState.class);
        HttpActionHandler actionHandler = mock(HttpActionHandler.class);
        Metrics metrics = new Metrics(config);
        return new Http3MockServerHandler(config, LOGGER, httpState, actionHandler, metrics);
    }

    /**
     * Get the mocked HttpState from the handler via reflection.
     */
    private HttpState getMockedHttpState(Http3MockServerHandler handler) throws Exception {
        java.lang.reflect.Field field = Http3MockServerHandler.class.getDeclaredField("httpState");
        field.setAccessible(true);
        return (HttpState) field.get(handler);
    }

    /**
     * Create a mock ChannelHandlerContext backed by a real AttributeMap
     * so channel attributes (like TRACE_CONTEXT) work correctly.
     */
    private ChannelHandlerContext mockCtxWithAttributeMap() {
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        Channel channel = mock(Channel.class);
        DefaultAttributeMap attrMap = new DefaultAttributeMap();

        when(ctx.channel()).thenReturn(channel);
        when(ctx.alloc()).thenReturn(ByteBufAllocator.DEFAULT);

        // delegate attr() calls to the real attribute map
        when(channel.attr(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            io.netty.util.AttributeKey<Object> key = (io.netty.util.AttributeKey<Object>) invocation.getArgument(0);
            return attrMap.attr(key);
        });

        return ctx;
    }
}
