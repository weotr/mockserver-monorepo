package org.mockserver.netty.http3;

import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.incubator.codec.http3.Http3DataFrame;
import io.netty.incubator.codec.http3.Http3HeadersFrame;
import io.netty.incubator.codec.http3.Http3RequestStreamInboundHandler;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.metrics.Metrics;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.model.HttpRequest;
import org.mockserver.responsewriter.ResponseWriter;
import org.slf4j.event.Level;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.mockserver.metrics.Metrics.Name.REQUESTS_RECEIVED_COUNT;

/**
 * HTTP/3 request stream handler that bridges incoming QUIC requests into
 * MockServer's standard request-processing pipeline (expectation matching,
 * actions, recording, proxy forwarding).
 * <p>
 * Each QUIC bidirectional stream gets its own instance. The handler
 * accumulates the request headers and body data frames, then routes the
 * resulting {@link HttpRequest} through the same {@link HttpState} and
 * {@link HttpActionHandler} used by HTTP/1.1 and HTTP/2.
 */
public class Http3MockServerHandler extends Http3RequestStreamInboundHandler {

    private final Configuration configuration;
    private final MockServerLogger mockServerLogger;
    private final HttpState httpState;
    private final HttpActionHandler httpActionHandler;
    private final Metrics metrics;

    // per-stream state: headers + accumulated body
    private Http3RequestBridge.ParsedHeaders parsedHeaders;
    private CompositeByteBuf bodyAccumulator;

    public Http3MockServerHandler(
        Configuration configuration,
        MockServerLogger mockServerLogger,
        HttpState httpState,
        HttpActionHandler httpActionHandler,
        Metrics metrics
    ) {
        this.configuration = configuration;
        this.mockServerLogger = mockServerLogger;
        this.httpState = httpState;
        this.httpActionHandler = httpActionHandler;
        this.metrics = metrics;
    }

    @Override
    protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame headersFrame) {
        parsedHeaders = Http3RequestBridge.parseHeaders(headersFrame);
        bodyAccumulator = ctx.alloc().compositeBuffer();
    }

    @Override
    protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame dataFrame) {
        try {
            if (bodyAccumulator != null) {
                Http3RequestBridge.accumulateBody(bodyAccumulator, dataFrame);
            }
        } finally {
            dataFrame.release();
        }
    }

    @Override
    protected void channelInputClosed(ChannelHandlerContext ctx) {
        try {
            if (parsedHeaders == null) {
                // no headers received -- nothing to process
                return;
            }

            byte[] body = bodyAccumulator != null ? Http3RequestBridge.readAccumulatedBody(bodyAccumulator) : new byte[0];

            HttpRequest request = Http3RequestBridge.toHttpRequest(
                parsedHeaders.method(),
                parsedHeaders.path(),
                parsedHeaders.scheme(),
                parsedHeaders.authority(),
                parsedHeaders.headers(),
                body
            );

            if (configuration.metricsEnabled()) {
                metrics.increment(REQUESTS_RECEIVED_COUNT);
            }

            ResponseWriter responseWriter = new Http3ResponseWriter(configuration, mockServerLogger, ctx);

            // first, try control-plane handling (expectations CRUD, status, etc.)
            if (!httpState.handle(request, responseWriter, false)) {
                // data-plane: match expectations, execute actions, proxy, etc.
                try {
                    httpActionHandler.processAction(
                        request,
                        responseWriter,
                        ctx,
                        buildLocalAddresses(ctx),
                        false,                  // not proxying
                        true                    // synchronous processing
                    );
                } catch (Throwable throwable) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.ERROR)
                            .setHttpRequest(request)
                            .setMessageFormat("exception processing HTTP/3 request:{}error:{}")
                            .setArguments(request, throwable.getMessage())
                            .setThrowable(throwable)
                    );
                }
            }
        } finally {
            releaseBodyAccumulator();
        }
    }

    /**
     * Build a set of local address strings (host:port variants) to pass to the
     * action handler so that unmatched requests are not mistakenly treated as
     * proxy-forwarding candidates.
     */
    private Set<String> buildLocalAddresses(ChannelHandlerContext ctx) {
        Set<String> addresses = new HashSet<>();
        // walk up to the parent QuicChannel to find the UDP local address
        Channel parentChannel = ctx.channel().parent();
        if (parentChannel != null) {
            parentChannel = parentChannel.parent(); // QuicStreamChannel -> QuicChannel -> DatagramChannel
        }
        int port = -1;
        if (parentChannel != null && parentChannel.localAddress() instanceof InetSocketAddress) {
            port = ((InetSocketAddress) parentChannel.localAddress()).getPort();
        } else if (ctx.channel().localAddress() instanceof InetSocketAddress) {
            port = ((InetSocketAddress) ctx.channel().localAddress()).getPort();
        }
        if (port > 0) {
            String portSuffix = ":" + port;
            addresses.add("localhost" + portSuffix);
            addresses.add("127.0.0.1" + portSuffix);
            addresses.add("::1" + portSuffix);
            addresses.add("[::1]" + portSuffix);
            addresses.add("0:0:0:0:0:0:0:1" + portSuffix);
        }
        return addresses;
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        // safety-net: release the body accumulator if the handler is removed before
        // channelInputClosed fires (e.g. abrupt disconnect, exception, pipeline change)
        releaseBodyAccumulator();
        super.handlerRemoved(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        mockServerLogger.logEvent(
            new LogEntry()
                .setLogLevel(Level.WARN)
                .setMessageFormat("exception in HTTP/3 request handler: {}")
                .setArguments(cause.getMessage())
                .setThrowable(cause)
        );
        ctx.close();
    }

    /**
     * Release the body accumulator if it is non-null and has not already been
     * released. Guards against double-release by nulling the reference.
     */
    private void releaseBodyAccumulator() {
        if (bodyAccumulator != null) {
            bodyAccumulator.release();
            bodyAccumulator = null;
        }
    }
}
