package org.mockserver.netty.grpc;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2StreamFrame;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import org.junit.Test;
import org.mockserver.grpc.GrpcFrameCodec;
import org.mockserver.grpc.GrpcStatusMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * Tests that server-streaming gRPC responses produced by
 * {@link org.mockserver.mock.action.http.GrpcStreamResponseActionHandler}
 * convert correctly to HTTP/2 stream frames when written through the
 * {@link Http2StreamFrameToHttpObjectCodec} on the multiplex child pipeline.
 * <p>
 * Verifies:
 * <ul>
 *   <li>(a) Transfer-Encoding: chunked (set by GrpcStreamResponseActionHandler) is stripped
 *       by the codec (illegal in HTTP/2)</li>
 *   <li>(b) Absence of content-length is handled (HTTP/2 does not require it)</li>
 *   <li>(c) LastHttpContent trailers become a trailing HEADERS frame with grpc-status
 *       and END_STREAM</li>
 *   <li>(d) Each DefaultHttpContent becomes its own DATA frame with the correct
 *       gRPC length-prefixed message bytes</li>
 * </ul>
 */
public class GrpcServerStreamingMultiplexTest {

    /**
     * Drives the exact sequence of Netty objects that GrpcStreamResponseActionHandler
     * emits through Http2StreamFrameToHttpObjectCodec and captures the outbound
     * Http2StreamFrame objects. Asserts:
     * <ol>
     *   <li>Initial HEADERS frame with :status=200, content-type=application/grpc,
     *       NO transfer-encoding, endStream=false</li>
     *   <li>N DATA frames, one per streamed message, each containing the exact bytes
     *       that GrpcFrameCodec.encode() produces, endStream=false</li>
     *   <li>Trailing HEADERS frame with grpc-status=0, endStream=true</li>
     * </ol>
     */
    @Test
    public void shouldConvertServerStreamingResponseToCorrectH2Frames() {
        // Capture outbound Http2StreamFrame objects
        List<Object> outboundFrames = new ArrayList<>();
        FrameCaptureHandler captureHandler = new FrameCaptureHandler(outboundFrames);

        // captureHandler is HEAD-ward of the codec. Outbound writes travel TAIL->HEAD, so the codec
        // converts the HttpResponse/HttpContent/LastHttpContent into Http2 stream frames first, and
        // captureHandler then intercepts the resulting Http2StreamFrame objects.
        EmbeddedChannel channel = new EmbeddedChannel(
            captureHandler,
            new Http2StreamFrameToHttpObjectCodec(true)
        );

        // === Simulate what GrpcStreamResponseActionHandler.handle() writes ===

        // 1. Initial response with Transfer-Encoding: chunked (as the action handler sets it)
        DefaultHttpResponse initialResponse = new DefaultHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.OK
        );
        initialResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, GrpcStatusMapper.GRPC_CONTENT_TYPE);
        initialResponse.headers().set(HttpHeaderNames.TRANSFER_ENCODING, "chunked");

        channel.writeOutbound(initialResponse);

        // 2. Two streamed messages — each encoded via GrpcFrameCodec
        byte[] msg1Protobuf = "message-one-payload".getBytes(StandardCharsets.UTF_8);
        byte[] msg1Frame = GrpcFrameCodec.encode(msg1Protobuf);

        byte[] msg2Protobuf = "message-two-payload".getBytes(StandardCharsets.UTF_8);
        byte[] msg2Frame = GrpcFrameCodec.encode(msg2Protobuf);

        channel.writeOutbound(new DefaultHttpContent(Unpooled.wrappedBuffer(msg1Frame)));
        channel.writeOutbound(new DefaultHttpContent(Unpooled.wrappedBuffer(msg2Frame)));

        // 3. Trailing headers with grpc-status=0 and grpc-message
        DefaultLastHttpContent trailers = new DefaultLastHttpContent();
        trailers.trailingHeaders().set(GrpcStatusMapper.GRPC_STATUS_HEADER, "0");
        trailers.trailingHeaders().set(GrpcStatusMapper.GRPC_MESSAGE_HEADER, "OK");

        channel.writeOutbound(trailers);

        // === Assertions ===

        // Should have: 1 initial HEADERS + 2 DATA + 1 trailing HEADERS = 4 frames
        assertThat("expected 4 outbound frames (HEADERS + 2xDATA + trailing HEADERS)",
            outboundFrames.size(), is(4));

        // --- Frame 0: Initial HEADERS ---
        assertThat("frame 0 should be Http2HeadersFrame",
            outboundFrames.get(0), instanceOf(Http2HeadersFrame.class));
        Http2HeadersFrame initialHeaders = (Http2HeadersFrame) outboundFrames.get(0);
        assertThat("initial HEADERS should not have endStream",
            initialHeaders.isEndStream(), is(false));
        assertThat("initial HEADERS should have :status=200",
            initialHeaders.headers().status().toString(), is("200"));
        assertThat("initial HEADERS should have content-type=application/grpc",
            initialHeaders.headers().get("content-type").toString(), is(GrpcStatusMapper.GRPC_CONTENT_TYPE));
        // (a) Transfer-Encoding: chunked must be stripped by the codec
        assertThat("Transfer-Encoding must NOT appear in HTTP/2 headers",
            initialHeaders.headers().get("transfer-encoding"), is(nullValue()));

        // --- Frame 1: DATA (message 1) ---
        assertThat("frame 1 should be Http2DataFrame",
            outboundFrames.get(1), instanceOf(Http2DataFrame.class));
        Http2DataFrame data1 = (Http2DataFrame) outboundFrames.get(1);
        assertThat("DATA frame 1 should not have endStream",
            data1.isEndStream(), is(false));
        byte[] data1Bytes = new byte[data1.content().readableBytes()];
        data1.content().readBytes(data1Bytes);
        assertThat("DATA frame 1 bytes must match GrpcFrameCodec.encode output (byte parity)",
            data1Bytes, is(equalTo(msg1Frame)));

        // --- Frame 2: DATA (message 2) ---
        assertThat("frame 2 should be Http2DataFrame",
            outboundFrames.get(2), instanceOf(Http2DataFrame.class));
        Http2DataFrame data2 = (Http2DataFrame) outboundFrames.get(2);
        assertThat("DATA frame 2 should not have endStream",
            data2.isEndStream(), is(false));
        byte[] data2Bytes = new byte[data2.content().readableBytes()];
        data2.content().readBytes(data2Bytes);
        assertThat("DATA frame 2 bytes must match GrpcFrameCodec.encode output (byte parity)",
            data2Bytes, is(equalTo(msg2Frame)));

        // --- Frame 3: Trailing HEADERS ---
        assertThat("frame 3 should be Http2HeadersFrame",
            outboundFrames.get(3), instanceOf(Http2HeadersFrame.class));
        Http2HeadersFrame trailingHeaders = (Http2HeadersFrame) outboundFrames.get(3);
        assertThat("trailing HEADERS must have endStream=true",
            trailingHeaders.isEndStream(), is(true));
        assertThat("trailing HEADERS should have grpc-status=0",
            trailingHeaders.headers().get(GrpcStatusMapper.GRPC_STATUS_HEADER).toString(), is("0"));
        assertThat("trailing HEADERS should have grpc-message=OK",
            trailingHeaders.headers().get(GrpcStatusMapper.GRPC_MESSAGE_HEADER).toString(), is("OK"));

        // Release
        for (Object frame : outboundFrames) {
            if (frame instanceof Http2DataFrame) {
                ((Http2DataFrame) frame).release();
            }
        }
        channel.finishAndReleaseAll();
    }

    /**
     * Verifies that a single-message server-streaming response (edge case: just 1 DATA frame)
     * also produces correct framing.
     */
    @Test
    public void shouldHandleSingleMessageServerStreaming() {
        List<Object> outboundFrames = new ArrayList<>();
        FrameCaptureHandler captureHandler = new FrameCaptureHandler(outboundFrames);

        EmbeddedChannel channel = new EmbeddedChannel(
            captureHandler,
            new Http2StreamFrameToHttpObjectCodec(true)
        );

        DefaultHttpResponse initialResponse = new DefaultHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.OK
        );
        initialResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, GrpcStatusMapper.GRPC_CONTENT_TYPE);
        initialResponse.headers().set(HttpHeaderNames.TRANSFER_ENCODING, "chunked");

        channel.writeOutbound(initialResponse);

        byte[] msgProtobuf = "single-message".getBytes(StandardCharsets.UTF_8);
        byte[] msgFrame = GrpcFrameCodec.encode(msgProtobuf);
        channel.writeOutbound(new DefaultHttpContent(Unpooled.wrappedBuffer(msgFrame)));

        DefaultLastHttpContent trailers = new DefaultLastHttpContent();
        trailers.trailingHeaders().set(GrpcStatusMapper.GRPC_STATUS_HEADER, "0");
        channel.writeOutbound(trailers);

        assertThat("expected 3 outbound frames (HEADERS + DATA + trailing HEADERS)",
            outboundFrames.size(), is(3));

        assertThat(outboundFrames.get(0), instanceOf(Http2HeadersFrame.class));
        assertThat(((Http2HeadersFrame) outboundFrames.get(0)).isEndStream(), is(false));

        assertThat(outboundFrames.get(1), instanceOf(Http2DataFrame.class));
        Http2DataFrame data = (Http2DataFrame) outboundFrames.get(1);
        byte[] dataBytes = new byte[data.content().readableBytes()];
        data.content().readBytes(dataBytes);
        assertThat(dataBytes, is(equalTo(msgFrame)));

        assertThat(outboundFrames.get(2), instanceOf(Http2HeadersFrame.class));
        Http2HeadersFrame trailing = (Http2HeadersFrame) outboundFrames.get(2);
        assertThat(trailing.isEndStream(), is(true));
        assertThat(trailing.headers().get(GrpcStatusMapper.GRPC_STATUS_HEADER).toString(), is("0"));

        for (Object frame : outboundFrames) {
            if (frame instanceof Http2DataFrame) {
                ((Http2DataFrame) frame).release();
            }
        }
        channel.finishAndReleaseAll();
    }

    /**
     * Verifies that an error-status trailing HEADERS (grpc-status != 0) is correctly
     * emitted with the error code and message.
     */
    @Test
    public void shouldEmitErrorStatusInTrailingHeaders() {
        List<Object> outboundFrames = new ArrayList<>();
        FrameCaptureHandler captureHandler = new FrameCaptureHandler(outboundFrames);

        EmbeddedChannel channel = new EmbeddedChannel(
            captureHandler,
            new Http2StreamFrameToHttpObjectCodec(true)
        );

        DefaultHttpResponse initialResponse = new DefaultHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.OK
        );
        initialResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, GrpcStatusMapper.GRPC_CONTENT_TYPE);
        initialResponse.headers().set(HttpHeaderNames.TRANSFER_ENCODING, "chunked");

        channel.writeOutbound(initialResponse);

        // No messages — immediate error trailer
        DefaultLastHttpContent trailers = new DefaultLastHttpContent();
        trailers.trailingHeaders().set(GrpcStatusMapper.GRPC_STATUS_HEADER,
            String.valueOf(GrpcStatusMapper.GrpcStatusCode.INTERNAL.getCode()));
        trailers.trailingHeaders().set(GrpcStatusMapper.GRPC_MESSAGE_HEADER, "something went wrong");
        channel.writeOutbound(trailers);

        assertThat("expected 2 outbound frames (HEADERS + trailing HEADERS)",
            outboundFrames.size(), is(2));

        Http2HeadersFrame trailingFrame = (Http2HeadersFrame) outboundFrames.get(1);
        assertThat(trailingFrame.isEndStream(), is(true));
        assertThat(trailingFrame.headers().get(GrpcStatusMapper.GRPC_STATUS_HEADER).toString(),
            is(String.valueOf(GrpcStatusMapper.GrpcStatusCode.INTERNAL.getCode())));
        assertThat(trailingFrame.headers().get(GrpcStatusMapper.GRPC_MESSAGE_HEADER).toString(),
            is("something went wrong"));

        channel.finishAndReleaseAll();
    }

    /**
     * Verifies gRPC frame byte parity: the exact bytes in each DATA frame match
     * what GrpcFrameCodec.encode() produces (5-byte header + payload).
     */
    @Test
    public void shouldPreserveGrpcFrameEncodingByteParity() {
        List<Object> outboundFrames = new ArrayList<>();
        FrameCaptureHandler captureHandler = new FrameCaptureHandler(outboundFrames);

        EmbeddedChannel channel = new EmbeddedChannel(
            captureHandler,
            new Http2StreamFrameToHttpObjectCodec(true)
        );

        DefaultHttpResponse initialResponse = new DefaultHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.OK
        );
        initialResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, GrpcStatusMapper.GRPC_CONTENT_TYPE);
        initialResponse.headers().set(HttpHeaderNames.TRANSFER_ENCODING, "chunked");
        channel.writeOutbound(initialResponse);

        // Create 3 messages with varying sizes
        String[] payloads = {"", "a", "hello world this is a longer gRPC message payload for testing"};
        byte[][] expectedFrames = new byte[payloads.length][];
        for (int i = 0; i < payloads.length; i++) {
            byte[] protobuf = payloads[i].getBytes(StandardCharsets.UTF_8);
            expectedFrames[i] = GrpcFrameCodec.encode(protobuf);
            channel.writeOutbound(new DefaultHttpContent(Unpooled.wrappedBuffer(expectedFrames[i].clone())));
        }

        DefaultLastHttpContent trailers = new DefaultLastHttpContent();
        trailers.trailingHeaders().set(GrpcStatusMapper.GRPC_STATUS_HEADER, "0");
        channel.writeOutbound(trailers);

        // 1 HEADERS + 3 DATA + 1 trailing HEADERS = 5
        assertThat(outboundFrames.size(), is(5));

        for (int i = 0; i < payloads.length; i++) {
            Http2DataFrame dataFrame = (Http2DataFrame) outboundFrames.get(i + 1);
            byte[] actual = new byte[dataFrame.content().readableBytes()];
            dataFrame.content().readBytes(actual);

            // Verify gRPC frame structure: compressed flag (1 byte) + length (4 bytes) + payload
            assertThat("frame " + i + " total length should be exactly 5 (gRPC header) + payload",
                actual.length, is(5 + payloads[i].getBytes(StandardCharsets.UTF_8).length));
            assertThat("frame " + i + " compressed flag should be 0 (uncompressed)",
                actual[0], is((byte) 0));
            int encodedLength = ((actual[1] & 0xFF) << 24) | ((actual[2] & 0xFF) << 16)
                | ((actual[3] & 0xFF) << 8) | (actual[4] & 0xFF);
            assertThat("frame " + i + " encoded length should match payload",
                encodedLength, is(payloads[i].getBytes(StandardCharsets.UTF_8).length));
            assertThat("frame " + i + " bytes must be byte-for-byte identical to GrpcFrameCodec.encode output",
                actual, is(equalTo(expectedFrames[i])));
        }

        for (Object frame : outboundFrames) {
            if (frame instanceof Http2DataFrame) {
                ((Http2DataFrame) frame).release();
            }
        }
        channel.finishAndReleaseAll();
    }

    /**
     * Verifies that custom gRPC metadata headers (e.g. x-custom-metadata) set on
     * the initial response are preserved in the initial HEADERS frame.
     */
    @Test
    public void shouldPreserveCustomMetadataInInitialHeaders() {
        List<Object> outboundFrames = new ArrayList<>();
        FrameCaptureHandler captureHandler = new FrameCaptureHandler(outboundFrames);

        EmbeddedChannel channel = new EmbeddedChannel(
            captureHandler,
            new Http2StreamFrameToHttpObjectCodec(true)
        );

        DefaultHttpResponse initialResponse = new DefaultHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.OK
        );
        initialResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, GrpcStatusMapper.GRPC_CONTENT_TYPE);
        initialResponse.headers().set(HttpHeaderNames.TRANSFER_ENCODING, "chunked");
        initialResponse.headers().set("x-custom-metadata", "test-value-123");
        initialResponse.headers().set("grpc-encoding", "identity");

        channel.writeOutbound(initialResponse);

        DefaultLastHttpContent trailers = new DefaultLastHttpContent();
        trailers.trailingHeaders().set(GrpcStatusMapper.GRPC_STATUS_HEADER, "0");
        channel.writeOutbound(trailers);

        assertThat("expected 2 outbound frames (HEADERS + trailing HEADERS)",
            outboundFrames.size(), is(2));
        assertThat("frame 0 should be Http2HeadersFrame",
            outboundFrames.get(0), instanceOf(Http2HeadersFrame.class));
        Http2HeadersFrame initial = (Http2HeadersFrame) outboundFrames.get(0);
        assertThat(initial.headers().get("x-custom-metadata").toString(), is("test-value-123"));
        assertThat(initial.headers().get("grpc-encoding").toString(), is("identity"));
        // Transfer-Encoding must still be stripped even when custom metadata is present
        assertThat("Transfer-Encoding must NOT appear in HTTP/2 headers",
            initial.headers().get("transfer-encoding"), is(nullValue()));

        channel.finishAndReleaseAll();
    }

    /**
     * Outbound handler that captures Http2StreamFrame objects written through the pipeline.
     * Placed BEFORE the Http2StreamFrameToHttpObjectCodec so it sees the codec's output.
     */
    private static class FrameCaptureHandler extends ChannelOutboundHandlerAdapter {
        private final List<Object> captured;

        FrameCaptureHandler(List<Object> captured) {
            this.captured = captured;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (msg instanceof Http2StreamFrame) {
                // This handler does not propagate the write downstream, so nothing else releases
                // the captured frame — the codec leaves a DATA frame's content at refcount 1, which
                // the per-test cleanup loop balances with a single release(). No extra retain needed.
                captured.add(msg);
            }
            // Don't propagate further — EmbeddedChannel doesn't have a real h2 connection
            promise.setSuccess();
        }
    }
}
