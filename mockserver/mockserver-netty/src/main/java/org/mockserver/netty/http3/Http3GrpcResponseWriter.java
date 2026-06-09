package org.mockserver.netty.http3;

import com.google.protobuf.Descriptors;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.quic.QuicStreamChannel;
import org.mockserver.configuration.Configuration;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.grpc.GrpcStreamMessageEncoder;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Delay;
import org.mockserver.model.GrpcStreamMessage;
import org.mockserver.model.GrpcStreamResponse;
import org.mockserver.model.Header;
import org.mockserver.model.Headers;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.NottableString;
import org.mockserver.responsewriter.GrpcStreamResponseWriter;
import org.mockserver.responsewriter.ResponseWriter;
import org.slf4j.event.Level;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A {@link ResponseWriter} that writes gRPC responses over HTTP/3 with correct
 * gRPC wire framing: initial HEADERS ({@code :status=200},
 * {@code content-type=application/grpc}), DATA (gRPC length-prefixed message),
 * and trailing HEADERS ({@code grpc-status}, {@code grpc-message}).
 * <p>
 * This follows the gRPC-over-HTTP/3 convention (same as HTTP/2): the
 * {@code grpc-status} is conveyed in a <strong>trailing HEADERS frame</strong>
 * at end-of-stream, which gRPC clients require. The initial HEADERS frame does
 * NOT contain grpc-status.
 * <p>
 * For error responses without a body, the "trailers-only" pattern is used:
 * a single HEADERS frame containing both {@code :status=200} and
 * {@code grpc-status} (no DATA frame).
 * <p>
 * The gRPC service and method names are captured from the original request
 * (where {@link GrpcHttp3Adapter} places them as {@code x-grpc-service} and
 * {@code x-grpc-method} headers) rather than from the response, because the
 * matching pipeline does not propagate these internal headers to the matched
 * response.
 */
public class Http3GrpcResponseWriter extends ResponseWriter implements GrpcStreamResponseWriter {

    private final ChannelHandlerContext ctx;
    private final GrpcProtoDescriptorStore descriptorStore;
    private final String grpcService;
    private final String grpcMethod;

    public Http3GrpcResponseWriter(
        Configuration configuration,
        MockServerLogger mockServerLogger,
        ChannelHandlerContext ctx,
        GrpcProtoDescriptorStore descriptorStore,
        String grpcService,
        String grpcMethod
    ) {
        super(configuration, mockServerLogger);
        this.ctx = ctx;
        this.descriptorStore = descriptorStore;
        this.grpcService = grpcService;
        this.grpcMethod = grpcMethod;
    }

    @Override
    public void sendResponse(HttpRequest request, HttpResponse response) {
        if (response == null) {
            response = HttpResponse.notFoundResponse();
        }

        if (grpcService != null && !grpcService.isEmpty()
            && grpcMethod != null && !grpcMethod.isEmpty()
            && descriptorStore != null && descriptorStore.hasServices()) {
            writeGrpcResponse(request, response, grpcService, grpcMethod);
        } else {
            // No descriptor-based conversion: the body stays as-is (binary or
            // whatever the expectation returned). Frame grpc-status correctly
            // in trailing HEADERS.
            writePassthroughGrpcResponse(response);
        }
    }

