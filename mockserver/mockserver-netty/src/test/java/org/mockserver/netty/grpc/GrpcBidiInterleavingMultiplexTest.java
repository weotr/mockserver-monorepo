package org.mockserver.netty.grpc;

import com.google.protobuf.Descriptors;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2StreamFrame;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.dashboard.DashboardWebSocketHandler;
import org.mockserver.grpc.GrpcFrameCodec;
import org.mockserver.grpc.GrpcJsonMessageConverter;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.netty.HttpRequestHandler;
import org.mockserver.netty.unification.TraceContextHandler;
import org.mockserver.netty.websocketregistry.CallbackWebSocketServerHandler;
import org.mockserver.scheduler.Scheduler;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * Tests for Phase 3a gRPC bidirectional-streaming support: verifies that
 * {@link GrpcBidiStreamHandler} produces interleaved responses (DATA frame
 * for msg1 emitted BEFORE msg2 is fed) and that {@link GrpcBidiRouterHandler}
 * correctly routes bidi methods vs non-bidi methods.
 * <p>
 * Uses the {@code greeting.dsc} descriptor which defines:
 * <ul>
 *   <li>{@code Chat (stream HelloRequest) returns (stream HelloResponse)} -- true bidi</li>
 *   <li>{@code Greeting (HelloRequest) returns (HelloResponse)} -- unary</li>
 * </ul>
 * <p>
 * All tests use EmbeddedChannel (no grpc-java dependency). The interleaving test
 * is designed to FAIL if responses are buffered until END_STREAM.
 */
public class GrpcBidiInterleavingMultiplexTest {

    private static GrpcProtoDescriptorStore loadDescriptorStore() {
        GrpcProtoDescriptorStore store = new GrpcProtoDescriptorStore(new MockServerLogger());
        store.loadDescriptorSetFromPath(Paths.get("../mockserver-core/src/test/resources/grpc/greeting.dsc"));
        return store;
    }

    // ---- (a) Interleaving proof ----

    /**
     * Core interleaving test: feeds HEADERS, then DATA(msg1, endStream=false), and asserts
     * that the response HEADERS frame AND a DATA response for msg1 appear in outbound
     * BEFORE msg2 is fed. Then feeds DATA(msg2, endStream=true) and asserts a second DATA
     * response + trailing HEADERS with grpc-status=0.
     * <p>
     * This test would FAIL if the handler buffered all responses until END_STREAM because
     * the assertion after msg1 checks outbound before msg2 is fed.
     */
    @Test
    public void shouldInterleaveResponsesPerInboundMessage() {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        GrpcJsonMessageConverter converter = store.getConverter();
        Descriptors.MethodDescriptor chatMethod = store.getMethod("com.example.grpc.GreetingService", "Chat");
        assertThat("Chat method must exist", chatMethod, is(notNullValue()));
        assertThat("Chat must be client-streaming", chatMethod.isClientStreaming(), is(true));
        assertThat("Chat must be server-streaming", chatMethod.isServerStreaming(), is(true));

        // Build the handler with an echo responder that maps HelloRequest.name -> HelloResponse.greeting
        // (input and output types differ, so raw echo would produce empty fields)
        Function<String, List<String>> echoResponder = json -> {
            // Extract "name" value and map to "greeting" field of HelloResponse
            String transformed = json.replace("\"name\"", "\"greeting\"");
            return Collections.singletonList(transformed);
        };
        List<Object> outbound = new ArrayList<>();
        FrameCaptureHandler captureHandler = new FrameCaptureHandler(outbound);

        EmbeddedChannel channel = new EmbeddedChannel(
            captureHandler,
            new GrpcBidiStreamHandler(chatMethod, converter, echoResponder)
        );

        // --- STEP 1: Feed HEADERS ---
        DefaultHttp2Headers reqHeaders = new DefaultHttp2Headers();
        reqHeaders.method("POST");
        reqHeaders.path("/com.example.grpc.GreetingService/Chat");
        reqHeaders.set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        channel.writeInbound(new DefaultHttp2HeadersFrame(reqHeaders, false));

        // After HEADERS: expect initial response HEADERS frame in outbound
        assertThat("should have initial response HEADERS after inbound HEADERS",
            outbound.size(), is(greaterThanOrEqualTo(1)));
        assertThat("first outbound should be HEADERS frame",
            outbound.get(0), instanceOf(Http2HeadersFrame.class));
        Http2HeadersFrame initialHeaders = (Http2HeadersFrame) outbound.get(0);
        assertThat("initial HEADERS :status should be 200",
            initialHeaders.headers().status().toString(), is("200"));
        assertThat("initial HEADERS should have content-type=application/grpc",
            initialHeaders.headers().get("content-type").toString(), is(GrpcStatusMapper.GRPC_CONTENT_TYPE));
        assertThat("initial HEADERS should not have endStream",
            initialHeaders.isEndStream(), is(false));

        // --- STEP 2: Feed DATA(msg1, endStream=false) ---
        byte[] proto1 = converter.toProtobuf("{\"name\":\"Alice\"}", chatMethod.getInputType());
        byte[] frame1 = GrpcFrameCodec.encode(proto1);
        channel.writeInbound(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(frame1), false));

