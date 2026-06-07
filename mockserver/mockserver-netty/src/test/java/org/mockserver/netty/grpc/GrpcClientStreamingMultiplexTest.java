package org.mockserver.netty.grpc;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import org.junit.Test;
import org.mockserver.grpc.GrpcFrameCodec;
import org.mockserver.grpc.GrpcJsonMessageConverter;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests that client-streaming gRPC (collect-then-respond) works correctly over the
 * multiplex pipeline introduced in Phase 0.
 * <p>
 * <strong>Phase 2 verification:</strong> the multiplex child pipeline uses
 * {@link Http2StreamFrameToHttpObjectCodec} + {@link HttpObjectAggregator} to re-aggregate
 * inbound HTTP/2 stream frames into a single {@link FullHttpRequest}. For client-streaming
 * RPCs, a client sends HEADERS followed by N DATA frames (each containing a gRPC
 * length-prefixed message) then END_STREAM. The re-aggregation concatenates all DATA frame
 * bytes into one body, which {@link GrpcToHttpRequestHandler} then decodes via
 * {@link GrpcFrameCodec#decode(byte[])} into N messages, producing a JSON array body
 * with the {@code x-grpc-client-streaming: true} header.
 * <p>
 * These tests prove:
 * <ol>
 *   <li>STEP 1: Inbound re-aggregation correctly concatenates multiple DATA frames' bytes
 *       using real HTTP/2 frames (Http2HeadersFrame + Http2DataFrame) so the codec's inbound
 *       decode path is genuinely exercised</li>
 *   <li>STEP 2: The concatenated body decodes to a JSON array with the client-streaming header</li>
 *   <li>Edge case: A single-message request still decodes as a non-array with no
 *       client-streaming header (unary vs client-streaming distinction is preserved)</li>
 * </ol>
 */
public class GrpcClientStreamingMultiplexTest {

    private static final int MAX_BODY_SIZE = 1048576; // 1 MiB

    /**
     * STEP 1: Proves that the multiplex child pipeline's inbound re-aggregation
     * (Http2StreamFrameToHttpObjectCodec + HttpObjectAggregator) concatenates multiple
     * DATA frame bodies into a single FullHttpRequest body.
     * <p>
     * Feeds real HTTP/2 frames (DefaultHttp2HeadersFrame + DefaultHttp2DataFrame) so the
     * codec's inbound decode path (Http2HeadersFrame to HttpRequest, Http2DataFrame to
     * HttpContent/LastHttpContent) is genuinely exercised. If the codec's inbound decode
     * regresses, this test will fail.
     */
    @Test
    public void shouldConcatenateMultipleDataFramesIntoSingleBody() {
        CaptureHandler capture = new CaptureHandler();
        EmbeddedChannel channel = new EmbeddedChannel(
            new Http2StreamFrameToHttpObjectCodec(true),
            new HttpObjectAggregator(MAX_BODY_SIZE),
            capture
        );

        // Build 3 distinct gRPC-framed messages
        byte[] msg1Proto = "Alice".getBytes(StandardCharsets.UTF_8);
        byte[] msg2Proto = "Bob".getBytes(StandardCharsets.UTF_8);
        byte[] msg3Proto = "Charlie".getBytes(StandardCharsets.UTF_8);

        byte[] frame1 = GrpcFrameCodec.encode(msg1Proto);
        byte[] frame2 = GrpcFrameCodec.encode(msg2Proto);
        byte[] frame3 = GrpcFrameCodec.encode(msg3Proto);

        // Expected: concatenation of all 3 gRPC frames
        byte[] expectedBody = new byte[frame1.length + frame2.length + frame3.length];
        System.arraycopy(frame1, 0, expectedBody, 0, frame1.length);
        System.arraycopy(frame2, 0, expectedBody, frame1.length, frame2.length);
        System.arraycopy(frame3, 0, expectedBody, frame1.length + frame2.length, frame3.length);

        // Feed real HTTP/2 frames: HEADERS (not end-stream) followed by 3 DATA frames
        // (last one has endStream=true). Http2StreamFrameToHttpObjectCodec decodes these
        // into HttpRequest + HttpContent + HttpContent + LastHttpContent, which the
        // HttpObjectAggregator combines into a single FullHttpRequest.
        DefaultHttp2Headers h2Headers = new DefaultHttp2Headers();
        h2Headers.method("POST");
        h2Headers.path("/com.example.grpc.GreetingService/CollectGreetings");
        h2Headers.set("content-type", "application/grpc");
        h2Headers.set("te", "trailers");

        channel.writeInbound(new DefaultHttp2HeadersFrame(h2Headers, false));
        channel.writeInbound(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(frame1), false));
        channel.writeInbound(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(frame2), false));
        channel.writeInbound(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(frame3), true));

        // Assertions on the re-aggregated FullHttpRequest
        assertThat("should have captured a re-aggregated request", capture.captured, is(notNullValue()));
        assertThat("method should be POST", capture.captured.method(), is(HttpMethod.POST));
        assertThat("path should be preserved",
            capture.captured.uri(), is("/com.example.grpc.GreetingService/CollectGreetings"));
        assertThat("content-type should be preserved",
            capture.captured.headers().get("content-type"), is("application/grpc"));

        byte[] actualBody = new byte[capture.captured.content().readableBytes()];
        capture.captured.content().readBytes(actualBody);
        assertThat("re-aggregated body must be the byte-for-byte concatenation of all 3 gRPC frames",
            actualBody, is(equalTo(expectedBody)));
        assertThat("body length must be sum of all frame lengths",
            actualBody.length, is(frame1.length + frame2.length + frame3.length));

        capture.release();
        channel.finishAndReleaseAll();
    }

    /**
     * STEP 2: Proves that the re-aggregated multi-message body is correctly decoded
     * by {@link GrpcToHttpRequestHandler} into a JSON array with the
     * {@code x-grpc-client-streaming: true} header.
     * <p>
     * Uses the {@code greeting.dsc} descriptor which defines
     * {@code CollectGreetings (stream HelloRequest) returns (HelloResponse)}.
     */
    @Test
    public void shouldDecodeMultiMessageBodyToJsonArrayWithClientStreamingHeader() {
        GrpcProtoDescriptorStore store = new GrpcProtoDescriptorStore(new MockServerLogger());
        store.loadDescriptorSetFromPath(Paths.get("../mockserver-core/src/test/resources/grpc/greeting.dsc"));

        GrpcJsonMessageConverter converter = store.getConverter();
        com.google.protobuf.Descriptors.MethodDescriptor collectMethod =
            store.getMethod("com.example.grpc.GreetingService", "CollectGreetings");
        assertThat("CollectGreetings method must exist in descriptor", collectMethod, is(notNullValue()));
        assertThat("CollectGreetings must be client-streaming", collectMethod.isClientStreaming(), is(true));

        // Build 3 properly encoded protobuf HelloRequest messages
        byte[] proto1 = converter.toProtobuf("{\"name\":\"Alice\"}", collectMethod.getInputType());
        byte[] proto2 = converter.toProtobuf("{\"name\":\"Bob\"}", collectMethod.getInputType());
        byte[] proto3 = converter.toProtobuf("{\"name\":\"Charlie\"}", collectMethod.getInputType());

        byte[] frame1 = GrpcFrameCodec.encode(proto1);
        byte[] frame2 = GrpcFrameCodec.encode(proto2);
        byte[] frame3 = GrpcFrameCodec.encode(proto3);

        // Concatenate all frames into a single body (as the re-aggregation produces)
        ByteBuffer bodyBuf = ByteBuffer.allocate(frame1.length + frame2.length + frame3.length);
        bodyBuf.put(frame1);
        bodyBuf.put(frame2);
        bodyBuf.put(frame3);
        byte[] body = bodyBuf.array();

        // Build the MockServer HttpRequest that GrpcToHttpRequestHandler receives
        HttpRequest grpcRequest = HttpRequest.request()
            .withMethod("POST")
            .withPath("/com.example.grpc.GreetingService/CollectGreetings")
            .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
            .withBody(body);

        // Wire up the handler and feed the request
        GrpcToHttpRequestHandler handler = new GrpcToHttpRequestHandler(new MockServerLogger(), store);
        RequestCaptureHandler requestCapture = new RequestCaptureHandler();
        EmbeddedChannel channel = new EmbeddedChannel(handler, requestCapture);

        channel.writeInbound(grpcRequest);

        // The handler should have converted and fired the request downstream
        assertThat("converted request must be captured", requestCapture.captured, is(notNullValue()));

        // Verify client-streaming marker header
        assertThat("must have x-grpc-client-streaming header set to true",
            requestCapture.captured.getFirstHeader("x-grpc-client-streaming"), is("true"));

        // Verify x-grpc-service and x-grpc-method headers
        assertThat("must have x-grpc-service header",
            requestCapture.captured.getFirstHeader("x-grpc-service"), is("com.example.grpc.GreetingService"));
        assertThat("must have x-grpc-method header",
            requestCapture.captured.getFirstHeader("x-grpc-method"), is("CollectGreetings"));

        // Verify body is a JSON array with 3 elements
        String jsonBody = requestCapture.captured.getBodyAsString();
        assertThat("body must start with [", jsonBody.trim(), is(org.hamcrest.Matchers.startsWith("[")));
        assertThat("body must end with ]", jsonBody.trim(), is(org.hamcrest.Matchers.endsWith("]")));
        assertThat("body must contain Alice", jsonBody, containsString("Alice"));
        assertThat("body must contain Bob", jsonBody, containsString("Bob"));
        assertThat("body must contain Charlie", jsonBody, containsString("Charlie"));

        channel.finishAndReleaseAll();
    }

    /**
     * Edge case: A single-message request (unary-style) must decode as a single JSON
     * object (NOT an array) and must NOT have the {@code x-grpc-client-streaming} header.
     * This confirms the unary vs client-streaming distinction is preserved through the
     * multiplex pipeline.
     */
    @Test
    public void shouldDecodeSingleMessageAsNonArrayWithNoClientStreamingHeader() {
        GrpcProtoDescriptorStore store = new GrpcProtoDescriptorStore(new MockServerLogger());
        store.loadDescriptorSetFromPath(Paths.get("../mockserver-core/src/test/resources/grpc/greeting.dsc"));

        GrpcJsonMessageConverter converter = store.getConverter();
        // Use the unary Greeting method, but CollectGreetings would also work for this test
        // — the distinction is based on message count, not method descriptor streaming flag
        com.google.protobuf.Descriptors.MethodDescriptor greetingMethod =
            store.getMethod("com.example.grpc.GreetingService", "Greeting");
        assertThat("Greeting method must exist", greetingMethod, is(notNullValue()));

        byte[] proto = converter.toProtobuf("{\"name\":\"SingleUser\"}", greetingMethod.getInputType());
        byte[] frame = GrpcFrameCodec.encode(proto);

        HttpRequest grpcRequest = HttpRequest.request()
            .withMethod("POST")
            .withPath("/com.example.grpc.GreetingService/Greeting")
            .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
            .withBody(frame);

        GrpcToHttpRequestHandler handler = new GrpcToHttpRequestHandler(new MockServerLogger(), store);
        RequestCaptureHandler requestCapture = new RequestCaptureHandler();
        EmbeddedChannel channel = new EmbeddedChannel(handler, requestCapture);

        channel.writeInbound(grpcRequest);

        assertThat("converted request must be captured", requestCapture.captured, is(notNullValue()));

        // Must NOT have client-streaming header
        assertThat("must NOT have x-grpc-client-streaming header for single message",
            requestCapture.captured.getFirstHeader("x-grpc-client-streaming"), is(""));

        // Body must be a single JSON object, not an array
        String jsonBody = requestCapture.captured.getBodyAsString();
        assertThat("body must not start with [", jsonBody.trim(), not(org.hamcrest.Matchers.startsWith("[")));
        assertThat("body must contain SingleUser", jsonBody, containsString("SingleUser"));

        // Verify service/method headers are still set
        assertThat("must have x-grpc-service header",
            requestCapture.captured.getFirstHeader("x-grpc-service"), is("com.example.grpc.GreetingService"));
        assertThat("must have x-grpc-method header",
            requestCapture.captured.getFirstHeader("x-grpc-method"), is("Greeting"));

        channel.finishAndReleaseAll();
    }

    /**
     * STEP 1+2 combined: End-to-end proof that multiple DATA frames fed through the
     * re-aggregation pipeline are correctly decoded to a JSON array with the
     * client-streaming header. This mirrors the full multiplex child inbound path.
     * <p>
     * Feeds real HTTP/2 frames (DefaultHttp2HeadersFrame + DefaultHttp2DataFrame) so the
     * codec's inbound decode path is genuinely exercised.
     */
    @Test
    public void shouldReAggregateAndDecodeClientStreamingEndToEnd() {
        GrpcProtoDescriptorStore store = new GrpcProtoDescriptorStore(new MockServerLogger());
        store.loadDescriptorSetFromPath(Paths.get("../mockserver-core/src/test/resources/grpc/greeting.dsc"));

        GrpcJsonMessageConverter converter = store.getConverter();
        com.google.protobuf.Descriptors.MethodDescriptor collectMethod =
            store.getMethod("com.example.grpc.GreetingService", "CollectGreetings");

        // Build 2 protobuf messages
        byte[] proto1 = converter.toProtobuf("{\"name\":\"Xena\"}", collectMethod.getInputType());
        byte[] proto2 = converter.toProtobuf("{\"name\":\"Yuri\"}", collectMethod.getInputType());
        byte[] frame1 = GrpcFrameCodec.encode(proto1);
        byte[] frame2 = GrpcFrameCodec.encode(proto2);

        // Step 1: Re-aggregation pipeline with real HTTP/2 frames
        CaptureHandler fullCapture = new CaptureHandler();
        EmbeddedChannel reaggChannel = new EmbeddedChannel(
            new Http2StreamFrameToHttpObjectCodec(true),
            new HttpObjectAggregator(MAX_BODY_SIZE),
            fullCapture
        );

        DefaultHttp2Headers h2Headers = new DefaultHttp2Headers();
        h2Headers.method("POST");
        h2Headers.path("/com.example.grpc.GreetingService/CollectGreetings");
        h2Headers.set("content-type", "application/grpc");
        h2Headers.set("te", "trailers");

        reaggChannel.writeInbound(new DefaultHttp2HeadersFrame(h2Headers, false));
        reaggChannel.writeInbound(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(frame1), false));
        reaggChannel.writeInbound(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(frame2), true));

        assertThat("re-aggregated request must be captured", fullCapture.captured, is(notNullValue()));

        // Verify concatenation
        byte[] reaggBody = new byte[fullCapture.captured.content().readableBytes()];
        fullCapture.captured.content().readBytes(reaggBody);

        byte[] expectedConcat = new byte[frame1.length + frame2.length];
        System.arraycopy(frame1, 0, expectedConcat, 0, frame1.length);
        System.arraycopy(frame2, 0, expectedConcat, frame1.length, frame2.length);
        assertThat("re-aggregated body must be concatenation of both frames",
            reaggBody, is(equalTo(expectedConcat)));

        // Step 2: Feed into GrpcToHttpRequestHandler
        HttpRequest mockReq = HttpRequest.request()
            .withMethod("POST")
            .withPath("/com.example.grpc.GreetingService/CollectGreetings")
            .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
            .withBody(reaggBody);

        GrpcToHttpRequestHandler handler = new GrpcToHttpRequestHandler(new MockServerLogger(), store);
        RequestCaptureHandler reqCapture = new RequestCaptureHandler();
        EmbeddedChannel handlerChannel = new EmbeddedChannel(handler, reqCapture);

        handlerChannel.writeInbound(mockReq);

        assertThat("decoded request must be captured", reqCapture.captured, is(notNullValue()));
        assertThat("must have x-grpc-client-streaming header",
            reqCapture.captured.getFirstHeader("x-grpc-client-streaming"), is("true"));

        String jsonBody = reqCapture.captured.getBodyAsString();
        assertThat("body must be a JSON array", jsonBody.trim(), is(org.hamcrest.Matchers.startsWith("[")));
        assertThat("body must contain Xena", jsonBody, containsString("Xena"));
        assertThat("body must contain Yuri", jsonBody, containsString("Yuri"));

        fullCapture.release();
        reaggChannel.finishAndReleaseAll();
        handlerChannel.finishAndReleaseAll();
    }

    /**
     * Edge case: Verifies that a single DATA frame containing exactly one gRPC message
     * re-aggregated through the pipeline produces a non-array JSON body with NO
     * client-streaming header, even when the method is declared as client-streaming in
     * the proto descriptor. (The distinction is based on actual message count, not the
     * method's streaming type.)
     */
    @Test
    public void shouldPreserveUnaryBehaviourForSingleFrameOnClientStreamingMethod() {
        GrpcProtoDescriptorStore store = new GrpcProtoDescriptorStore(new MockServerLogger());
        store.loadDescriptorSetFromPath(Paths.get("../mockserver-core/src/test/resources/grpc/greeting.dsc"));

        GrpcJsonMessageConverter converter = store.getConverter();
        com.google.protobuf.Descriptors.MethodDescriptor collectMethod =
            store.getMethod("com.example.grpc.GreetingService", "CollectGreetings");

        // Single message on a client-streaming method
        byte[] proto = converter.toProtobuf("{\"name\":\"Solo\"}", collectMethod.getInputType());
        byte[] frame = GrpcFrameCodec.encode(proto);

        HttpRequest grpcRequest = HttpRequest.request()
            .withMethod("POST")
            .withPath("/com.example.grpc.GreetingService/CollectGreetings")
            .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
            .withBody(frame);

        GrpcToHttpRequestHandler handler = new GrpcToHttpRequestHandler(new MockServerLogger(), store);
        RequestCaptureHandler requestCapture = new RequestCaptureHandler();
        EmbeddedChannel channel = new EmbeddedChannel(handler, requestCapture);

        channel.writeInbound(grpcRequest);

        assertThat("converted request must be captured", requestCapture.captured, is(notNullValue()));

        // Must NOT have client-streaming header for single message
        assertThat("must NOT have x-grpc-client-streaming header for single message",
            requestCapture.captured.getFirstHeader("x-grpc-client-streaming"), is(""));

        // Body must be a single JSON object
        String jsonBody = requestCapture.captured.getBodyAsString();
        assertThat("body must not be an array", jsonBody.trim(), not(org.hamcrest.Matchers.startsWith("[")));
        assertThat("body must contain Solo", jsonBody, containsString("Solo"));

        channel.finishAndReleaseAll();
    }

    // --- Capture helpers ---

    /**
     * Captures the re-aggregated FullHttpRequest from the inbound pipeline.
     * Not @Sharable — holds mutable state (the captured request reference).
     */
    private static class CaptureHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        FullHttpRequest captured;

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) {
            release();
            captured = msg.retainedDuplicate();
        }

        void release() {
            if (captured != null) {
                captured.release();
                captured = null;
            }
        }
    }

    /**
     * Captures the MockServer HttpRequest that GrpcToHttpRequestHandler fires downstream.
     * Not @Sharable — holds mutable state (the captured request reference).
     */
    private static class RequestCaptureHandler extends SimpleChannelInboundHandler<HttpRequest> {
        HttpRequest captured;

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, HttpRequest msg) {
            captured = msg;
        }
    }
}
