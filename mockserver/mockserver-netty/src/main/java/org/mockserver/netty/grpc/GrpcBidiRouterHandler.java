package org.mockserver.netty.grpc;

import com.google.protobuf.Descriptors;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import org.mockserver.configuration.Configuration;
import org.mockserver.dashboard.DashboardWebSocketHandler;
import org.mockserver.grpc.GrpcJsonMessageConverter;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.grpc.GrpcServerReflectionHandler;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.model.Action;
import org.mockserver.model.GrpcBidiResponse;
import org.mockserver.model.HttpRequest;
import org.mockserver.netty.HttpRequestHandler;
import org.mockserver.netty.mcp.McpStreamableHttpHandler;
import org.mockserver.netty.unification.AltSvcHeaderHandler;
import org.mockserver.netty.unification.TraceContextHandler;
import org.mockserver.netty.websocketregistry.CallbackWebSocketServerHandler;
import org.mockserver.uuid.UUIDService;
import org.slf4j.event.Level;

import java.security.cert.Certificate;

import static org.mockserver.log.model.LogEntry.LogMessageType.RECEIVED_REQUEST;
import static org.mockserver.log.model.LogEntryMessages.RECEIVED_REQUEST_MESSAGE_FORMAT;

/**
 * Per-stream router that inspects the first {@link Http2HeadersFrame} to determine whether
 * this stream is a true bidirectional-streaming gRPC method. NOT {@code @Sharable} --
 * per-stream stateful (consumes the first HEADERS frame, then replaces itself).
 * <p>
 * <strong>Routing logic (Phase 3b):</strong>
 * <ul>
 *   <li>If the method is both {@code isClientStreaming()} and {@code isServerStreaming()}
 *       (true bidi) AND a matched expectation has a {@link GrpcBidiResponse} action:
 *       replaces itself with {@link GrpcBidiStreamHandler} configured from the matched
 *       expectation, sets autoRead off, and re-fires the HEADERS frame to the new handler.</li>
 *   <li>Otherwise (unary, server-streaming, client-streaming, non-gRPC, or bidi without
 *       a matching GrpcBidiResponse expectation): installs the existing Phase 0 re-aggregating
 *       chain via {@link GrpcMultiplexChildInitializer#installReAggregatingChain} and re-fires
 *       the HEADERS frame. Non-bidi streams are byte-for-byte unchanged.</li>
 * </ul>
 */
public class GrpcBidiRouterHandler extends ChannelInboundHandlerAdapter {

    private final Configuration configuration;
    private final GrpcProtoDescriptorStore descriptorStore;
    private final GrpcJsonMessageConverter converter;
    private final MockServerLogger mockServerLogger;
    private final boolean sslEnabled;
    private final Certificate[] clientCertificates;

    // Sharable handler references for the re-aggregating chain
    private final CallbackWebSocketServerHandler callbackWebSocketServerHandler;
    private final DashboardWebSocketHandler dashboardWebSocketHandler;
    private final McpStreamableHttpHandler mcpStreamableHttpHandler;
    private final TraceContextHandler traceContextHandler;
    private final AltSvcHeaderHandler altSvcHeaderHandler;
    private final GrpcToHttpResponseHandler grpcToHttpResponseHandler;
    private final GrpcToHttpRequestHandler grpcToHttpRequestHandler;
    private final HttpRequestHandler httpRequestHandler;

    // HttpState for expectation matching (nullable for testing)
    private final HttpState httpState;

    public GrpcBidiRouterHandler(
        Configuration configuration,
        GrpcProtoDescriptorStore descriptorStore,
        MockServerLogger mockServerLogger,
        boolean sslEnabled,
        Certificate[] clientCertificates,
        CallbackWebSocketServerHandler callbackWebSocketServerHandler,
        DashboardWebSocketHandler dashboardWebSocketHandler,
        McpStreamableHttpHandler mcpStreamableHttpHandler,
        TraceContextHandler traceContextHandler,
        AltSvcHeaderHandler altSvcHeaderHandler,
        GrpcToHttpResponseHandler grpcToHttpResponseHandler,
        GrpcToHttpRequestHandler grpcToHttpRequestHandler,
        HttpRequestHandler httpRequestHandler
    ) {
        this(configuration, descriptorStore, mockServerLogger, sslEnabled, clientCertificates,
            callbackWebSocketServerHandler, dashboardWebSocketHandler, mcpStreamableHttpHandler,
            traceContextHandler, altSvcHeaderHandler, grpcToHttpResponseHandler, grpcToHttpRequestHandler,
            httpRequestHandler, null);
    }

