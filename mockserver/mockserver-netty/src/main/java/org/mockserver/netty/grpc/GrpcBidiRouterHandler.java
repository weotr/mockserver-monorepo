package org.mockserver.netty.grpc;

import com.google.protobuf.Descriptors;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import org.mockserver.codec.MockServerHttpServerCodec;
import org.mockserver.configuration.Configuration;
import org.mockserver.dashboard.DashboardWebSocketHandler;
import org.mockserver.grpc.GrpcJsonMessageConverter;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.netty.HttpRequestHandler;
import org.mockserver.netty.mcp.McpStreamableHttpHandler;
import org.mockserver.netty.unification.TraceContextHandler;
import org.mockserver.netty.websocketregistry.CallbackWebSocketServerHandler;

import java.security.cert.Certificate;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * Per-stream router that inspects the first {@link Http2HeadersFrame} to determine whether
 * this stream is a true bidirectional-streaming gRPC method. NOT {@code @Sharable} --
 * per-stream stateful (consumes the first HEADERS frame, then replaces itself).
 * <p>
 * <strong>Routing logic (Phase 3a):</strong>
 * <ul>
 *   <li>If the method is both {@code isClientStreaming()} and {@code isServerStreaming()}
 *       (true bidi): replaces itself with {@link GrpcBidiStreamHandler}, sets autoRead off,
 *       and re-fires the HEADERS frame to the new handler.</li>
 *   <li>Otherwise (unary, server-streaming, client-streaming, or non-gRPC): installs the
 *       existing Phase 0 re-aggregating chain via
 *       {@link GrpcMultiplexChildInitializer#installReAggregatingChain} and re-fires
 *       the HEADERS frame. Non-bidi streams are byte-for-byte unchanged.</li>
 * </ul>
 * <p>
 * <strong>3b will add:</strong> the "AND matched GrpcBidiResponse action" guard so that
 * bidi methods without a matching expectation fall through to re-aggregation.
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
    private final GrpcToHttpResponseHandler grpcToHttpResponseHandler;
    private final GrpcToHttpRequestHandler grpcToHttpRequestHandler;
    private final HttpRequestHandler httpRequestHandler;

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
        GrpcToHttpResponseHandler grpcToHttpResponseHandler,
        GrpcToHttpRequestHandler grpcToHttpRequestHandler,
        HttpRequestHandler httpRequestHandler
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
        this.grpcToHttpResponseHandler = grpcToHttpResponseHandler;
        this.grpcToHttpRequestHandler = grpcToHttpRequestHandler;
        this.httpRequestHandler = httpRequestHandler;
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

        // Resolve the gRPC method descriptor from the :path
        Descriptors.MethodDescriptor methodDescriptor = resolveMethod(path);

        if (methodDescriptor != null && methodDescriptor.isClientStreaming() && methodDescriptor.isServerStreaming()) {
            // True bidi -- install GrpcBidiStreamHandler
            ChannelPipeline pipeline = ctx.pipeline();

            // 3a default: echo responder (returns [inboundJson])
            Function<String, List<String>> echoResponder = json -> Collections.singletonList(json);

            GrpcBidiStreamHandler bidiHandler = new GrpcBidiStreamHandler(
                methodDescriptor, converter, echoResponder
            );

            pipeline.replace(this, "grpcBidiStream", bidiHandler);

            // Re-fire the HEADERS frame to the newly installed handler
            ctx.fireChannelRead(msg);
        } else {
            // Non-bidi: install the existing re-aggregating chain
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
                grpcToHttpResponseHandler,
                grpcToHttpRequestHandler,
                httpRequestHandler
            );

            // Remove this router (it's been replaced by the re-aggregating chain)
            pipeline.remove(this);

            // Re-fire the HEADERS frame to the newly installed chain
            ctx.fireChannelRead(msg);
        }
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
