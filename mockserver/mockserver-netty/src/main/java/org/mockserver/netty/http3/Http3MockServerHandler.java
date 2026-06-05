package org.mockserver.netty.http3;

import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3DataFrame;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import io.netty.handler.codec.http3.Http3RequestStreamInboundHandler;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicStreamChannel;
import org.mockserver.authentication.AuthenticationException;
import org.mockserver.authentication.AuthenticationHandler;
import org.mockserver.configuration.Configuration;
import org.mockserver.cors.CORSHeaders;
import org.mockserver.grpc.GrpcException;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mappers.JDKCertificateToMockServerX509Certificate;
import org.mockserver.metrics.Metrics;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.model.HttpRequest;
import org.mockserver.netty.mcp.JsonRpcMessage;
import org.mockserver.netty.mcp.McpRequestProcessor;
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
    /** Null when MCP is not wired (legacy/test constructors). */
    private final McpRequestProcessor mcpRequestProcessor;

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
        this(configuration, mockServerLogger, httpState, httpActionHandler, metrics, null);
    }

    public Http3MockServerHandler(
        Configuration configuration,
        MockServerLogger mockServerLogger,
        HttpState httpState,
        HttpActionHandler httpActionHandler,
        Metrics metrics,
        McpRequestProcessor mcpRequestProcessor
    ) {
        this.configuration = configuration;
        this.mockServerLogger = mockServerLogger;
        this.httpState = httpState;
        this.httpActionHandler = httpActionHandler;
        this.metrics = metrics;
        this.jdkCertificateToMockServerX509Certificate = new JDKCertificateToMockServerX509Certificate(mockServerLogger);
        this.mcpRequestProcessor = mcpRequestProcessor;
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

            // MCP dispatch: intercept /mockserver/mcp before the normal pipeline
            if (mcpRequestProcessor != null && McpRequestProcessor.isMcpPath(parsedHeaders.path())) {
                handleMcpOverHttp3(ctx, request);
                return;
            }

            // gRPC-over-HTTP/3: detect gRPC requests by content-type and route
            // through the gRPC adapter, which decodes gRPC framing, converts
            // protobuf to JSON for matching, and writes the response with correct
            // gRPC trailing HEADERS framing. Non-gRPC requests take the normal path.
            String contentType = request.getFirstHeader("content-type");
            if (GrpcHttp3Adapter.isGrpcRequest(contentType)) {
                handleGrpcRequest(ctx, request);
                return;
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
     * Handle a gRPC request over HTTP/3: decode the gRPC framing, convert
     * protobuf to JSON for expectation matching, and write the response with
     * correct gRPC wire framing (initial HEADERS + DATA + trailing HEADERS
     * with grpc-status).
     * <p>
     * This reuses the existing {@link GrpcHttp3Adapter} and
     * {@link Http3GrpcResponseWriter} to avoid duplicating any gRPC codec logic.
     * The descriptor store from {@link HttpState} provides the protobuf schema
     * needed for JSON conversion.
     */
    private void handleGrpcRequest(ChannelHandlerContext ctx, HttpRequest request) {
        GrpcProtoDescriptorStore descriptorStore = httpState.getGrpcDescriptorStore();

        if (descriptorStore == null || !descriptorStore.hasServices()) {
            // No proto descriptors loaded: pass the request through unchanged.
            // The body remains gRPC-framed (binary), which lets raw binary
            // expectations match, and the response writer will frame
            // grpc-status correctly in trailing HEADERS.
            Http3GrpcResponseWriter grpcResponseWriter = new Http3GrpcResponseWriter(
                configuration, mockServerLogger, ctx, descriptorStore, null, null
            );
            processRequestThroughPipeline(ctx, request, grpcResponseWriter);
            return;
        }

        try {
            HttpRequest grpcRequest = GrpcHttp3Adapter.transformGrpcRequest(request, descriptorStore);
            // Extract service/method from the transformed request so the
            // response writer can re-encode the JSON response to protobuf
            String grpcService = grpcRequest.getFirstHeader("x-grpc-service");
            String grpcMethod = grpcRequest.getFirstHeader("x-grpc-method");
            Http3GrpcResponseWriter grpcResponseWriter = new Http3GrpcResponseWriter(
                configuration, mockServerLogger, ctx, descriptorStore, grpcService, grpcMethod
            );
            processRequestThroughPipeline(ctx, grpcRequest, grpcResponseWriter);
        } catch (GrpcException e) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setHttpRequest(request)
                    .setMessageFormat("gRPC request error over HTTP/3:{}:{}")
                    .setArguments(request.getPath(), e.getMessage())
            );
            GrpcStatusMapper.GrpcStatusCode statusCode =
                e.getMessage() != null && e.getMessage().startsWith("unknown gRPC method")
                    ? GrpcStatusMapper.GrpcStatusCode.UNIMPLEMENTED
                    : GrpcStatusMapper.GrpcStatusCode.INTERNAL;
            Http3GrpcResponseWriter errorWriter = new Http3GrpcResponseWriter(
                configuration, mockServerLogger, ctx, descriptorStore, null, null
            );
            errorWriter.writeErrorResponse(statusCode, e.getMessage());
        } catch (Exception e) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setHttpRequest(request)
                    .setMessageFormat("failed to convert gRPC request to JSON over HTTP/3:{}:{}")
                    .setArguments(request.getPath(), e.getMessage())
            );
            Http3GrpcResponseWriter errorWriter = new Http3GrpcResponseWriter(
                configuration, mockServerLogger, ctx, descriptorStore, null, null
            );
            errorWriter.writeErrorResponse(
                GrpcStatusMapper.GrpcStatusCode.INTERNAL,
                "failed to decode gRPC request: " + e.getMessage()
            );
        }
    }

    /**
     * Route a request through the standard MockServer pipeline (control-plane
     * then data-plane), using the given response writer.
     */
    private void processRequestThroughPipeline(
        ChannelHandlerContext ctx,
        HttpRequest request,
        ResponseWriter responseWriter
    ) {
        if (!httpState.handle(request, responseWriter, false)) {
            try {
                httpActionHandler.processAction(
                    request,
                    responseWriter,
                    ctx,
                    buildLocalAddresses(ctx),
                    false,
                    true
                );
            } catch (Throwable throwable) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.ERROR)
                        .setHttpRequest(request)
                        .setMessageFormat("exception processing gRPC request over HTTP/3:{}error:{}")
                        .setArguments(request, throwable.getMessage())
                        .setThrowable(throwable)
                );
            }
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

    /**
     * Handle an MCP request over HTTP/3. Determines the HTTP method from the
     * request, delegates to {@link McpRequestProcessor}, and writes the result
     * as HTTP/3 frames (headers + optional data + stream shutdown).
     * <p>
     * Control-plane authentication is enforced for POST, GET, and DELETE --
     * mirroring the TCP path ({@code McpStreamableHttpHandler.authenticateRequest}).
     * OPTIONS (CORS preflight) is exempt, matching TCP behaviour.
     */
    private void handleMcpOverHttp3(ChannelHandlerContext ctx, HttpRequest request) {
        String method = request.getMethod() != null ? request.getMethod().getValue() : "GET";
        String origin = request.getFirstHeader("origin");
        String accessControlRequestHeaders = request.getFirstHeader("access-control-request-headers");
        String mcpSessionId = request.getFirstHeader("mcp-session-id");
        McpRequestProcessor.McpResult result;

        switch (method.toUpperCase()) {
            case "OPTIONS":
                // CORS preflight -- exempt from authentication (matches TCP path)
                boolean hasOrigin = origin != null && !origin.isEmpty();
                result = mcpRequestProcessor.handleOptions(hasOrigin);
                break;
            case "POST":
                if (!authenticateMcpRequest(ctx, request)) {
                    result = buildUnauthorizedResult();
                    break;
                }
                String body = request.getBodyAsString();
                result = mcpRequestProcessor.handlePost(body, mcpSessionId);
                break;
            case "DELETE":
                if (!authenticateMcpRequest(ctx, request)) {
                    result = buildUnauthorizedResult();
                    break;
                }
                result = mcpRequestProcessor.handleDelete(mcpSessionId);
                break;
            case "GET":
                if (!authenticateMcpRequest(ctx, request)) {
                    result = buildUnauthorizedResult();
                    break;
                }
                result = mcpRequestProcessor.handleGet();
                break;
            default:
                result = mcpRequestProcessor.handleOptions(false);
                break;
        }

        writeMcpResultAsHttp3(ctx, result, origin, accessControlRequestHeaders);
    }

    /**
     * Authenticate an MCP request against the control-plane authentication handler,
     * mirroring the TCP path's {@code McpStreamableHttpHandler.authenticateRequest()}.
     * <p>
     * The request already has the client certificate chain attached (captured in
     * {@link #captureClientCertificates}), so mTLS authentication works over H3.
     *
     * @return true if authentication passed (or no handler is configured); false if rejected
     */
    private boolean authenticateMcpRequest(ChannelHandlerContext ctx, HttpRequest request) {
        AuthenticationHandler authHandler = httpState.getControlPlaneAuthenticationHandler();
        if (authHandler == null) {
            return true;
        }
        try {
            return authHandler.controlPlaneRequestAuthenticated(request);
        } catch (AuthenticationException e) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setHttpRequest(request)
                    .setMessageFormat("MCP-over-H3 authentication failed: {}")
                    .setArguments(e.getMessage())
                    .setThrowable(e)
            );
            return false;
        }
    }

    /**
     * Build an unauthorized (401) MCP result with the same JSON-RPC error body
     * as the TCP path's {@code McpStreamableHttpHandler.writeUnauthorized()}.
     */
    private McpRequestProcessor.McpResult buildUnauthorizedResult() {
        byte[] body;
        try {
            body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(
                JsonRpcMessage.JsonRpcResponse.error(null, JsonRpcMessage.INVALID_REQUEST, "Unauthorized for control plane"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            body = "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32600,\"message\":\"Unauthorized for control plane\"},\"id\":null}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        return new McpRequestProcessor.McpResult(401, body, null);
    }

    /**
     * Write an {@link McpRequestProcessor.McpResult} as HTTP/3 frames,
     * including CORS headers when the request had an Origin header --
     * mirroring the TCP path's {@code McpStreamableHttpHandler.addCorsHeaders()}.
     *
     * @param origin the request's Origin header value (may be null/empty)
     * @param accessControlRequestHeaders the request's Access-Control-Request-Headers value (may be null)
     */
    private void writeMcpResultAsHttp3(
        ChannelHandlerContext ctx, McpRequestProcessor.McpResult result,
        String origin, String accessControlRequestHeaders
    ) {
        DefaultHttp3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().status(String.valueOf(result.getStatusCode()));
        headersFrame.headers().add("server", "mockserver-http3");

        if (result.hasBody()) {
            headersFrame.headers().add("content-type", "application/json");
        }
        if (result.getSessionId() != null) {
            headersFrame.headers().add("mcp-session-id", result.getSessionId());
        }

        // CORS headers -- mirror the TCP path (McpStreamableHttpHandler.addCorsHeaders)
        if (origin != null && !origin.isEmpty()) {
            headersFrame.headers().add("access-control-allow-origin", origin);
            headersFrame.headers().add("access-control-allow-methods", CORSHeaders.DEFAULT_ALLOW_METHODS);
            String allowHeaders = (accessControlRequestHeaders != null && !accessControlRequestHeaders.isEmpty())
                ? accessControlRequestHeaders : CORSHeaders.DEFAULT_ALLOW_HEADERS;
            headersFrame.headers().add("access-control-allow-headers", allowHeaders);
            headersFrame.headers().add("access-control-expose-headers", "Mcp-Session-Id, " + CORSHeaders.DEFAULT_ALLOW_HEADERS);
            headersFrame.headers().add("access-control-max-age", "300");
        }

        ctx.write(headersFrame);
        if (result.hasBody()) {
            ctx.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(result.getBody())))
                .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
        } else {
            ctx.flush();
            if (ctx.channel() instanceof QuicStreamChannel) {
                ((QuicStreamChannel) ctx.channel()).shutdownOutput();
            }
        }
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
