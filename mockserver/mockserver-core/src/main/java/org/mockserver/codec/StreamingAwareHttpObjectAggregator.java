package org.mockserver.codec;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.*;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.AttributeKey;
import org.mockserver.configuration.Configuration;
import org.mockserver.httpclient.HttpClientHandler;
import org.mockserver.httpclient.StreamingResponseRelayHandler;
import org.mockserver.logging.MockServerLogger;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * An {@link HttpObjectAggregator} that can recognise streaming responses — specifically
 * Server-Sent Events ({@code Content-Type: text/event-stream}) — so that, when
 * MockServer is acting as a proxy, such responses can be relayed to the client
 * incrementally instead of being fully buffered in memory before being forwarded.
 * <p>
 * When streaming is detected and enabled, this handler removes itself from the pipeline
 * and installs a {@link StreamingResponseRelayHandler} that processes the unaggregated
 * {@link HttpObject}s. The {@link HttpClientHandler} is also removed to prevent double
 * completion of the response future.
 * <p>
 * When streaming is not detected (or not enabled), the handler delegates to
 * {@link HttpObjectAggregator} so the non-streaming path stays byte-identical.
 * <p>
 * Only the {@code text/event-stream} content type triggers streaming detection.
 * Ordinary chunked responses (e.g. Tomcat/servlet responses that use chunked
 * transfer-encoding without {@code Content-Length}) are aggregated normally.
 */
public class StreamingAwareHttpObjectAggregator extends HttpObjectAggregator {

    private final Configuration configuration;
    private final MockServerLogger mockServerLogger;
    private final boolean relayOnly;

    /**
     * Create an aggregator with streaming awareness for the {@link NettyHttpClient} forward path.
     * When a streaming response is detected, it installs a {@link StreamingResponseRelayHandler}
     * that completes the {@code RESPONSE_FUTURE} at head time.
     *
     * @param maxContentLength the maximum content length for non-streaming responses
     * @param configuration    the MockServer configuration (for streaming properties)
     * @param mockServerLogger the logger
     */
    public StreamingAwareHttpObjectAggregator(int maxContentLength, Configuration configuration, MockServerLogger mockServerLogger) {
        this(maxContentLength, configuration, mockServerLogger, false);
    }

    /**
     * Create an aggregator with streaming awareness.
     *
     * @param maxContentLength the maximum content length for non-streaming responses
     * @param configuration    the MockServer configuration (for streaming properties)
     * @param mockServerLogger the logger
     * @param relayOnly        if true, when a streaming response is detected the aggregator simply
     *                         removes itself from the pipeline so that unaggregated {@link HttpObject}s
     *                         flow through to the next handler. This mode is used on the loopback
     *                         pipeline where there is no {@code RESPONSE_FUTURE} to complete.
     */
    public StreamingAwareHttpObjectAggregator(int maxContentLength, Configuration configuration, MockServerLogger mockServerLogger, boolean relayOnly) {
        super(maxContentLength);
        this.configuration = configuration;
        this.mockServerLogger = mockServerLogger;
        this.relayOnly = relayOnly;
    }

    /**
     * Backwards-compatible constructor for use without streaming support (e.g. in relay
     * pipelines where Configuration is not yet threaded through). Behaves identically to
     * a plain {@link HttpObjectAggregator}.
     *
     * @param maxContentLength the maximum content length
     */
    public StreamingAwareHttpObjectAggregator(int maxContentLength) {
        super(maxContentLength);
        this.configuration = null;
        this.mockServerLogger = null;
        this.relayOnly = false;
    }

