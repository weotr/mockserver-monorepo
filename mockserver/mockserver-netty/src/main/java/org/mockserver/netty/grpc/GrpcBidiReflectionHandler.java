package org.mockserver.netty.grpc;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import org.mockserver.grpc.GrpcServerReflectionHandler;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.grpc.IncrementalGrpcFrameDecoder;

import java.util.List;

/**
 * Per-stream handler for serving gRPC Server Reflection interactively over the
 * bidirectional-streaming multiplex pipeline. NOT {@code @Sharable} -- holds per-stream
 * state (the incremental frame decoder, finished guard).
 * <p>
 * <strong>Behaviour:</strong>
 * <ul>
 *   <li>On {@link Http2HeadersFrame}: writes initial response HEADERS ({@code :status=200},
 *       {@code content-type=application/grpc}, {@code endStream=false}). If the HEADERS
 *       frame has {@code endStream=true}, immediately finishes with grpc-status=0.</li>
 *   <li>On {@link Http2DataFrame}: feeds content bytes to {@link IncrementalGrpcFrameDecoder};
 *       for each complete inbound gRPC message, delegates to
 *       {@link GrpcServerReflectionHandler#handleReflectionRequest(byte[])} and writes the
 *       gRPC-framed response as a DATA frame. If the frame has {@code endStream=true},
 *       calls {@link #finish(ChannelHandlerContext)}.</li>
 *   <li>{@code finish()}: writes trailing HEADERS with grpc-status=0 and
 *       {@code endStream=true}. Guarded to run at most once.</li>
 * </ul>
 * <p>
 * This handler reuses the existing {@link GrpcServerReflectionHandler} for per-message
 * reflection logic (decoding the request, looking up services/files in the descriptor
 * store, encoding the response). The only difference from the buffered-unary path is
 * that multiple request/response pairs can be interleaved over a single HTTP/2 stream.
 * <p>
 * Flow control: the channel's autoRead is set to {@code false} when this handler is
 * added; after processing each inbound frame, {@code ctx.read()} is called to request
 * the next frame.
 */
public class GrpcBidiReflectionHandler extends ChannelInboundHandlerAdapter {

    private final GrpcServerReflectionHandler reflectionHandler;
    private final IncrementalGrpcFrameDecoder decoder;
    private volatile boolean finished;

    /**
     * Creates a new per-stream bidi reflection handler.
     *
     * @param reflectionHandler the reflection handler that performs per-message request
     *                          decoding and response encoding (shared, stateless)
     */
    public GrpcBidiReflectionHandler(GrpcServerReflectionHandler reflectionHandler) {
        this(reflectionHandler, new IncrementalGrpcFrameDecoder());
    }

    /**
     * Visible-for-testing constructor that accepts a custom decoder.
     */
    GrpcBidiReflectionHandler(GrpcServerReflectionHandler reflectionHandler,
                              IncrementalGrpcFrameDecoder decoder) {
        this.reflectionHandler = reflectionHandler;
        this.decoder = decoder;
        this.finished = false;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        ctx.channel().config().setAutoRead(false);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        try {
            if (msg instanceof Http2HeadersFrame) {
                handleHeaders(ctx, (Http2HeadersFrame) msg);
            } else if (msg instanceof Http2DataFrame) {
                handleData(ctx, (Http2DataFrame) msg);
            } else {
                ctx.fireChannelRead(msg);
            }
        } catch (Exception e) {
            writeTrailer(ctx, GrpcStatusMapper.GrpcStatusCode.INTERNAL,
                e.getMessage() != null ? e.getMessage() : "internal error");
        }
    }

    private void handleHeaders(ChannelHandlerContext ctx, Http2HeadersFrame headersFrame) {
        // Write initial response headers
        DefaultHttp2Headers responseHeaders = new DefaultHttp2Headers();
        responseHeaders.status("200");
        responseHeaders.set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        ctx.writeAndFlush(new DefaultHttp2HeadersFrame(responseHeaders, false));

        if (headersFrame.isEndStream()) {
            finish(ctx);
        } else {
            ctx.read();
        }
    }

    private void handleData(ChannelHandlerContext ctx, Http2DataFrame dataFrame) {
        try {
            byte[] bytes = new byte[dataFrame.content().readableBytes()];
            dataFrame.content().readBytes(bytes);

            List<byte[]> completedMessages = decoder.feed(bytes);

            // For each complete inbound gRPC message, invoke the reflection handler
            // and write the response as a DATA frame
            for (byte[] messagePayload : completedMessages) {
                // Re-frame the payload with the 5-byte gRPC header because
                // handleReflectionRequest expects gRPC-framed input
                byte[] grpcFramedRequest = GrpcServerReflectionHandler.grpcFrame(messagePayload);
                byte[] grpcFramedResponse = reflectionHandler.handleReflectionRequest(grpcFramedRequest);
                ctx.writeAndFlush(new DefaultHttp2DataFrame(
                    Unpooled.wrappedBuffer(grpcFramedResponse), false));
            }

            if (dataFrame.isEndStream()) {
                finish(ctx);
            } else {
                ctx.read();
            }
        } finally {
            dataFrame.release();
        }
    }

    private void finish(ChannelHandlerContext ctx) {
        if (finished) {
            return;
        }
        finished = true;

        DefaultHttp2Headers trailers = new DefaultHttp2Headers();
        trailers.set(GrpcStatusMapper.GRPC_STATUS_HEADER, "0");
        ctx.writeAndFlush(new DefaultHttp2HeadersFrame(trailers, true));
    }

    private void writeTrailer(ChannelHandlerContext ctx, GrpcStatusMapper.GrpcStatusCode statusCode,
                              String message) {
        if (finished) {
            return;
        }
        finished = true;
        DefaultHttp2Headers trailers = new DefaultHttp2Headers();
        trailers.set(GrpcStatusMapper.GRPC_STATUS_HEADER, String.valueOf(statusCode.getCode()));
        if (message != null) {
            trailers.set(GrpcStatusMapper.GRPC_MESSAGE_HEADER, message);
        }
        ctx.writeAndFlush(new DefaultHttp2HeadersFrame(trailers, true));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        writeTrailer(ctx, GrpcStatusMapper.GrpcStatusCode.INTERNAL,
            cause.getMessage() != null ? cause.getMessage() : "internal error");
    }
}