        // INTERLEAVING ASSERTION: before feeding msg2, there must be a DATA response for msg1
        assertThat("should have initial HEADERS + DATA response for msg1 BEFORE msg2 is fed",
            outbound.size(), is(2));
        assertThat("second outbound should be DATA frame",
            outbound.get(1), instanceOf(Http2DataFrame.class));
        Http2DataFrame dataResp1 = (Http2DataFrame) outbound.get(1);
        assertThat("DATA response 1 endStream should be false",
            dataResp1.isEndStream(), is(false));

        // Verify the response DATA contains a valid gRPC frame echoing Alice
        byte[] resp1Bytes = new byte[dataResp1.content().readableBytes()];
        dataResp1.content().readBytes(resp1Bytes);
        List<byte[]> decodedResp1 = GrpcFrameCodec.decode(resp1Bytes);
        assertThat("response should contain exactly one gRPC message", decodedResp1.size(), is(1));
        String resp1Json = converter.toJson(decodedResp1.get(0), chatMethod.getOutputType());
        assertThat("echo response should contain Alice", resp1Json, containsString("Alice"));

        // --- STEP 3: Feed DATA(msg2, endStream=true) ---
        byte[] proto2 = converter.toProtobuf("{\"name\":\"Bob\"}", chatMethod.getInputType());
        byte[] frame2 = GrpcFrameCodec.encode(proto2);
        channel.writeInbound(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(frame2), true));

        // After msg2 + END_STREAM: expect DATA response for msg2 + trailing HEADERS
        assertThat("should have 4 outbound frames total (HEADERS + DATA1 + DATA2 + trailing HEADERS)",
            outbound.size(), is(4));

        // Verify DATA response for msg2
        assertThat("third outbound should be DATA frame",
            outbound.get(2), instanceOf(Http2DataFrame.class));
        Http2DataFrame dataResp2 = (Http2DataFrame) outbound.get(2);
        byte[] resp2Bytes = new byte[dataResp2.content().readableBytes()];
        dataResp2.content().readBytes(resp2Bytes);
        List<byte[]> decodedResp2 = GrpcFrameCodec.decode(resp2Bytes);
        assertThat("response should contain exactly one gRPC message", decodedResp2.size(), is(1));
        String resp2Json = converter.toJson(decodedResp2.get(0), chatMethod.getOutputType());
        assertThat("echo response should contain Bob", resp2Json, containsString("Bob"));

        // Verify trailing HEADERS with grpc-status=0
        assertThat("fourth outbound should be HEADERS frame",
            outbound.get(3), instanceOf(Http2HeadersFrame.class));
        Http2HeadersFrame trailingHeaders = (Http2HeadersFrame) outbound.get(3);
        assertThat("trailing HEADERS must have endStream=true",
            trailingHeaders.isEndStream(), is(true));
        assertThat("trailing HEADERS should have grpc-status=0",
            trailingHeaders.headers().get(GrpcStatusMapper.GRPC_STATUS_HEADER).toString(), is("0"));

        // Release DATA frames
        for (Object frame : outbound) {
            if (frame instanceof Http2DataFrame) {
                ((Http2DataFrame) frame).release();
            }
        }
        channel.finishAndReleaseAll();
    }

    // ---- (b) Split-frame test ----

    /**
     * Verifies that when a single gRPC message's bytes are split across two DATA frames
     * (neither of which has endStream), no response DATA is emitted until the message is
     * fully received. This proves the IncrementalGrpcFrameDecoder correctly accumulates
     * across DATA frame boundaries.
     */
    @Test
    public void shouldAccumulateSplitFrameBeforeResponding() {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        GrpcJsonMessageConverter converter = store.getConverter();
        Descriptors.MethodDescriptor chatMethod = store.getMethod("com.example.grpc.GreetingService", "Chat");

        // Echo responder mapping HelloRequest.name -> HelloResponse.greeting
        Function<String, List<String>> echoResponder = json -> {
            String transformed = json.replace("\"name\"", "\"greeting\"");
            return Collections.singletonList(transformed);
        };
        List<Object> outbound = new ArrayList<>();
        FrameCaptureHandler captureHandler = new FrameCaptureHandler(outbound);

        EmbeddedChannel channel = new EmbeddedChannel(
            captureHandler,
            new GrpcBidiStreamHandler(chatMethod, converter, echoResponder)
        );

        // Feed HEADERS
        DefaultHttp2Headers reqHeaders = new DefaultHttp2Headers();
        reqHeaders.method("POST");
        reqHeaders.path("/com.example.grpc.GreetingService/Chat");
        reqHeaders.set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        channel.writeInbound(new DefaultHttp2HeadersFrame(reqHeaders, false));

        // Expect initial response HEADERS
        assertThat("should have initial HEADERS", outbound.size(), is(1));

        // Build a gRPC-framed message and split it in the middle
        byte[] proto = converter.toProtobuf("{\"name\":\"SplitTest\"}", chatMethod.getInputType());
        byte[] grpcFrame = GrpcFrameCodec.encode(proto);
        int splitPoint = grpcFrame.length / 2;
        byte[] part1 = Arrays.copyOfRange(grpcFrame, 0, splitPoint);
        byte[] part2 = Arrays.copyOfRange(grpcFrame, splitPoint, grpcFrame.length);

        // Feed first half -- should NOT produce any DATA response
        channel.writeInbound(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(part1), false));
        assertThat("no DATA response should appear for partial message (only initial HEADERS)",
            outbound.size(), is(1));

        // Feed second half with endStream=true -- should now produce DATA + trailing HEADERS
        channel.writeInbound(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(part2), true));
        assertThat("should have initial HEADERS + DATA + trailing HEADERS",
            outbound.size(), is(3));

        assertThat("second outbound should be DATA", outbound.get(1), instanceOf(Http2DataFrame.class));
        Http2DataFrame dataResp = (Http2DataFrame) outbound.get(1);
        byte[] respBytes = new byte[dataResp.content().readableBytes()];
        dataResp.content().readBytes(respBytes);
        List<byte[]> decoded = GrpcFrameCodec.decode(respBytes);
        String json = converter.toJson(decoded.get(0), chatMethod.getOutputType());
        assertThat("response should contain SplitTest", json, containsString("SplitTest"));

        assertThat("third outbound should be trailing HEADERS",
            outbound.get(2), instanceOf(Http2HeadersFrame.class));
        assertThat("trailing HEADERS endStream=true",
            ((Http2HeadersFrame) outbound.get(2)).isEndStream(), is(true));

        for (Object frame : outbound) {
            if (frame instanceof Http2DataFrame) {
                ((Http2DataFrame) frame).release();
            }
        }
        channel.finishAndReleaseAll();
    }

    // ---- (c) Router parity test ----

    /**
     * Drives a UNARY-method HEADERS through {@link GrpcBidiRouterHandler} and asserts that
     * the resulting pipeline contains the re-aggregating chain
     * ({@link Http2StreamFrameToHttpObjectCodec} + {@link HttpObjectAggregator}) and NOT
     * {@link GrpcBidiStreamHandler}. This proves that non-bidi streams are routed to the
     * identical Phase 0 chain.
     */
    @Test
    public void shouldRouteUnaryMethodToReAggregatingChain() {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        Configuration config = configuration();

        // Verify the unary method exists and is NOT bidi
        Descriptors.MethodDescriptor greetingMethod = store.getMethod("com.example.grpc.GreetingService", "Greeting");
        assertThat("Greeting method must exist", greetingMethod, is(notNullValue()));
        assertThat("Greeting must NOT be client-streaming", greetingMethod.isClientStreaming(), is(false));
        assertThat("Greeting must NOT be server-streaming", greetingMethod.isServerStreaming(), is(false));

        MockServerLogger logger = new MockServerLogger();
        HttpState httpState = new HttpState(config, logger, mock(Scheduler.class));

        // Build sharable handlers (minimal mocks/instances for pipeline assembly)
        CallbackWebSocketServerHandler wsHandler = new CallbackWebSocketServerHandler(httpState);
        DashboardWebSocketHandler dashHandler = new DashboardWebSocketHandler(httpState, false, false);
        TraceContextHandler traceHandler = new TraceContextHandler(config);
        HttpRequestHandler reqHandler = new HttpRequestHandler(
            config, mock(org.mockserver.lifecycle.LifeCycle.class), httpState,
            mock(org.mockserver.mock.action.http.HttpActionHandler.class)
        );
        GrpcToHttpResponseHandler grpcRespHandler = new GrpcToHttpResponseHandler(logger, store);
        GrpcToHttpRequestHandler grpcReqHandler = new GrpcToHttpRequestHandler(logger, store);

        // Create the router handler
        GrpcBidiRouterHandler router = new GrpcBidiRouterHandler(
            config, store, logger, false, null,
            wsHandler, dashHandler, null, traceHandler,
            grpcRespHandler, grpcReqHandler, reqHandler
        );

        // Embed it in a channel
        EmbeddedChannel channel = new EmbeddedChannel(router);

        // Feed a HEADERS frame for the unary Greeting method
        DefaultHttp2Headers h2Headers = new DefaultHttp2Headers();
        h2Headers.method("POST");
        h2Headers.path("/com.example.grpc.GreetingService/Greeting");
        h2Headers.set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        channel.writeInbound(new DefaultHttp2HeadersFrame(h2Headers, false));

        // After routing: pipeline should contain re-aggregating chain, NOT bidi handler
        assertThat("pipeline should contain Http2StreamFrameToHttpObjectCodec",
            channel.pipeline().get(Http2StreamFrameToHttpObjectCodec.class), is(notNullValue()));
        assertThat("pipeline should contain HttpObjectAggregator",
            channel.pipeline().get(HttpObjectAggregator.class), is(notNullValue()));
        assertThat("pipeline should NOT contain GrpcBidiStreamHandler",
            channel.pipeline().get(GrpcBidiStreamHandler.class), is(nullValue()));
        assertThat("pipeline should NOT contain GrpcBidiRouterHandler (removed after routing)",
            channel.pipeline().get(GrpcBidiRouterHandler.class), is(nullValue()));

        channel.finishAndReleaseAll();
    }

    /**
     * Drives a BIDI-method (Chat) HEADERS through {@link GrpcBidiRouterHandler} and asserts
     * that the resulting pipeline contains {@link GrpcBidiStreamHandler} and NOT the
     * re-aggregating chain.
     */
    @Test
    public void shouldRouteBidiMethodToStreamHandler() {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        Configuration config = configuration();

        Descriptors.MethodDescriptor chatMethod = store.getMethod("com.example.grpc.GreetingService", "Chat");
        assertThat("Chat method must exist", chatMethod, is(notNullValue()));
        assertThat("Chat must be client-streaming", chatMethod.isClientStreaming(), is(true));
        assertThat("Chat must be server-streaming", chatMethod.isServerStreaming(), is(true));

        MockServerLogger logger = new MockServerLogger();
        HttpState httpState = new HttpState(config, logger, mock(Scheduler.class));

        CallbackWebSocketServerHandler wsHandler = new CallbackWebSocketServerHandler(httpState);
        DashboardWebSocketHandler dashHandler = new DashboardWebSocketHandler(httpState, false, false);
        TraceContextHandler traceHandler = new TraceContextHandler(config);
        HttpRequestHandler reqHandler = new HttpRequestHandler(
            config, mock(org.mockserver.lifecycle.LifeCycle.class), httpState,
            mock(org.mockserver.mock.action.http.HttpActionHandler.class)
        );
        GrpcToHttpResponseHandler grpcRespHandler = new GrpcToHttpResponseHandler(logger, store);
        GrpcToHttpRequestHandler grpcReqHandler = new GrpcToHttpRequestHandler(logger, store);

        GrpcBidiRouterHandler router = new GrpcBidiRouterHandler(
            config, store, logger, false, null,
            wsHandler, dashHandler, null, traceHandler,
            grpcRespHandler, grpcReqHandler, reqHandler
        );

        List<Object> outbound = new ArrayList<>();
        FrameCaptureHandler captureHandler = new FrameCaptureHandler(outbound);

        EmbeddedChannel channel = new EmbeddedChannel(captureHandler, router);

        DefaultHttp2Headers h2Headers = new DefaultHttp2Headers();
        h2Headers.method("POST");
        h2Headers.path("/com.example.grpc.GreetingService/Chat");
        h2Headers.set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        channel.writeInbound(new DefaultHttp2HeadersFrame(h2Headers, false));

        // After routing: pipeline should contain GrpcBidiStreamHandler, NOT re-aggregating chain
        assertThat("pipeline should contain GrpcBidiStreamHandler",
            channel.pipeline().get(GrpcBidiStreamHandler.class), is(notNullValue()));
        assertThat("pipeline should NOT contain Http2StreamFrameToHttpObjectCodec",
            channel.pipeline().get(Http2StreamFrameToHttpObjectCodec.class), is(nullValue()));
        assertThat("pipeline should NOT contain HttpObjectAggregator",
            channel.pipeline().get(HttpObjectAggregator.class), is(nullValue()));
        assertThat("pipeline should NOT contain GrpcBidiRouterHandler (replaced)",
            channel.pipeline().get(GrpcBidiRouterHandler.class), is(nullValue()));

        // Should have emitted initial response HEADERS (bidi handler processes the re-fired frame)
        assertThat("should have emitted initial response HEADERS",
            outbound.size(), is(greaterThanOrEqualTo(1)));
        assertThat("first outbound should be HEADERS",
            outbound.get(0), instanceOf(Http2HeadersFrame.class));

        for (Object frame : outbound) {
            if (frame instanceof Http2DataFrame) {
                ((Http2DataFrame) frame).release();
            }
        }
        channel.finishAndReleaseAll();
    }

    /**
     * Verifies that a server-streaming method (not bidi) is routed to the re-aggregating chain.
     */
    @Test
    public void shouldRouteServerStreamingMethodToReAggregatingChain() {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        Configuration config = configuration();

        Descriptors.MethodDescriptor listMethod = store.getMethod("com.example.grpc.GreetingService", "ListGreetings");
        assertThat("ListGreetings method must exist", listMethod, is(notNullValue()));
        assertThat("ListGreetings must NOT be client-streaming", listMethod.isClientStreaming(), is(false));
        assertThat("ListGreetings must be server-streaming", listMethod.isServerStreaming(), is(true));

        MockServerLogger logger = new MockServerLogger();
        HttpState httpState = new HttpState(config, logger, mock(Scheduler.class));

        CallbackWebSocketServerHandler wsHandler = new CallbackWebSocketServerHandler(httpState);
        DashboardWebSocketHandler dashHandler = new DashboardWebSocketHandler(httpState, false, false);
        TraceContextHandler traceHandler = new TraceContextHandler(config);
        HttpRequestHandler reqHandler = new HttpRequestHandler(
            config, mock(org.mockserver.lifecycle.LifeCycle.class), httpState,
            mock(org.mockserver.mock.action.http.HttpActionHandler.class)
        );
        GrpcToHttpResponseHandler grpcRespHandler = new GrpcToHttpResponseHandler(logger, store);
        GrpcToHttpRequestHandler grpcReqHandler = new GrpcToHttpRequestHandler(logger, store);

        GrpcBidiRouterHandler router = new GrpcBidiRouterHandler(
            config, store, logger, false, null,
            wsHandler, dashHandler, null, traceHandler,
            grpcRespHandler, grpcReqHandler, reqHandler
        );

        EmbeddedChannel channel = new EmbeddedChannel(router);

        DefaultHttp2Headers h2Headers = new DefaultHttp2Headers();
        h2Headers.method("POST");
        h2Headers.path("/com.example.grpc.GreetingService/ListGreetings");
        h2Headers.set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        channel.writeInbound(new DefaultHttp2HeadersFrame(h2Headers, false));

        assertThat("pipeline should contain Http2StreamFrameToHttpObjectCodec",
            channel.pipeline().get(Http2StreamFrameToHttpObjectCodec.class), is(notNullValue()));
        assertThat("pipeline should NOT contain GrpcBidiStreamHandler",
            channel.pipeline().get(GrpcBidiStreamHandler.class), is(nullValue()));

        channel.finishAndReleaseAll();
    }

    // ---- Capture helper ----

    /**
     * Outbound handler that captures Http2StreamFrame objects written through the pipeline.
     * Placed BEFORE the bidi stream handler so it sees the handler's output.
     */
    private static class FrameCaptureHandler extends ChannelOutboundHandlerAdapter {
        private final List<Object> captured;

        FrameCaptureHandler(List<Object> captured) {
            this.captured = captured;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (msg instanceof Http2StreamFrame) {
                captured.add(msg);
            }
            promise.setSuccess();
        }
    }
}