    /**
     * Write a gRPC response with proper trailing HEADERS framing.
     * The response body is converted from JSON to gRPC-framed protobuf.
     */
    private void writeGrpcResponse(
        HttpRequest request, HttpResponse response,
        String serviceName, String methodName
    ) {
        try {
            GrpcHttp3Adapter.GrpcResponseParts parts = GrpcHttp3Adapter.transformGrpcResponse(
                response, serviceName, methodName, descriptorStore
            );

            if (parts.hasBody()) {
                // Full response: initial HEADERS + DATA + trailing HEADERS
                DefaultHttp3HeadersFrame initialHeaders = GrpcHttp3Adapter.buildInitialHeadersFrame();
                DefaultHttp3DataFrame dataFrame = GrpcHttp3Adapter.buildDataFrame(parts.grpcFrameBytes());
                DefaultHttp3HeadersFrame trailers = GrpcHttp3Adapter.buildTrailingHeadersFrame(
                    parts.grpcStatus(), parts.grpcMessage()
                );

                ctx.write(initialHeaders);
                ctx.write(dataFrame);
                ctx.writeAndFlush(trailers)
                    .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
            } else {
                // Trailers-only: single HEADERS frame with grpc-status
                DefaultHttp3HeadersFrame trailersOnly = GrpcHttp3Adapter.buildTrailersOnlyFrame(
                    parts.grpcStatus(), parts.grpcMessage()
                );
                ctx.writeAndFlush(trailersOnly)
                    .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
            }
        } catch (Exception e) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setHttpRequest(request)
                    .setMessageFormat("failed to encode gRPC response over HTTP/3 for {}/{}:{}")
                    .setArguments(serviceName, methodName, e.getMessage())
                    .setThrowable(e)
            );
            writeErrorResponse(
                GrpcStatusMapper.GrpcStatusCode.INTERNAL,
                "failed to encode gRPC response: " + e.getMessage()
            );
        }
    }

    /**
     * Write a passthrough gRPC response -- the response already has grpc-status
     * as a header (from a raw expectation or chaos handler), so we just frame
     * it correctly with trailing HEADERS.
     */
    private void writePassthroughGrpcResponse(HttpResponse response) {
        String grpcStatus = response.getFirstHeader(GrpcStatusMapper.GRPC_STATUS_HEADER);
        String grpcMessage = response.getFirstHeader(GrpcStatusMapper.GRPC_MESSAGE_HEADER);

        if (grpcStatus == null || grpcStatus.isEmpty()) {
            grpcStatus = "0"; // OK
        }

        byte[] bodyBytes = response.getBodyAsRawBytes();
        if (bodyBytes != null && bodyBytes.length > 0) {
            DefaultHttp3HeadersFrame initialHeaders = GrpcHttp3Adapter.buildInitialHeadersFrame();
            DefaultHttp3DataFrame dataFrame = GrpcHttp3Adapter.buildDataFrame(bodyBytes);
            DefaultHttp3HeadersFrame trailers = GrpcHttp3Adapter.buildTrailingHeadersFrame(
                grpcStatus, grpcMessage
            );

            ctx.write(initialHeaders);
            ctx.write(dataFrame);
            ctx.writeAndFlush(trailers)
                .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
        } else {
            DefaultHttp3HeadersFrame trailersOnly = GrpcHttp3Adapter.buildTrailersOnlyFrame(
                grpcStatus, grpcMessage
            );
            ctx.writeAndFlush(trailersOnly)
                .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
        }
    }

    /**
     * Write a gRPC error as a trailers-only response over HTTP/3.
     */
    void writeErrorResponse(GrpcStatusMapper.GrpcStatusCode statusCode, String message) {
        DefaultHttp3HeadersFrame trailersOnly = GrpcHttp3Adapter.buildTrailersOnlyFrame(
            String.valueOf(statusCode.getCode()), message
        );
        ctx.writeAndFlush(trailersOnly)
            .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
    }

    // ------------------------------------------------------------------
    // Server-streaming gRPC over HTTP/3 (GrpcStreamResponseWriter)
    // ------------------------------------------------------------------

    /**
     * Write a server-streaming gRPC response over HTTP/3: an initial HEADERS frame
     * ({@code :status=200}, {@code content-type=application/grpc}, plus any configured
     * headers), one DATA frame per message honouring per-message {@link Delay}, then a
     * trailing HEADERS frame carrying {@code grpc-status} / {@code grpc-message}, followed
     * by a QUIC stream output shutdown (or connection close when configured).
     * <p>
     * Mirrors the HTTP/2 {@link org.mockserver.mock.action.http.GrpcStreamResponseActionHandler}
     * scheduling pattern, reusing {@link GrpcStreamMessageEncoder} for byte-identical framing.
     * Delays are scheduled on the QUIC stream's own event-loop executor so writes stay ordered.
     */
    @Override
    public void writeGrpcStreamResponse(GrpcStreamResponse grpcStreamResponse, HttpRequest request) {
        Descriptors.MethodDescriptor methodDescriptor = resolveMethodDescriptor();

        DefaultHttp3HeadersFrame initialHeaders = GrpcHttp3Adapter.buildInitialHeadersFrame();
        addConfiguredHeaders(initialHeaders, grpcStreamResponse.getHeaders());
        ctx.write(initialHeaders);
        ctx.flush();

        List<GrpcStreamMessage> messages = grpcStreamResponse.getMessages();
        if (messages != null && !messages.isEmpty()) {
            scheduleStreamMessages(messages, 0, grpcStreamResponse, methodDescriptor, request);
        } else {
            finishGrpcStream(grpcStreamResponse);
        }
    }

    private Descriptors.MethodDescriptor resolveMethodDescriptor() {
        if (grpcService != null && !grpcService.isEmpty()
            && grpcMethod != null && !grpcMethod.isEmpty()
            && descriptorStore != null && descriptorStore.hasServices()) {
            return descriptorStore.getMethod(grpcService, grpcMethod);
        }
        return null;
    }

    private void addConfiguredHeaders(DefaultHttp3HeadersFrame frame, Headers headers) {
        if (headers != null) {
            for (Header entry : headers.getEntries()) {
                for (NottableString value : entry.getValues()) {
                    frame.headers().add(entry.getName().getValue().toLowerCase(), value.getValue());
                }
            }
        }
    }

    private void scheduleStreamMessages(
        List<GrpcStreamMessage> messages, int index, GrpcStreamResponse action,
        Descriptors.MethodDescriptor methodDescriptor, HttpRequest request
    ) {
        if (index >= messages.size() || !ctx.channel().isActive()) {
            finishGrpcStream(action);
            return;
        }

        GrpcStreamMessage message = messages.get(index);
        Delay delay = message.getDelay();
        long delayMillis = (delay != null) ? delay.sampleValueMillis() : 0;

        Runnable writeAndContinue = () -> {
            if (!ctx.channel().isActive()) {
                return;
            }
            try {
                byte[] frameBytes = GrpcStreamMessageEncoder.encode(message, methodDescriptor, descriptorStore);
                DefaultHttp3DataFrame dataFrame = new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(frameBytes));
                ctx.writeAndFlush(dataFrame).addListener(future -> {
                    if (future.isSuccess()) {
                        scheduleStreamMessages(messages, index + 1, action, methodDescriptor, request);
                    } else {
                        finishGrpcStream(action);
                    }
                });
            } catch (Exception e) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setHttpRequest(request)
                        .setMessageFormat("exception sending gRPC stream message {} over HTTP/3 for request:{}")
                        .setArguments(index + 1, request)
                        .setThrowable(e)
                );
                finishGrpcStream(action);
            }
        };

        if (delayMillis > 0) {
            ctx.executor().schedule(writeAndContinue, delayMillis, TimeUnit.MILLISECONDS);
        } else {
            writeAndContinue.run();
        }
    }

    private void finishGrpcStream(GrpcStreamResponse action) {
        if (!ctx.channel().isActive()) {
            return;
        }
        GrpcStatusMapper.GrpcStatusCode statusCode = GrpcStatusMapper.GrpcStatusCode.OK;
        if (action.getStatusName() != null && !action.getStatusName().isEmpty()) {
            statusCode = GrpcStatusMapper.fromName(action.getStatusName());
        }
        String grpcMessage = (action.getStatusMessage() != null && !action.getStatusMessage().isEmpty())
            ? action.getStatusMessage() : null;

        DefaultHttp3HeadersFrame trailers = GrpcHttp3Adapter.buildTrailingHeadersFrame(
            String.valueOf(statusCode.getCode()), grpcMessage
        );
        ctx.writeAndFlush(trailers).addListener(future -> {
            if (Boolean.TRUE.equals(action.getCloseConnection())) {
                ctx.close();
            } else if (ctx.channel() instanceof QuicStreamChannel) {
                ((QuicStreamChannel) ctx.channel()).shutdownOutput();
            }
        });
    }
}
