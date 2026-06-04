package org.mockserver.netty.http3;

import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http3.Http3DataFrame;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import io.netty.handler.codec.http3.Http3RequestStreamInboundHandler;
import io.netty.handler.codec.quic.QuicChannel;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mappers.JDKCertificateToMockServerX509Certificate;
import org.mockserver.metrics.Metrics;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.model.HttpRequest;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.telemetry.TraceContextAttributes;
import org.mockserver.telemetry.W3CTraceContext;
import org.slf4j.event.Level;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import java.net.InetSocketAddress;
import java.security.cert.Certificate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

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
    private final JDKCertificateToMockServerX509Certificate jdkCertificateToMockServerX509Certificate;

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
        this.jdkCertificateToMockServerX509Certificate = new JDKCertificateToMockServerX509Certificate(mockServerLogger);
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

            // mTLS client-certificate capture: extract the peer certificate chain
            // from the QUIC SSLEngine (analogous to the TCP path's
            // SniHandler.retrieveClientCertificates → MockServerHttpServerCodec →
            // withClientCertificateChain) so cert-based expectation matching and
            // verification work over HTTP/3.
            captureClientCertificates(ctx, request);

            // W3C trace-context extraction: parse traceparent/tracestate from the
            // request headers (or generate a context when otelGenerateTraceId is set)
            // and store on the channel attribute. HttpActionHandler reads this attr
            // to parent OTel spans -- identical logic to TraceContextHandler on the TCP path.
            extractOrGenerateTraceContext(ctx, request);

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
     * Capture the peer (client) certificate chain from the QUIC handshake and
     * plumb it into the request via {@link HttpRequest#withClientCertificateChain},
     * exactly like the TCP path does via {@code SniHandler.retrieveClientCertificates}
     * → {@code MockServerHttpServerCodec}. This enables cert-based expectation
     * matching and verification over HTTP/3.
     * <p>
     * The QUIC SSLEngine is obtained from the parent {@link QuicChannel}. If
     * the client did not present a certificate (SSLPeerUnverifiedException),
     * the request is left without a certificate chain (no error).
     */
    private void captureClientCertificates(ChannelHandlerContext ctx, HttpRequest request) {
        try {
            // Walk QuicStreamChannel → QuicChannel to get the QUIC SSLEngine
            Channel streamChannel = ctx.channel();
            Channel parentChannel = streamChannel.parent();
            if (parentChannel instanceof QuicChannel) {
                QuicChannel quicChannel = (QuicChannel) parentChannel;
                SSLEngine sslEngine = quicChannel.sslEngine();
                if (sslEngine != null) {
                    SSLSession sslSession = sslEngine.getSession();
                    if (sslSession != null) {
                        try {
                            Certificate[] peerCertificates = sslSession.getPeerCertificates();
                            if (peerCertificates != null && peerCertificates.length > 0) {
                                jdkCertificateToMockServerX509Certificate.setClientCertificates(request, peerCertificates);
                            }
                        } catch (SSLPeerUnverifiedException ignore) {
                            // client did not present a certificate -- normal for non-mTLS connections
                        }
                    }
                }
            }
        } catch (Exception e) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.DEBUG)
                    .setHttpRequest(request)
                    .setMessageFormat("failed to capture client certificates from QUIC session: {}")
                    .setArguments(e.getMessage())
            );
        }
    }

    /**
     * Extract W3C trace context from the request's traceparent/tracestate headers,
     * or generate a new context when {@code otelGenerateTraceId} is enabled and no
     * traceparent header is present. The parsed/generated context is stored on the
     * channel attribute so that {@code HttpActionHandler} can attach it as a remote
     * parent to request-level OpenTelemetry spans.
     * <p>
     * This replicates the inbound logic of {@code TraceContextHandler} (TCP path)
     * using the same {@link W3CTraceContext} and {@link TraceContextAttributes}
     * types and the same configuration gates.
     */
    private void extractOrGenerateTraceContext(ChannelHandlerContext ctx, HttpRequest request) {
        String traceparent = request.getFirstHeader("traceparent");
        String tracestate = request.getFirstHeader("tracestate");

        if (traceparent != null && !traceparent.isEmpty()) {
            W3CTraceContext context = W3CTraceContext.parse(traceparent, tracestate);
            if (context != null && context.isValid()) {
                ctx.channel().attr(TraceContextAttributes.TRACE_CONTEXT).set(context);
            }
        } else if (configuration.otelGenerateTraceId()) {
            W3CTraceContext generated = generateTraceContext();
            ctx.channel().attr(TraceContextAttributes.TRACE_CONTEXT).set(generated);
        }
    }

    /**
     * Generate a new W3C trace context with a random trace ID and parent ID.
     * Uses version 00 and sampled flag 01.
     */
    private static W3CTraceContext generateTraceContext() {
        String traceId = randomHexString(32);
        String parentId = randomHexString(16);
        return new W3CTraceContext("00", traceId, parentId, "01", null);
    }

    /**
     * Generate a lowercase hex string of the specified length from random UUIDs.
     */
    static String randomHexString(int length) {
        StringBuilder sb = new StringBuilder(length);
        while (sb.length() < length) {
            sb.append(UUID.randomUUID().toString().replace("-", ""));
        }
        return sb.substring(0, length);
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