    /**
     * A response should be relayed as a stream rather than aggregated when it is a
     * Server-Sent Events stream ({@code Content-Type: text/event-stream}).
     * <p>
     * Only SSE responses are detected as streaming. Ordinary chunked responses
     * (e.g. those produced by servlet containers like Tomcat, which strip
     * {@code Content-Length} and use chunked transfer-encoding for all responses)
     * are aggregated normally. Real streaming APIs — Anthropic, OpenAI, and MCP
     * streamable-HTTP — all use {@code text/event-stream}.
     *
     * @param response the response head (status line and headers)
     * @return true when the response body should be streamed through incrementally
     */
    public static boolean isStreamingResponse(HttpResponse response) {
        String contentType = response.headers().get(HttpHeaderNames.CONTENT_TYPE);
        return contentType != null && contentType.toLowerCase(Locale.US).contains("text/event-stream");
    }

    /**
     * Channel attribute key set by {@code NettyHttpClient} when streaming must be disabled
     * for a particular request (e.g. FORWARD_REPLACE with a response override). When set
     * to {@code true}, this aggregator always delegates to the standard
     * {@link HttpObjectAggregator} path regardless of response content type.
     */
    private static final AttributeKey<Boolean> DISABLE_RESPONSE_STREAMING = AttributeKey.valueOf("DISABLE_RESPONSE_STREAMING");

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpResponse && !(msg instanceof FullHttpResponse)) {
            HttpResponse response = (HttpResponse) msg;
            if (isStreamingEnabled() && !isStreamingDisabledOnChannel(ctx) && isStreamingResponse(response)) {
                switchToStreamingMode(ctx, response);
                return;
            }
        }
        // Non-streaming: delegate to super (standard aggregation)
        super.channelRead(ctx, msg);
    }

    private boolean isStreamingEnabled() {
        return configuration != null && Boolean.TRUE.equals(configuration.streamingResponsesEnabled());
    }

    private boolean isStreamingDisabledOnChannel(ChannelHandlerContext ctx) {
        Boolean disabled = ctx.channel().attr(DISABLE_RESPONSE_STREAMING).get();
        return Boolean.TRUE.equals(disabled);
    }

    private void switchToStreamingMode(ChannelHandlerContext ctx, HttpResponse responseHead) {
        ChannelPipeline pipeline = ctx.pipeline();

        if (relayOnly) {
            // Relay-only mode (loopback pipeline): just remove the aggregator so HttpObjects
            // flow directly to the next handler (e.g. DownstreamProxyRelayHandler).
            // ctx.fireChannelRead still works after remove because the context retains its
            // position in the pipeline chain.
            pipeline.remove(this);
            ctx.fireChannelRead(responseHead);
            return;
        }

        // Install the streaming relay handler
        StreamingResponseRelayHandler relayHandler = new StreamingResponseRelayHandler(configuration, mockServerLogger);

        // Remove HttpClientHandler to prevent double completion of RESPONSE_FUTURE
        if (pipeline.get(HttpClientHandler.class) != null) {
            pipeline.remove(HttpClientHandler.class);
        }

        // Remove MockServerHttpClientCodec since it expects FullHttpResponse and would
        // fail on unaggregated HttpObject messages
        if (pipeline.get(MockServerHttpClientCodec.class) != null) {
            pipeline.remove(MockServerHttpClientCodec.class);
        }

        // Add idle state handler for stream timeout
        int idleTimeout = configuration.streamIdleTimeoutSeconds();
        if (idleTimeout > 0) {
            pipeline.addBefore(ctx.name(), "streamIdleStateHandler", new IdleStateHandler(0, 0, idleTimeout, TimeUnit.SECONDS));
            pipeline.addAfter("streamIdleStateHandler", "streamIdleTimeoutHandler", new StreamIdleTimeoutHandler(mockServerLogger));
        }

        // Replace this aggregator with the streaming relay handler
        pipeline.replace(this, "streamingResponseRelayHandler", relayHandler);

        // Fire the response head through the new handler using its own context
        ChannelHandlerContext relayCtx = pipeline.context(relayHandler);
        try {
            relayHandler.channelRead(relayCtx, responseHead);
        } catch (Exception e) {
            relayCtx.fireExceptionCaught(e);
        }
    }
}