    public GrpcBidiRouterHandler(
        Configuration configuration,
        GrpcProtoDescriptorStore descriptorStore,
        MockServerLogger mockServerLogger,
        boolean sslEnabled,
        Certificate[] clientCertificates,
        CallbackWebSocketServerHandler callbackWebSocketServerHandler,
        DashboardWebSocketHandler dashboardWebSocketHandler,
        McpStreamableHttpHandler mcpStreamableHttpHandler,
        TraceContextHandler traceContextHandler,
        AltSvcHeaderHandler altSvcHeaderHandler,
        GrpcToHttpResponseHandler grpcToHttpResponseHandler,
        GrpcToHttpRequestHandler grpcToHttpRequestHandler,
        HttpRequestHandler httpRequestHandler,
        HttpState httpState
    ) {
        this.configuration = configuration;
        this.descriptorStore = descriptorStore;
        this.converter = descriptorStore != null ? descriptorStore.getConverter() : null;
        this.mockServerLogger = mockServerLogger;
        this.sslEnabled = sslEnabled;
        this.clientCertificates = clientCertificates;
        this.callbackWebSocketServerHandler = callbackWebSocketServerHandler;
        this.dashboardWebSocketHandler = dashboardWebSocketHandler;
        this.mcpStreamableHttpHandler = mcpStreamableHttpHandler;
        this.traceContextHandler = traceContextHandler;
        this.altSvcHeaderHandler = altSvcHeaderHandler;
        this.grpcToHttpResponseHandler = grpcToHttpResponseHandler;
        this.grpcToHttpRequestHandler = grpcToHttpRequestHandler;
        this.httpRequestHandler = httpRequestHandler;
        this.httpState = httpState;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof Http2HeadersFrame)) {
            // Not a HEADERS frame -- should not happen on first read; pass through
            ctx.fireChannelRead(msg);
            return;
        }

        Http2HeadersFrame headersFrame = (Http2HeadersFrame) msg;
        CharSequence pathSeq = headersFrame.headers().path();
        String path = pathSeq != null ? pathSeq.toString() : "";

        // Built-in service: gRPC Server Reflection (bidi streaming).
        // Checked before method descriptor resolution because reflection service
        // methods are not in the user's descriptor store.
        if (descriptorStore != null && descriptorStore.hasServices()
            && GrpcServerReflectionHandler.isReflectionPath(path)) {
            ChannelPipeline pipeline = ctx.pipeline();
            GrpcServerReflectionHandler reflectionHandler = new GrpcServerReflectionHandler(descriptorStore);
            pipeline.replace(this, "grpcBidiReflection",
                new GrpcBidiReflectionHandler(reflectionHandler));
            ctx.fireChannelRead(msg);
            return;
        }

        // Resolve the gRPC method descriptor from the :path
        Descriptors.MethodDescriptor methodDescriptor = resolveMethod(path);

        if (methodDescriptor != null && methodDescriptor.isClientStreaming() && methodDescriptor.isServerStreaming()) {
            // True bidi method -- check for a matching GrpcBidiResponse expectation
            // Two-phase match: peek (side-effect-free) to check the action type, then
            // consume (with Times/scenario/responseInProgress) only on the committed path.
            BidiMatchResult matchResult = findAndConsumeMatchingBidiResponse(path);

            if (matchResult != null) {
                // Install rule-driven GrpcBidiStreamHandler
                ChannelPipeline pipeline = ctx.pipeline();

                // Build a completion callback that clears responseInProgress when the
                // stream finishes (or errors). This ensures a times-limited expectation
                // is not left stuck with responseInProgress=true after the bidi stream ends.
                Runnable completionCallback = () -> {
                    if (httpState != null && matchResult.expectation != null) {
                        httpState.postProcess(matchResult.expectation);
                    }
                };

                GrpcBidiStreamHandler bidiHandler = new GrpcBidiStreamHandler(
                    methodDescriptor, converter, matchResult.bidiResponse, completionCallback
                );

                pipeline.replace(this, "grpcBidiStream", bidiHandler);

                // Re-fire the HEADERS frame to the newly installed handler
                ctx.fireChannelRead(msg);
            } else {
                // Bidi method but no matching GrpcBidiResponse expectation --
                // fall back to re-aggregating chain (same as non-bidi)
                installReAggregatingChainAndRefire(ctx, msg);
            }
        } else {
            // Non-bidi: install the existing re-aggregating chain
            installReAggregatingChainAndRefire(ctx, msg);
        }
    }

    private void installReAggregatingChainAndRefire(ChannelHandlerContext ctx, Object msg) {
        ChannelPipeline pipeline = ctx.pipeline();

        GrpcMultiplexChildInitializer.installReAggregatingChain(
            pipeline,
            configuration,
            mockServerLogger,
            sslEnabled,
            clientCertificates,
            ctx.channel(),
            callbackWebSocketServerHandler,
            dashboardWebSocketHandler,
            mcpStreamableHttpHandler,
            traceContextHandler,
            altSvcHeaderHandler,
            grpcToHttpResponseHandler,
            grpcToHttpRequestHandler,
            httpRequestHandler
        );

        // Remove this router (it's been replaced by the re-aggregating chain)
        pipeline.remove(this);

        // Re-fire the HEADERS frame to the newly installed chain
        ctx.fireChannelRead(msg);
    }

    /**
     * Result of the two-phase bidi match: contains the consumed {@link Expectation} (for
     * post-processing / responseInProgress lifecycle) and the extracted {@link GrpcBidiResponse}.
     */
    static class BidiMatchResult {
        final Expectation expectation;
        final GrpcBidiResponse bidiResponse;

        BidiMatchResult(Expectation expectation, GrpcBidiResponse bidiResponse) {
            this.expectation = expectation;
            this.bidiResponse = bidiResponse;
        }
    }

    /**
     * Two-phase match for bidi routing:
     * <ol>
     *   <li><strong>Peek (side-effect-free):</strong> uses
     *       {@link HttpState#peekFirstMatchingExpectation} to inspect the action type without
     *       consuming Times, transitioning scenarios, or setting responseInProgress. This is
     *       essential for the FALLBACK case: a non-bidi expectation on the same path must be
     *       left untouched for {@code HttpActionHandler} to consume normally.</li>
     *   <li><strong>Consume (on bidi commit):</strong> if the peeked action IS a
     *       {@link GrpcBidiResponse}, calls {@link HttpState#firstMatchingExpectation} to
     *       consume the match exactly once — decrementing Times, transitioning scenario state,
     *       setting responseInProgress, and recording metrics. A RECEIVED_REQUEST log entry
     *       is also emitted so the bidi request is visible in the request log and verifiable.</li>
     * </ol>
     *
     * @return a {@link BidiMatchResult} containing the consumed expectation and the
     *         GrpcBidiResponse action, or {@code null} if no matching GrpcBidiResponse exists
     */
    private BidiMatchResult findAndConsumeMatchingBidiResponse(String path) {
        if (httpState == null) {
            return null;
        }

        // Synthesise the matching request
        HttpRequest request = synthesiseBidiRequest(path);

        // Phase 1: side-effect-free peek to check action type
        Expectation peeked = httpState.peekFirstMatchingExpectation(request);
        if (peeked == null || !(peeked.getAction() instanceof GrpcBidiResponse)) {
            return null;
        }

        // Phase 2: the peeked action IS GrpcBidiResponse — commit by consuming the match.
        // This decrements Times, transitions scenarios, sets responseInProgress=true, and
        // records metrics. The responseInProgress flag will be cleared by the completion
        // callback passed to GrpcBidiStreamHandler when the stream finishes.
        Expectation consumed = httpState.firstMatchingExpectation(request);
        if (consumed == null || !(consumed.getAction() instanceof GrpcBidiResponse)) {
            // Race: between the side-effect-free peek and the consuming
            // firstMatchingExpectation call, another thread may have exhausted
            // the expectation (consumed == null) or a different expectation with
            // a non-bidi action may have been inserted ahead of it (action is not
            // GrpcBidiResponse). In either case, fall back gracefully to the
            // re-aggregating chain. Note: if consumed is non-null here, its Times
            // HAVE been decremented — this is acceptable because the re-aggregating
            // chain will re-match and handle the request via HttpActionHandler.
            return null;
        }

        // Log the bidi request as RECEIVED_REQUEST so it appears in the request log
        // and is verifiable via the verification API (mirrors HttpActionHandler.processAction).
        if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(Level.INFO)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(RECEIVED_REQUEST)
                    .setLogLevel(Level.INFO)
                    .setCorrelationId(request.getLogCorrelationId())
                    .setHttpRequest(request)
                    .setMessageFormat(RECEIVED_REQUEST_MESSAGE_FORMAT)
                    .setArguments(request)
            );
        }

        return new BidiMatchResult(consumed, (GrpcBidiResponse) consumed.getAction());
    }

    /**
     * Synthesise a MockServer {@link HttpRequest} representing the bidi gRPC request
     * (POST /service/method with gRPC headers, empty body). Used for both peek and
     * consume matching.
     */
    private HttpRequest synthesiseBidiRequest(String path) {
        HttpRequest request = HttpRequest.request()
            .withMethod("POST")
            .withPath(path)
            .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        request.withLogCorrelationId(UUIDService.getUUID());

        String[] parts = GrpcToHttpRequestHandler.parseGrpcPath(path);
        if (parts[0] != null && !parts[0].isEmpty()) {
            request.withHeader("x-grpc-service", parts[0]);
        }
        if (parts[1] != null && !parts[1].isEmpty()) {
            request.withHeader("x-grpc-method", parts[1]);
        }
        return request;
    }

    private Descriptors.MethodDescriptor resolveMethod(String path) {
        if (descriptorStore == null || !descriptorStore.hasServices()) {
            return null;
        }
        String[] parts = GrpcToHttpRequestHandler.parseGrpcPath(path);
        String serviceName = parts[0];
        String methodName = parts[1];
        if (serviceName.isEmpty() || methodName.isEmpty()) {
            return null;
        }
        return descriptorStore.getMethod(serviceName, methodName);
    }
}
