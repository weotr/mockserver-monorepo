package org.mockserver.netty.unification;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.telemetry.W3CTraceContext;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class TraceContextHandlerTest {

    private static final String VALID_TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    private static final String VALID_TRACESTATE = "rojo=00f067aa0ba902b7";

    private EmbeddedChannel channel;

    @After
    public void tearDown() {
        if (channel != null) {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldExtractTraceContextFromInboundRequest() {
        // given
        Configuration configuration = configuration();
        channel = new EmbeddedChannel(new TraceContextHandler(configuration));

        // when
        HttpRequest request = request().withPath("/test").withHeader("traceparent", VALID_TRACEPARENT);
        channel.writeInbound(request);

        // then
        W3CTraceContext ctx = channel.attr(TraceContextHandler.TRACE_CONTEXT).get();
        assertThat(ctx, is(notNullValue()));
        assertThat(ctx.getTraceId(), is("4bf92f3577b34da6a3ce929d0e0e4736"));
        assertThat(ctx.getParentId(), is("00f067aa0ba902b7"));
        assertThat(ctx.getFlags(), is("01"));

        // request passes through
        HttpRequest propagated = channel.readInbound();
        assertThat(propagated, is(notNullValue()));
    }

    @Test
    public void shouldExtractTraceContextWithTracestate() {
        // given
        Configuration configuration = configuration();
        channel = new EmbeddedChannel(new TraceContextHandler(configuration));

        // when
        HttpRequest request = request().withPath("/test")
            .withHeader("traceparent", VALID_TRACEPARENT)
            .withHeader("tracestate", VALID_TRACESTATE);
        channel.writeInbound(request);

        // then
        W3CTraceContext ctx = channel.attr(TraceContextHandler.TRACE_CONTEXT).get();
        assertThat(ctx, is(notNullValue()));
        assertThat(ctx.getTraceState(), is(VALID_TRACESTATE));
    }

    @Test
    public void shouldNotSetAttributeWhenNoTraceparentHeader() {
        // given
        Configuration configuration = configuration();
        channel = new EmbeddedChannel(new TraceContextHandler(configuration));

        // when
        HttpRequest request = request().withPath("/test");
        channel.writeInbound(request);

        // then
        W3CTraceContext ctx = channel.attr(TraceContextHandler.TRACE_CONTEXT).get();
        assertThat(ctx, is(nullValue()));
    }

    @Test
    public void shouldNotSetAttributeForInvalidTraceparent() {
        // given
        Configuration configuration = configuration();
        channel = new EmbeddedChannel(new TraceContextHandler(configuration));

        // when — traceId too short (not 32 chars)
        HttpRequest request = request().withPath("/test")
            .withHeader("traceparent", "00-short-00f067aa0ba902b7-01");
        channel.writeInbound(request);

        // then
        W3CTraceContext ctx = channel.attr(TraceContextHandler.TRACE_CONTEXT).get();
        assertThat(ctx, is(nullValue()));
    }

    @Test
    public void shouldPropagateTraceContextToResponseWhenEnabled() {
        // given
        Configuration configuration = configuration().otelPropagateTraceContext(true);
        channel = new EmbeddedChannel(new TraceContextHandler(configuration));

        // set the trace context on the channel as if an inbound request was processed
        HttpRequest request = request().withPath("/test")
            .withHeader("traceparent", VALID_TRACEPARENT)
            .withHeader("tracestate", VALID_TRACESTATE);
        channel.writeInbound(request);

        // when — write a response outbound
        HttpResponse response = response().withStatusCode(200);
        channel.writeOutbound(response);

        // then — response should have trace headers
        HttpResponse outbound = channel.readOutbound();
        assertThat(outbound, is(notNullValue()));
        assertThat(outbound.getFirstHeader("traceparent"), is(VALID_TRACEPARENT));
        assertThat(outbound.getFirstHeader("tracestate"), is(VALID_TRACESTATE));
    }

    @Test
    public void shouldNotPropagateTraceContextToResponseWhenDisabled() {
        // given — default config (propagation disabled)
        Configuration configuration = configuration();
        channel = new EmbeddedChannel(new TraceContextHandler(configuration));

        HttpRequest request = request().withPath("/test")
            .withHeader("traceparent", VALID_TRACEPARENT);
        channel.writeInbound(request);

        // when
        HttpResponse response = response().withStatusCode(200);
        channel.writeOutbound(response);

        // then — no trace headers added to response
        HttpResponse outbound = channel.readOutbound();
        assertThat(outbound, is(notNullValue()));
        assertThat(outbound.getFirstHeader("traceparent"), is(""));
    }

    @Test
    public void shouldNotPropagateTracestateWhenEmpty() {
        // given
        Configuration configuration = configuration().otelPropagateTraceContext(true);
        channel = new EmbeddedChannel(new TraceContextHandler(configuration));

        // inbound request with traceparent but no tracestate
        HttpRequest request = request().withPath("/test")
            .withHeader("traceparent", VALID_TRACEPARENT);
        channel.writeInbound(request);

        // when
        HttpResponse response = response().withStatusCode(200);
        channel.writeOutbound(response);

        // then — traceparent propagated, no tracestate header
        HttpResponse outbound = channel.readOutbound();
        assertThat(outbound.getFirstHeader("traceparent"), is(VALID_TRACEPARENT));
        assertThat(outbound.getFirstHeader("tracestate"), is(""));
    }

    @Test
    public void shouldGenerateTraceIdWhenEnabledAndNoTraceparent() {
        // given
        Configuration configuration = configuration().otelGenerateTraceId(true);
        channel = new EmbeddedChannel(new TraceContextHandler(configuration));

        // when — request without traceparent header
        HttpRequest request = request().withPath("/test");
        channel.writeInbound(request);

        // then — a trace context is generated
        W3CTraceContext ctx = channel.attr(TraceContextHandler.TRACE_CONTEXT).get();
        assertThat(ctx, is(notNullValue()));
        assertThat(ctx.isValid(), is(true));
        assertThat(ctx.getVersion(), is("00"));
        assertThat(ctx.getFlags(), is("01"));
        assertThat(ctx.getTraceId().length(), is(32));
        assertThat(ctx.getParentId().length(), is(16));
    }

    @Test
    public void shouldNotGenerateTraceIdWhenDisabled() {
        // given — default config (generation disabled)
        Configuration configuration = configuration();
        channel = new EmbeddedChannel(new TraceContextHandler(configuration));

        // when — request without traceparent header
        HttpRequest request = request().withPath("/test");
        channel.writeInbound(request);

        // then — no trace context generated
        W3CTraceContext ctx = channel.attr(TraceContextHandler.TRACE_CONTEXT).get();
        assertThat(ctx, is(nullValue()));
    }

    @Test
    public void shouldNotGenerateTraceIdWhenTraceparentAlreadyPresent() {
        // given — both extraction and generation enabled
        Configuration configuration = configuration().otelGenerateTraceId(true);
        channel = new EmbeddedChannel(new TraceContextHandler(configuration));

        // when — request WITH traceparent header
        HttpRequest request = request().withPath("/test")
            .withHeader("traceparent", VALID_TRACEPARENT);
        channel.writeInbound(request);

        // then — uses the existing trace context, not a generated one
        W3CTraceContext ctx = channel.attr(TraceContextHandler.TRACE_CONTEXT).get();
        assertThat(ctx, is(notNullValue()));
        assertThat(ctx.getTraceId(), is("4bf92f3577b34da6a3ce929d0e0e4736"));
    }

    @Test
    public void shouldPassThroughNonHttpRequestMessages() {
        // given
        Configuration configuration = configuration();
        channel = new EmbeddedChannel(new TraceContextHandler(configuration));

        // when — write a non-HttpRequest object
        String plainMessage = "hello";
        channel.writeInbound(plainMessage);

        // then — passes through
        Object received = channel.readInbound();
        assertThat(received, is("hello"));
        assertThat(channel.attr(TraceContextHandler.TRACE_CONTEXT).get(), is(nullValue()));
    }

    @Test
    public void shouldPassThroughNonHttpResponseOutboundMessages() {
        // given
        Configuration configuration = configuration().otelPropagateTraceContext(true);
        channel = new EmbeddedChannel(new TraceContextHandler(configuration));

        // set a trace context
        HttpRequest request = request().withPath("/test")
            .withHeader("traceparent", VALID_TRACEPARENT);
        channel.writeInbound(request);

        // when — write a non-HttpResponse object outbound
        String plainMessage = "outbound";
        channel.writeOutbound(plainMessage);

        // then — passes through unmodified
        Object received = channel.readOutbound();
        assertThat(received, is("outbound"));
    }
}
