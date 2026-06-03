package org.mockserver.netty.grpc;

import com.google.protobuf.Descriptors;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import org.mockserver.grpc.GrpcException;
import org.mockserver.grpc.GrpcFrameCodec;
import org.mockserver.grpc.GrpcJsonMessageConverter;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.grpc.IncrementalGrpcFrameDecoder;

import java.util.List;
import java.util.function.Function;

/**
 * Per-stream handler for true bidirectional gRPC streaming. NOT {@code @Sharable} --
 * holds per-stream state (the incremental frame decoder, finished guard).
 * <p>
 * <strong>Phase 3a behaviour:</strong>
 * <ul>
 *   <li>On {@link Http2HeadersFrame}: writes the initial response HEADERS
 *       ({@code :status=200}, {@code content-type=application/grpc}, {@code endStream=false}).</li>
 *   <li>On {@link Http2DataFrame}: feeds content bytes to {@link IncrementalGrpcFrameDecoder};
 *       for each complete inbound message, converts to JSON via the converter, applies the
 *       responder function, converts each response JSON to protobuf, gRPC-frames it, and
 *       writes it IMMEDIATELY as a DATA frame (interleaving proof). If the frame has
 *       {@code endStream=true}, calls {@link #finish(ChannelHandlerContext)}.</li>
 *   <li>{@code finish()}: writes trailing HEADERS with {@code grpc-status=0} and
 *       {@code endStream=true}. Guarded to run at most once.</li>
 * </ul>
 * <p>
 * Flow control: the channel's autoRead is set to {@code false} when this handler is
 * added; after processing each inbound frame, {@code ctx.read()} is called to request
 * the next frame. If the decoder's buffer cap is exceeded, a RESOURCE_EXHAUSTED trailing
 * status is written and the stream is finished.
 * <p>
 * Error handling: exceptions during channelRead are caught and result in an INTERNAL
 * grpc-status trailer, never an uncaught exception propagating up the pipeline.
 */
public class GrpcBidiStreamHandler extends ChannelInboundHandlerAdapter {

    private final Descriptors.MethodDescriptor methodDescriptor;
    private final GrpcJsonMessageConverter converter;
    private final Function<String, List<String>> responder;
    private final IncrementalGrpcFrameDecoder decoder;
    private volatile boolean finished;

    /**
     * @param methodDescriptor the resolved gRPC method descriptor
     * @param converter        JSON/protobuf converter for the method's message types
     * @param responder        maps an inbound message JSON string to a list of response
     *                         JSON strings; for 3a the default is echo: returns {@code [inboundJson]}
     */
    public GrpcBidiStreamHandler(
        Descriptors.MethodDescriptor methodDescriptor,
        GrpcJsonMessageConverter converter,
        Function<String, List<String>> responder
    ) {
        this(methodDescriptor, converter, responder, new IncrementalGrpcFrameDecoder());
    }

    /**
     * Visible-for-testing constructor that accepts a custom decoder (e.g. with a small cap).
     */
    GrpcBidiStreamHandler(
        Descriptors.MethodDescriptor methodDescriptor,
        GrpcJsonMessageConverter converter,
        Function<String, List<String>> responder,
        IncrementalGrpcFrameDecoder decoder
    ) {
        this.methodDescriptor = methodDescriptor;
        this.converter = converter;
        this.responder = responder;
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
        } catch (GrpcException e) {
            if (e.getMessage() != null && e.getMessage().contains("exceeded maximum")) {
                writeTrailer(ctx, GrpcStatusMapper.GrpcStatusCode.RESOURCE_EXHAUSTED, e.getMessage());
            } else {
                writeTrailer(ctx, GrpcStatusMapper.GrpcStatusCode.INTERNAL, e.getMessage());
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

            for (byte[] messageBytes : completedMessages) {
                String inboundJson = converter.toJson(messageBytes, methodDescriptor.getInputType());
                List<String> responseJsons = responder.apply(inboundJson);
                if (responseJsons != null) {
                    for (String responseJson : responseJsons) {
                        byte[] responseProto = converter.toProtobuf(responseJson, methodDescriptor.getOutputType());
                        byte[] framedResponse = GrpcFrameCodec.encode(responseProto);
                        ctx.writeAndFlush(new DefaultHttp2DataFrame(
                            Unpooled.wrappedBuffer(framedResponse), false));
                    }
                }
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

    private void writeTrailer(ChannelHandlerContext ctx, GrpcStatusMapper.GrpcStatusCode statusCode, String message) {
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
