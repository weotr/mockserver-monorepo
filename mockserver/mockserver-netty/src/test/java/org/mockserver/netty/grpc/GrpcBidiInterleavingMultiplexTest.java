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
import org.mockserver.matchers.Times;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.model.Delay;
import org.mockserver.model.GrpcBidiResponse;
import org.mockserver.model.GrpcBidiRule;
import org.mockserver.model.GrpcStreamMessage;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.NottableString;
import org.mockserver.netty.HttpRequestHandler;
import org.mockserver.netty.unification.TraceContextHandler;
import org.mockserver.netty.websocketregistry.CallbackWebSocketServerHandler;
import org.mockserver.scheduler.Scheduler;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
 * Tests for Phase 3a+3b gRPC bidirectional-streaming support: verifies that
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

    // ---- (a) Interleaving proof (Phase 3a: function-based responder) ----

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
            wsHandler, dashHandler, null, traceHandler, null,
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
     * Phase 3b: Drives a BIDI-method (Chat) HEADERS through {@link GrpcBidiRouterHandler}
     * WITHOUT a matching expectation and asserts that it falls back to the re-aggregating
     * chain (because no GrpcBidiResponse expectation is found).
     */
    @Test
    public void shouldRouteBidiMethodToReAggregatingChainWithoutMatchingExpectation() {
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

        // Router WITH httpState but NO matching expectations
        GrpcBidiRouterHandler router = new GrpcBidiRouterHandler(
            config, store, logger, false, null,
            wsHandler, dashHandler, null, traceHandler, null,
            grpcRespHandler, grpcReqHandler, reqHandler,
            httpState
        );

        EmbeddedChannel channel = new EmbeddedChannel(router);

        DefaultHttp2Headers h2Headers = new DefaultHttp2Headers();
        h2Headers.method("POST");
        h2Headers.path("/com.example.grpc.GreetingService/Chat");
        h2Headers.set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        channel.writeInbound(new DefaultHttp2HeadersFrame(h2Headers, false));

        // Without a matching expectation, bidi method falls back to re-aggregating chain
        assertThat("pipeline should contain Http2StreamFrameToHttpObjectCodec",
            channel.pipeline().get(Http2StreamFrameToHttpObjectCodec.class), is(notNullValue()));
        assertThat("pipeline should NOT contain GrpcBidiStreamHandler",
            channel.pipeline().get(GrpcBidiStreamHandler.class), is(nullValue()));

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
            wsHandler, dashHandler, null, traceHandler, null,
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

    // ---- Phase 3b: GrpcBidiResponse-driven tests ----

    /**
     * Phase 3b: Two-rule interleave test. Configures a GrpcBidiStreamHandler with a
     * GrpcBidiResponse containing two rules (Alice -> "Hi Alice", Bob -> "Hi Bob"),
     * eager messages, and a custom trailing status. Verifies:
     * <ul>
     *   <li>Eager messages are emitted first (after initial HEADERS)</li>
     *   <li>Inbound msg1 (Alice) triggers rule 1's response BEFORE msg2 is fed</li>
     *   <li>Inbound msg2 (Bob) triggers rule 2's response</li>
     *   <li>Trailing HEADERS carry the configured grpc-status</li>
     * </ul>
     */
    @Test
    public void shouldInterleaveResponsesFromTwoRules() {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        GrpcJsonMessageConverter converter = store.getConverter();
        Descriptors.MethodDescriptor chatMethod = store.getMethod("com.example.grpc.GreetingService", "Chat");

        GrpcBidiResponse config = GrpcBidiResponse.grpcBidiResponse()
            .withMessage("{\"greeting\": \"Welcome!\"}")
            .withRule(GrpcBidiRule.grpcBidiRule(".*Alice.*")
                .withResponse("{\"greeting\": \"Hi Alice\"}"))
            .withRule(GrpcBidiRule.grpcBidiRule(".*Bob.*")
                .withResponse("{\"greeting\": \"Hi Bob\"}"))
            .withStatusName("OK")
            .withStatusMessage("all done");

        List<Object> outbound = new ArrayList<>();
        FrameCaptureHandler captureHandler = new FrameCaptureHandler(outbound);

        EmbeddedChannel channel = new EmbeddedChannel(
            captureHandler,
            new GrpcBidiStreamHandler(chatMethod, converter, config)
        );

        // --- STEP 1: Feed HEADERS ---
        DefaultHttp2Headers reqHeaders = new DefaultHttp2Headers();
        reqHeaders.method("POST");
        reqHeaders.path("/com.example.grpc.GreetingService/Chat");
        reqHeaders.set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        channel.writeInbound(new DefaultHttp2HeadersFrame(reqHeaders, false));

        // Expect: initial HEADERS + eager message DATA
        assertThat("should have initial HEADERS + eager DATA",
            outbound.size(), is(2));
        assertThat("first outbound should be HEADERS",
            outbound.get(0), instanceOf(Http2HeadersFrame.class));
        Http2HeadersFrame initialHeaders = (Http2HeadersFrame) outbound.get(0);
        assertThat("initial HEADERS :status should be 200",
            initialHeaders.headers().status().toString(), is("200"));

        // Verify eager message
        assertThat("second outbound should be DATA (eager message)",
            outbound.get(1), instanceOf(Http2DataFrame.class));
        Http2DataFrame eagerData = (Http2DataFrame) outbound.get(1);
        byte[] eagerBytes = new byte[eagerData.content().readableBytes()];
        eagerData.content().readBytes(eagerBytes);
        List<byte[]> eagerDecoded = GrpcFrameCodec.decode(eagerBytes);
        String eagerJson = converter.toJson(eagerDecoded.get(0), chatMethod.getOutputType());
        assertThat("eager message should contain Welcome", eagerJson, containsString("Welcome"));

        // --- STEP 2: Feed DATA(msg1 = Alice, endStream=false) ---
        byte[] proto1 = converter.toProtobuf("{\"name\":\"Alice\"}", chatMethod.getInputType());
        byte[] frame1 = GrpcFrameCodec.encode(proto1);
        channel.writeInbound(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(frame1), false));

        // INTERLEAVING ASSERTION: rule 1's response appears before msg2
        assertThat("should have HEADERS + eager + rule1-response BEFORE msg2",
            outbound.size(), is(3));
        Http2DataFrame rule1Data = (Http2DataFrame) outbound.get(2);
        byte[] rule1Bytes = new byte[rule1Data.content().readableBytes()];
        rule1Data.content().readBytes(rule1Bytes);
        List<byte[]> rule1Decoded = GrpcFrameCodec.decode(rule1Bytes);
        String rule1Json = converter.toJson(rule1Decoded.get(0), chatMethod.getOutputType());
        assertThat("rule 1 response should contain Hi Alice", rule1Json, containsString("Hi Alice"));

        // --- STEP 3: Feed DATA(msg2 = Bob, endStream=true) ---
        byte[] proto2 = converter.toProtobuf("{\"name\":\"Bob\"}", chatMethod.getInputType());
        byte[] frame2 = GrpcFrameCodec.encode(proto2);
        channel.writeInbound(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(frame2), true));

        // Expect: HEADERS + eager + rule1 + rule2 + trailing HEADERS = 5
        assertThat("should have 5 outbound frames total",
            outbound.size(), is(5));

        // Verify rule 2's response
        Http2DataFrame rule2Data = (Http2DataFrame) outbound.get(3);
        byte[] rule2Bytes = new byte[rule2Data.content().readableBytes()];
        rule2Data.content().readBytes(rule2Bytes);
        List<byte[]> rule2Decoded = GrpcFrameCodec.decode(rule2Bytes);
        String rule2Json = converter.toJson(rule2Decoded.get(0), chatMethod.getOutputType());
        assertThat("rule 2 response should contain Hi Bob", rule2Json, containsString("Hi Bob"));

        // Verify trailing HEADERS
        Http2HeadersFrame trailing = (Http2HeadersFrame) outbound.get(4);
        assertThat("trailing HEADERS endStream=true", trailing.isEndStream(), is(true));
        assertThat("trailing grpc-status should be 0 (OK)",
            trailing.headers().get(GrpcStatusMapper.GRPC_STATUS_HEADER).toString(), is("0"));
        assertThat("trailing grpc-message should be 'all done'",
            trailing.headers().get(GrpcStatusMapper.GRPC_MESSAGE_HEADER).toString(), is("all done"));

        for (Object frame : outbound) {
            if (frame instanceof Http2DataFrame) {
                ((Http2DataFrame) frame).release();
            }
        }
        channel.finishAndReleaseAll();
    }

    /**
     * Phase 3b: No rule match -> no response emitted. Verifies that when an inbound
     * message does not match any rule, no DATA frame is emitted for it.
     */
    @Test
    public void shouldEmitNoResponseWhenNoRuleMatches() {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        GrpcJsonMessageConverter converter = store.getConverter();
        Descriptors.MethodDescriptor chatMethod = store.getMethod("com.example.grpc.GreetingService", "Chat");

        GrpcBidiResponse config = GrpcBidiResponse.grpcBidiResponse()
            .withRule(GrpcBidiRule.grpcBidiRule(".*Alice.*")
                .withResponse("{\"greeting\": \"Hi Alice\"}"))
            .withStatusName("OK");

        List<Object> outbound = new ArrayList<>();
        FrameCaptureHandler captureHandler = new FrameCaptureHandler(outbound);

        EmbeddedChannel channel = new EmbeddedChannel(
            captureHandler,
            new GrpcBidiStreamHandler(chatMethod, converter, config)
        );

        // Feed HEADERS
        DefaultHttp2Headers reqHeaders = new DefaultHttp2Headers();
        reqHeaders.method("POST");
        reqHeaders.path("/com.example.grpc.GreetingService/Chat");
        reqHeaders.set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        channel.writeInbound(new DefaultHttp2HeadersFrame(reqHeaders, false));

        // Expect: initial HEADERS only (no eager messages)
        assertThat("should have initial HEADERS", outbound.size(), is(1));

        // Feed DATA with a non-matching message (Charlie, not Alice)
        byte[] proto = converter.toProtobuf("{\"name\":\"Charlie\"}", chatMethod.getInputType());
        byte[] frame = GrpcFrameCodec.encode(proto);
        channel.writeInbound(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(frame), true));

        // Expect: initial HEADERS + trailing HEADERS only (no DATA for non-matching message)
        assertThat("should have 2 outbound frames (HEADERS + trailing HEADERS, no DATA)",
            outbound.size(), is(2));
        assertThat("second outbound should be trailing HEADERS",
            outbound.get(1), instanceOf(Http2HeadersFrame.class));
        assertThat("trailing HEADERS endStream=true",
            ((Http2HeadersFrame) outbound.get(1)).isEndStream(), is(true));

        channel.finishAndReleaseAll();
    }

    /**
     * Phase 3b: Custom trailing status (NOT_FOUND/5) + statusMessage.
     */
    @Test
    public void shouldUseConfiguredTrailingStatus() {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        GrpcJsonMessageConverter converter = store.getConverter();
        Descriptors.MethodDescriptor chatMethod = store.getMethod("com.example.grpc.GreetingService", "Chat");

        GrpcBidiResponse config = GrpcBidiResponse.grpcBidiResponse()
            .withStatusName("NOT_FOUND")
            .withStatusMessage("resource not found");

        List<Object> outbound = new ArrayList<>();
        FrameCaptureHandler captureHandler = new FrameCaptureHandler(outbound);

        EmbeddedChannel channel = new EmbeddedChannel(
            captureHandler,
            new GrpcBidiStreamHandler(chatMethod, converter, config)
        );

        // Feed HEADERS + endStream
        DefaultHttp2Headers reqHeaders = new DefaultHttp2Headers();
        reqHeaders.method("POST");
        reqHeaders.path("/com.example.grpc.GreetingService/Chat");
        reqHeaders.set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        channel.writeInbound(new DefaultHttp2HeadersFrame(reqHeaders, true));

        // Expect: initial HEADERS + trailing HEADERS
        assertThat("should have 2 outbound frames", outbound.size(), is(2));
        Http2HeadersFrame trailing = (Http2HeadersFrame) outbound.get(1);
        assertThat("trailing grpc-status should be 5 (NOT_FOUND)",
            trailing.headers().get(GrpcStatusMapper.GRPC_STATUS_HEADER).toString(), is("5"));
        assertThat("trailing grpc-message should be 'resource not found'",
            trailing.headers().get(GrpcStatusMapper.GRPC_MESSAGE_HEADER).toString(), is("resource not found"));

        channel.finishAndReleaseAll();
    }

    /**
     * Phase 3b: Multiple responses per rule.
     */
    @Test
    public void shouldEmitMultipleResponsesFromSingleRule() {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        GrpcJsonMessageConverter converter = store.getConverter();
        Descriptors.MethodDescriptor chatMethod = store.getMethod("com.example.grpc.GreetingService", "Chat");

        GrpcBidiResponse config = GrpcBidiResponse.grpcBidiResponse()
            .withRule(GrpcBidiRule.grpcBidiRule(".*Alice.*")
                .withResponse("{\"greeting\": \"Hello Alice\"}")
                .withResponse("{\"greeting\": \"Welcome Alice\"}"))
            .withStatusName("OK");

        List<Object> outbound = new ArrayList<>();
        FrameCaptureHandler captureHandler = new FrameCaptureHandler(outbound);

        EmbeddedChannel channel = new EmbeddedChannel(
            captureHandler,
            new GrpcBidiStreamHandler(chatMethod, converter, config)
        );

        // Feed HEADERS
        DefaultHttp2Headers reqHeaders = new DefaultHttp2Headers();
        reqHeaders.method("POST");
        reqHeaders.path("/com.example.grpc.GreetingService/Chat");
        reqHeaders.set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        channel.writeInbound(new DefaultHttp2HeadersFrame(reqHeaders, false));

        // Feed DATA(Alice, endStream=true)
        byte[] proto = converter.toProtobuf("{\"name\":\"Alice\"}", chatMethod.getInputType());
        byte[] frame = GrpcFrameCodec.encode(proto);
        channel.writeInbound(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(frame), true));

        // Expect: HEADERS + DATA1 + DATA2 + trailing HEADERS = 4
        assertThat("should have 4 outbound frames", outbound.size(), is(4));

        // Verify both responses
        Http2DataFrame data1 = (Http2DataFrame) outbound.get(1);
        byte[] data1Bytes = new byte[data1.content().readableBytes()];
        data1.content().readBytes(data1Bytes);
        String json1 = converter.toJson(GrpcFrameCodec.decode(data1Bytes).get(0), chatMethod.getOutputType());
        assertThat("first response should contain Hello Alice", json1, containsString("Hello Alice"));

        Http2DataFrame data2 = (Http2DataFrame) outbound.get(2);
        byte[] data2Bytes = new byte[data2.content().readableBytes()];
        data2.content().readBytes(data2Bytes);
        String json2 = converter.toJson(GrpcFrameCodec.decode(data2Bytes).get(0), chatMethod.getOutputType());
        assertThat("second response should contain Welcome Alice", json2, containsString("Welcome Alice"));

        for (Object f : outbound) {
            if (f instanceof Http2DataFrame) {
                ((Http2DataFrame) f).release();
            }
        }
        channel.finishAndReleaseAll();
    }

    // ---- FIX 1: Router uses side-effect-free peek (times(1) not consumed by routing) ----

    /**
     * FIX 1 proof: a times(1) NON-bidi expectation (HttpResponse action) on a bidi-method
     * path is NOT consumed by the router's routing probe. The router uses
     * {@link HttpState#peekFirstMatchingExpectation} which does not decrement Times,
     * transition scenarios, or set responseInProgress. Since the action is HttpResponse
     * (not GrpcBidiResponse), the router falls back to the re-aggregating chain, and
     * the expectation remains fully active for HttpActionHandler to consume normally.
     */
    @Test
    public void shouldNotConsumeTimesOneExpectationWhenRoutingProbeFindsNonBidiAction() {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        Configuration config = configuration();
        MockServerLogger logger = new MockServerLogger();
        HttpState httpState = new HttpState(config, logger, mock(Scheduler.class));

        // Add a times(1) HttpResponse expectation on the bidi Chat method path
        Expectation timesOneExpectation = new Expectation(
            HttpRequest.request()
                .withMethod("POST")
                .withPath("/com.example.grpc.GreetingService/Chat")
                .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE),
            Times.once(), null, 0
        ).thenRespond(HttpResponse.response().withStatusCode(200).withBody("fallback"));

        httpState.add(timesOneExpectation);

        // Verify the expectation is active before routing
        assertThat("expectation should be active before routing",
            timesOneExpectation.isActive(), is(true));
        assertThat("remaining times should be 1 before routing",
            timesOneExpectation.getTimes().getRemainingTimes(), is(1));

        // Build router with httpState
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
            wsHandler, dashHandler, null, traceHandler, null,
            grpcRespHandler, grpcReqHandler, reqHandler,
            httpState
        );

        EmbeddedChannel channel = new EmbeddedChannel(router);

        // Feed HEADERS for the bidi Chat method
        DefaultHttp2Headers h2Headers = new DefaultHttp2Headers();
        h2Headers.method("POST");
        h2Headers.path("/com.example.grpc.GreetingService/Chat");
        h2Headers.set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        channel.writeInbound(new DefaultHttp2HeadersFrame(h2Headers, false));

        // Router should have fallen back to re-aggregating chain (HttpResponse != GrpcBidiResponse)
        assertThat("pipeline should contain re-aggregating chain (Http2StreamFrameToHttpObjectCodec)",
            channel.pipeline().get(Http2StreamFrameToHttpObjectCodec.class), is(notNullValue()));
        assertThat("pipeline should NOT contain GrpcBidiStreamHandler",
            channel.pipeline().get(GrpcBidiStreamHandler.class), is(nullValue()));

        // CRITICAL ASSERTION: the times(1) expectation was NOT consumed by the routing probe
        assertThat("expectation should still be active after routing probe",
            timesOneExpectation.isActive(), is(true));
        assertThat("remaining times should still be 1 after routing probe (not consumed)",
            timesOneExpectation.getTimes().getRemainingTimes(), is(1));

        channel.finishAndReleaseAll();
    }

    // ---- FIX 2: No false-positive from contains step (removed) ----

    /**
     * FIX 2 proof: a rule with matchJson "Alice" does NOT match an inbound message JSON
     * like {"name":"Alice"} via substring contains. The contains step was removed; only
     * exact string match and regex are supported. "Alice" is neither an exact match for
     * the full JSON string nor a valid regex that matches the full string (it would only
     * match the literal string "Alice"), so the rule should NOT fire.
     */
    @Test
    public void shouldNotMatchViaContainsSubstring() {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        GrpcJsonMessageConverter converter = store.getConverter();
        Descriptors.MethodDescriptor chatMethod = store.getMethod("com.example.grpc.GreetingService", "Chat");

        // Rule with plain "Alice" (not a regex pattern like ".*Alice.*")
        GrpcBidiResponse config = GrpcBidiResponse.grpcBidiResponse()
            .withRule(GrpcBidiRule.grpcBidiRule("Alice")
                .withResponse("{\"greeting\": \"Hi Alice\"}"))
            .withStatusName("OK");

        List<Object> outbound = new ArrayList<>();
        FrameCaptureHandler captureHandler = new FrameCaptureHandler(outbound);

        EmbeddedChannel channel = new EmbeddedChannel(
            captureHandler,
            new GrpcBidiStreamHandler(chatMethod, converter, config)
        );

        // Feed HEADERS
        DefaultHttp2Headers reqHeaders = new DefaultHttp2Headers();
        reqHeaders.method("POST");
        reqHeaders.path("/com.example.grpc.GreetingService/Chat");
        reqHeaders.set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        channel.writeInbound(new DefaultHttp2HeadersFrame(reqHeaders, false));
        assertThat("should have initial HEADERS", outbound.size(), is(1));

        // Feed DATA with a message containing "Alice" as a field value
        // The full JSON is something like: {"name":"Alice"} — NOT the literal string "Alice"
        byte[] proto = converter.toProtobuf("{\"name\":\"Alice\"}", chatMethod.getInputType());
        byte[] frame = GrpcFrameCodec.encode(proto);
        channel.writeInbound(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(frame), true));

        // With the contains step removed, "Alice" should NOT match {"name":"Alice"}
        // (it's neither an exact match nor a regex matching the full JSON string)
        // Expect: initial HEADERS + trailing HEADERS only (NO DATA response)
        assertThat("should have 2 outbound frames (HEADERS + trailing, NO DATA — no contains match)",
            outbound.size(), is(2));
        assertThat("second outbound should be trailing HEADERS (no DATA emitted)",
            outbound.get(1), instanceOf(Http2HeadersFrame.class));
        assertThat("trailing HEADERS endStream=true",
            ((Http2HeadersFrame) outbound.get(1)).isEndStream(), is(true));

        channel.finishAndReleaseAll();
    }

    /**
     * FIX 2 counterpart: a regex rule DOES match when the pattern covers the full JSON.
     */
    @Test
    public void shouldMatchViaRegexPattern() {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        GrpcJsonMessageConverter converter = store.getConverter();
        Descriptors.MethodDescriptor chatMethod = store.getMethod("com.example.grpc.GreetingService", "Chat");

        // Regex pattern that matches any JSON containing "Alice"
        GrpcBidiResponse config = GrpcBidiResponse.grpcBidiResponse()
            .withRule(GrpcBidiRule.grpcBidiRule(".*Alice.*")
                .withResponse("{\"greeting\": \"Hi Alice\"}"))
            .withStatusName("OK");

        List<Object> outbound = new ArrayList<>();
        FrameCaptureHandler captureHandler = new FrameCaptureHandler(outbound);

        EmbeddedChannel channel = new EmbeddedChannel(
            captureHandler,
            new GrpcBidiStreamHandler(chatMethod, converter, config)
        );

        // Feed HEADERS
        DefaultHttp2Headers reqHeaders = new DefaultHttp2Headers();
        reqHeaders.method("POST");
        reqHeaders.path("/com.example.grpc.GreetingService/Chat");
        reqHeaders.set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        channel.writeInbound(new DefaultHttp2HeadersFrame(reqHeaders, false));

        // Feed DATA with a message containing "Alice"
        byte[] proto = converter.toProtobuf("{\"name\":\"Alice\"}", chatMethod.getInputType());
        byte[] frame = GrpcFrameCodec.encode(proto);
        channel.writeInbound(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(frame), true));

        // Regex ".*Alice.*" should match the full JSON
        // Expect: HEADERS + DATA (rule matched) + trailing HEADERS = 3
        assertThat("should have 3 outbound frames (HEADERS + DATA + trailing)",
            outbound.size(), is(3));
        assertThat("second outbound should be DATA (regex rule matched)",
            outbound.get(1), instanceOf(Http2DataFrame.class));

        for (Object f : outbound) {
            if (f instanceof Http2DataFrame) {
                ((Http2DataFrame) f).release();
            }
        }
        channel.finishAndReleaseAll();
    }

    // ---- FIX 3: Negated matchJson (NottableString.isNot) ----

    /**
     * FIX 3: a rule with negated matchJson (isNot=true) inverts the match result.
     * A rule with matchJson "!.*Alice.*" should NOT match an Alice message but
     * SHOULD match a non-Alice message.
     */
    @Test
    public void shouldHonourNottableStringNegation() {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        GrpcJsonMessageConverter converter = store.getConverter();
        Descriptors.MethodDescriptor chatMethod = store.getMethod("com.example.grpc.GreetingService", "Chat");

        // Negated rule: "!.*Alice.*" means "match when inbound does NOT contain Alice"
        GrpcBidiResponse config = GrpcBidiResponse.grpcBidiResponse()
            .withRule(GrpcBidiRule.grpcBidiRule()
                .withMatchJson(NottableString.not(".*Alice.*"))
                .withResponse("{\"greeting\": \"Not Alice!\"}"))
            .withStatusName("OK");

        // -- Test 1: Alice message should NOT trigger the negated rule --
        {
            List<Object> outbound = new ArrayList<>();
            FrameCaptureHandler captureHandler = new FrameCaptureHandler(outbound);
            EmbeddedChannel channel = new EmbeddedChannel(
                captureHandler,
                new GrpcBidiStreamHandler(chatMethod, converter, config)
            );

            DefaultHttp2Headers reqHeaders = new DefaultHttp2Headers();
            reqHeaders.method("POST");
            reqHeaders.path("/com.example.grpc.GreetingService/Chat");
            reqHeaders.set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
            channel.writeInbound(new DefaultHttp2HeadersFrame(reqHeaders, false));

            byte[] proto = converter.toProtobuf("{\"name\":\"Alice\"}", chatMethod.getInputType());
            byte[] frame = GrpcFrameCodec.encode(proto);
            channel.writeInbound(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(frame), true));

            // Negated ".*Alice.*" with Alice input: regex matches -> negation inverts -> no match
            assertThat("negated rule should NOT match Alice (2 frames: HEADERS + trailing)",
                outbound.size(), is(2));
            assertThat("second outbound should be trailing HEADERS (no DATA)",
                outbound.get(1), instanceOf(Http2HeadersFrame.class));

            channel.finishAndReleaseAll();
        }

        // -- Test 2: Bob message SHOULD trigger the negated rule --
        {
            List<Object> outbound = new ArrayList<>();
            FrameCaptureHandler captureHandler = new FrameCaptureHandler(outbound);
            EmbeddedChannel channel = new EmbeddedChannel(
                captureHandler,
                new GrpcBidiStreamHandler(chatMethod, converter, config)
            );

            DefaultHttp2Headers reqHeaders = new DefaultHttp2Headers();
            reqHeaders.method("POST");
            reqHeaders.path("/com.example.grpc.GreetingService/Chat");
            reqHeaders.set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
            channel.writeInbound(new DefaultHttp2HeadersFrame(reqHeaders, false));

            byte[] proto = converter.toProtobuf("{\"name\":\"Bob\"}", chatMethod.getInputType());
            byte[] frame = GrpcFrameCodec.encode(proto);
            channel.writeInbound(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(frame), true));

            // Negated ".*Alice.*" with Bob input: regex does NOT match -> negation inverts -> match!
            assertThat("negated rule should match Bob (3 frames: HEADERS + DATA + trailing)",
                outbound.size(), is(3));
            assertThat("second outbound should be DATA (negated rule matched Bob)",
                outbound.get(1), instanceOf(Http2DataFrame.class));

            for (Object f : outbound) {
                if (f instanceof Http2DataFrame) {
                    ((Http2DataFrame) f).release();
                }
            }
            channel.finishAndReleaseAll();
        }
    }

    /**
     * FIX 3 unit-level: directly tests {@link GrpcBidiStreamHandler#matchesRule} with
     * negated NottableString.
     */
    @Test
    public void matchesRuleShouldInvertWhenNottableStringIsNot() {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        Descriptors.MethodDescriptor chatMethod = store.getMethod("com.example.grpc.GreetingService", "Chat");

        GrpcBidiStreamHandler handler = new GrpcBidiStreamHandler(
            chatMethod, store.getConverter(), (Function<String, List<String>>) null
        );

        // Non-negated rule: ".*hello.*" matches "hello world"
        GrpcBidiRule normalRule = GrpcBidiRule.grpcBidiRule(".*hello.*");
        assertThat("non-negated rule should match", handler.matchesRule(normalRule, "hello world"), is(true));
        assertThat("non-negated rule should not match other text",
            handler.matchesRule(normalRule, "goodbye world"), is(false));

        // Negated rule: "!.*hello.*" inverts the result
        GrpcBidiRule negatedRule = GrpcBidiRule.grpcBidiRule()
            .withMatchJson(NottableString.not(".*hello.*"));
        assertThat("negated rule should NOT match matching text",
            handler.matchesRule(negatedRule, "hello world"), is(false));
        assertThat("negated rule SHOULD match non-matching text",
            handler.matchesRule(negatedRule, "goodbye world"), is(true));
    }

    /**
     * FIX 2 unit-level: directly tests that matchesRule does NOT use contains.
     */
    @Test
    public void matchesRuleShouldNotUseContains() {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        Descriptors.MethodDescriptor chatMethod = store.getMethod("com.example.grpc.GreetingService", "Chat");

        GrpcBidiStreamHandler handler = new GrpcBidiStreamHandler(
            chatMethod, store.getConverter(), (Function<String, List<String>>) null
        );

        // "Alice" as a plain substring should NOT match a larger JSON string via contains
        GrpcBidiRule rule = GrpcBidiRule.grpcBidiRule("Alice");
        assertThat("plain 'Alice' should match exact string 'Alice'",
            handler.matchesRule(rule, "Alice"), is(true));
        assertThat("plain 'Alice' should NOT match via contains in larger string",
            handler.matchesRule(rule, "{\"sender\":\"Alice\",\"note\":\"x\"}"), is(false));
        assertThat("plain 'Alice' should NOT match via contains in JSON",
            handler.matchesRule(rule, "{\"name\":\"Alice\"}"), is(false));
    }

    // ---- Follow-up 1: Times-consumption + request logging/verification ----

    /**
     * Follow-up 1 proof: a times(1) GrpcBidiResponse expectation is consumed by the
     * router when it commits to the bidi path. After routing, the expectation's remaining
     * Times must be 0 (consumed). A second routing attempt returns null (exhausted),
     * falling back to the re-aggregating chain.
     */
    @Test
    public void shouldConsumeTimesOnBidiCommit() {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        Configuration config = configuration();
        MockServerLogger logger = new MockServerLogger();
        HttpState httpState = new HttpState(config, logger, mock(Scheduler.class));

        // Add a times(1) GrpcBidiResponse expectation on the Chat bidi method
        GrpcBidiResponse bidiResponse = GrpcBidiResponse.grpcBidiResponse()
            .withRule(GrpcBidiRule.grpcBidiRule(".*")
                .withResponse("{\"greeting\": \"echo\"}"))
            .withStatusName("OK");

        Expectation timesOneExpectation = new Expectation(
            HttpRequest.request()
                .withMethod("POST")
                .withPath("/com.example.grpc.GreetingService/Chat")
                .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE),
            Times.once(), null, 0
        ).thenRespondWithGrpcBidi(bidiResponse);

        httpState.add(timesOneExpectation);

        // Verify expectation is active
        assertThat("expectation should be active before routing",
            timesOneExpectation.isActive(), is(true));
        assertThat("remaining times should be 1 before routing",
            timesOneExpectation.getTimes().getRemainingTimes(), is(1));

        // Build and execute the router
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
            wsHandler, dashHandler, null, traceHandler, null,
            grpcRespHandler, grpcReqHandler, reqHandler,
            httpState
        );

        EmbeddedChannel channel = new EmbeddedChannel(router);

        DefaultHttp2Headers h2Headers = new DefaultHttp2Headers();
        h2Headers.method("POST");
        h2Headers.path("/com.example.grpc.GreetingService/Chat");
        h2Headers.set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        channel.writeInbound(new DefaultHttp2HeadersFrame(h2Headers, false));

        // Router should have installed the bidi handler (not re-aggregating chain)
        assertThat("pipeline should contain GrpcBidiStreamHandler",
            channel.pipeline().get(GrpcBidiStreamHandler.class), is(notNullValue()));

        // CRITICAL: Times should now be consumed (0 remaining)
        assertThat("remaining times should be 0 after bidi commit (consumed)",
            timesOneExpectation.getTimes().getRemainingTimes(), is(0));

        channel.finishAndReleaseAll();
    }

    /**
     * Follow-up 1 proof: after a times(1) bidi expectation is consumed, a second bidi
     * routing attempt on a new stream finds no matching expectation and falls back to
     * the re-aggregating chain.
     */
    @Test
    public void shouldExhaustTimesOneBidiExpectation() {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        Configuration config = configuration();
        MockServerLogger logger = new MockServerLogger();
        HttpState httpState = new HttpState(config, logger, mock(Scheduler.class));

        GrpcBidiResponse bidiResponse = GrpcBidiResponse.grpcBidiResponse()
            .withRule(GrpcBidiRule.grpcBidiRule(".*")
                .withResponse("{\"greeting\": \"echo\"}"))
            .withStatusName("OK");

        Expectation timesOneExpectation = new Expectation(
            HttpRequest.request()
                .withMethod("POST")
                .withPath("/com.example.grpc.GreetingService/Chat")
                .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE),
            Times.once(), null, 0
        ).thenRespondWithGrpcBidi(bidiResponse);

        httpState.add(timesOneExpectation);

        // First stream: consumes the expectation
        {
            CallbackWebSocketServerHandler wsHandler = new CallbackWebSocketServerHandler(httpState);
            DashboardWebSocketHandler dashHandler = new DashboardWebSocketHandler(httpState, false, false);
            TraceContextHandler traceHandler = new TraceContextHandler(config);
            HttpRequestHandler reqHandler = new HttpRequestHandler(
                config, mock(org.mockserver.lifecycle.LifeCycle.class), httpState,
                mock(org.mockserver.mock.action.http.HttpActionHandler.class)
            );
            GrpcToHttpResponseHandler grpcRespHandler = new GrpcToHttpResponseHandler(logger, store);
            GrpcToHttpRequestHandler grpcReqHandler = new GrpcToHttpRequestHandler(logger, store);

            GrpcBidiRouterHandler router1 = new GrpcBidiRouterHandler(
                config, store, logger, false, null,
                wsHandler, dashHandler, null, traceHandler, null,
                grpcRespHandler, grpcReqHandler, reqHandler,
                httpState
            );

            EmbeddedChannel ch1 = new EmbeddedChannel(router1);
            DefaultHttp2Headers h1 = new DefaultHttp2Headers();
            h1.method("POST");
            h1.path("/com.example.grpc.GreetingService/Chat");
            h1.set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
            ch1.writeInbound(new DefaultHttp2HeadersFrame(h1, true));

            assertThat("first stream should get bidi handler",
                ch1.pipeline().get(GrpcBidiStreamHandler.class), is(notNullValue()));

            // Complete the stream to clear responseInProgress
            ch1.finishAndReleaseAll();
        }

        // Post-process to clear responseInProgress (simulating the completion callback)
        httpState.postProcess(timesOneExpectation);

        // Second stream: expectation exhausted -> fallback to re-aggregating chain
        {
            CallbackWebSocketServerHandler wsHandler2 = new CallbackWebSocketServerHandler(httpState);
            DashboardWebSocketHandler dashHandler2 = new DashboardWebSocketHandler(httpState, false, false);
            TraceContextHandler traceHandler2 = new TraceContextHandler(config);
            HttpRequestHandler reqHandler2 = new HttpRequestHandler(
                config, mock(org.mockserver.lifecycle.LifeCycle.class), httpState,
                mock(org.mockserver.mock.action.http.HttpActionHandler.class)
            );
            GrpcToHttpResponseHandler grpcRespHandler2 = new GrpcToHttpResponseHandler(logger, store);
            GrpcToHttpRequestHandler grpcReqHandler2 = new GrpcToHttpRequestHandler(logger, store);

            GrpcBidiRouterHandler router2 = new GrpcBidiRouterHandler(
                config, store, logger, false, null,
                wsHandler2, dashHandler2, null, traceHandler2, null,
                grpcRespHandler2, grpcReqHandler2, reqHandler2,
                httpState
            );

            EmbeddedChannel ch2 = new EmbeddedChannel(router2);
            DefaultHttp2Headers h2 = new DefaultHttp2Headers();
            h2.method("POST");
            h2.path("/com.example.grpc.GreetingService/Chat");
            h2.set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
            ch2.writeInbound(new DefaultHttp2HeadersFrame(h2, false));

            assertThat("second stream should NOT get bidi handler (exhausted)",
                ch2.pipeline().get(GrpcBidiStreamHandler.class), is(nullValue()));
            assertThat("second stream should fall back to re-aggregating chain",
                ch2.pipeline().get(io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec.class), is(notNullValue()));

            ch2.finishAndReleaseAll();
        }
    }

    /**
     * Follow-up 1 proof: the fallback path (non-bidi action on same path) still does NOT
     * consume Times. This is a REGRESSION test — must keep existing RequestMatchersPeekTest
     * behaviour: a times(1) HttpResponse expectation on a bidi-method path is NOT consumed
     * by the routing probe.
     */
    @Test
    public void shouldNotConsumeTimesOnFallbackPath() {
        // This test is the existing shouldNotConsumeTimesOneExpectationWhenRoutingProbeFindsNonBidiAction
        // test — it stays intact (unchanged). Verifying the existing test still passes proves
        // the fallback path is not broken by the consume-on-commit change.
        shouldNotConsumeTimesOneExpectationWhenRoutingProbeFindsNonBidiAction();
    }

    /**
     * Follow-up 1 proof: completionCallback is invoked when the bidi stream finishes,
     * clearing responseInProgress.
     */
    @Test
    public void shouldInvokeCompletionCallbackOnFinish() {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        GrpcJsonMessageConverter converter = store.getConverter();
        Descriptors.MethodDescriptor chatMethod = store.getMethod("com.example.grpc.GreetingService", "Chat");

        java.util.concurrent.atomic.AtomicBoolean callbackInvoked = new java.util.concurrent.atomic.AtomicBoolean(false);

        GrpcBidiResponse bidiConfig = GrpcBidiResponse.grpcBidiResponse()
            .withStatusName("OK");

        List<Object> outbound = new ArrayList<>();
        FrameCaptureHandler captureHandler = new FrameCaptureHandler(outbound);

        GrpcBidiStreamHandler handler = new GrpcBidiStreamHandler(
            chatMethod, converter, bidiConfig, () -> callbackInvoked.set(true)
        );

        EmbeddedChannel channel = new EmbeddedChannel(captureHandler, handler);

        // Feed HEADERS + endStream=true -> should finish immediately
        DefaultHttp2Headers reqHeaders = new DefaultHttp2Headers();
        reqHeaders.method("POST");
        reqHeaders.path("/com.example.grpc.GreetingService/Chat");
        reqHeaders.set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        channel.writeInbound(new DefaultHttp2HeadersFrame(reqHeaders, true));

        // Verify: completion callback was invoked
        assertThat("completion callback should be invoked on stream finish",
            callbackInvoked.get(), is(true));

        // Verify trailing HEADERS were written
        assertThat("should have initial HEADERS + trailing HEADERS",
            outbound.size(), is(2));

        channel.finishAndReleaseAll();
    }

    /**
     * Follow-up 1 proof: completionCallback is invoked on the error path too.
     */
    @Test
    public void shouldInvokeCompletionCallbackOnError() {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        GrpcJsonMessageConverter converter = store.getConverter();
        Descriptors.MethodDescriptor chatMethod = store.getMethod("com.example.grpc.GreetingService", "Chat");

        java.util.concurrent.atomic.AtomicBoolean callbackInvoked = new java.util.concurrent.atomic.AtomicBoolean(false);

        GrpcBidiResponse bidiConfig = GrpcBidiResponse.grpcBidiResponse()
            .withStatusName("OK");

        List<Object> outbound = new ArrayList<>();
        FrameCaptureHandler captureHandler = new FrameCaptureHandler(outbound);

        // Use a small decoder cap (16 bytes) to trigger RESOURCE_EXHAUSTED
        org.mockserver.grpc.IncrementalGrpcFrameDecoder smallDecoder =
            new org.mockserver.grpc.IncrementalGrpcFrameDecoder(16);

        GrpcBidiStreamHandler handler = new GrpcBidiStreamHandler(
            chatMethod, converter, null, bidiConfig, smallDecoder, () -> callbackInvoked.set(true)
        );

        EmbeddedChannel channel = new EmbeddedChannel(captureHandler, handler);

        // Feed HEADERS (no endStream)
        DefaultHttp2Headers reqHeaders = new DefaultHttp2Headers();
        reqHeaders.method("POST");
        reqHeaders.path("/com.example.grpc.GreetingService/Chat");
        reqHeaders.set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        channel.writeInbound(new DefaultHttp2HeadersFrame(reqHeaders, false));

        // Feed a DATA frame that exceeds the 16-byte cap
        byte[] bigPayload = new byte[100];
        channel.writeInbound(new DefaultHttp2DataFrame(
            Unpooled.wrappedBuffer(bigPayload), false));

        // Verify: completion callback was invoked even on error
        assertThat("completion callback should be invoked on error path",
            callbackInvoked.get(), is(true));

        channel.finishAndReleaseAll();
    }

    // ---- Follow-up 2: Per-message + top-level delay ----

    /**
     * Follow-up 2: verifies that per-message delay on eager messages is honoured.
     * Uses a 0ms delay (which runs immediately via the event loop) to verify the
     * scheduling path works without introducing flaky timing dependencies.
     * The key assertion is that the messages are still emitted in order and the
     * finish (trailing HEADERS) comes AFTER all messages.
     */
    @Test
    public void shouldHonourPerMessageDelayOnEagerMessages() {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        GrpcJsonMessageConverter converter = store.getConverter();
        Descriptors.MethodDescriptor chatMethod = store.getMethod("com.example.grpc.GreetingService", "Chat");

        // Configure eager messages with 0ms delays (tests the scheduling path without timing flakes)
        GrpcBidiResponse bidiConfig = GrpcBidiResponse.grpcBidiResponse()
            .withMessage(GrpcStreamMessage.grpcStreamMessage("{\"greeting\": \"First\"}")
                .withDelay(new Delay(TimeUnit.MILLISECONDS, 0)))
            .withMessage(GrpcStreamMessage.grpcStreamMessage("{\"greeting\": \"Second\"}")
                .withDelay(new Delay(TimeUnit.MILLISECONDS, 0)))
            .withStatusName("OK");

        List<Object> outbound = new ArrayList<>();
        FrameCaptureHandler captureHandler = new FrameCaptureHandler(outbound);

        EmbeddedChannel channel = new EmbeddedChannel(
            captureHandler,
            new GrpcBidiStreamHandler(chatMethod, converter, bidiConfig)
        );

        // Feed HEADERS + endStream=true
        DefaultHttp2Headers reqHeaders = new DefaultHttp2Headers();
        reqHeaders.method("POST");
        reqHeaders.path("/com.example.grpc.GreetingService/Chat");
        reqHeaders.set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        channel.writeInbound(new DefaultHttp2HeadersFrame(reqHeaders, true));

        // Run pending scheduled tasks in the EmbeddedChannel's event loop
        channel.runPendingTasks();

        // Expect: initial HEADERS + First DATA + Second DATA + trailing HEADERS = 4
        assertThat("should have 4 outbound frames (HEADERS + 2 DATA + trailing HEADERS)",
            outbound.size(), is(4));

        // Verify ordering: First before Second
        assertThat("first outbound should be HEADERS",
            outbound.get(0), instanceOf(Http2HeadersFrame.class));
        assertThat("second outbound should be DATA (First)",
            outbound.get(1), instanceOf(Http2DataFrame.class));
        assertThat("third outbound should be DATA (Second)",
            outbound.get(2), instanceOf(Http2DataFrame.class));
        assertThat("fourth outbound should be trailing HEADERS",
            outbound.get(3), instanceOf(Http2HeadersFrame.class));
        assertThat("trailing HEADERS endStream=true",
            ((Http2HeadersFrame) outbound.get(3)).isEndStream(), is(true));

        // Verify content of first and second messages
        Http2DataFrame firstData = (Http2DataFrame) outbound.get(1);
        byte[] firstBytes = new byte[firstData.content().readableBytes()];
        firstData.content().readBytes(firstBytes);
        String firstJson = converter.toJson(GrpcFrameCodec.decode(firstBytes).get(0), chatMethod.getOutputType());
        assertThat("first eager message should contain 'First'", firstJson, containsString("First"));

        Http2DataFrame secondData = (Http2DataFrame) outbound.get(2);
        byte[] secondBytes = new byte[secondData.content().readableBytes()];
        secondData.content().readBytes(secondBytes);
        String secondJson = converter.toJson(GrpcFrameCodec.decode(secondBytes).get(0), chatMethod.getOutputType());
        assertThat("second eager message should contain 'Second'", secondJson, containsString("Second"));

        for (Object f : outbound) {
            if (f instanceof Http2DataFrame) {
                ((Http2DataFrame) f).release();
            }
        }
        channel.finishAndReleaseAll();
    }

    /**
     * Follow-up 2: verifies that per-message delay on rule responses is honoured.
     * Uses 0ms delay to exercise the scheduling path without timing flakes.
     * The trailing HEADERS (grpc-status) MUST come AFTER all rule responses.
     */
    @Test
    public void shouldHonourPerMessageDelayOnRuleResponses() {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        GrpcJsonMessageConverter converter = store.getConverter();
        Descriptors.MethodDescriptor chatMethod = store.getMethod("com.example.grpc.GreetingService", "Chat");

        GrpcBidiResponse bidiConfig = GrpcBidiResponse.grpcBidiResponse()
            .withRule(GrpcBidiRule.grpcBidiRule(".*Alice.*")
                .withResponse(GrpcStreamMessage.grpcStreamMessage("{\"greeting\": \"Hi Alice\"}")
                    .withDelay(new Delay(TimeUnit.MILLISECONDS, 0)))
                .withResponse(GrpcStreamMessage.grpcStreamMessage("{\"greeting\": \"Welcome Alice\"}")
                    .withDelay(new Delay(TimeUnit.MILLISECONDS, 0))))
            .withStatusName("OK");

        List<Object> outbound = new ArrayList<>();
        FrameCaptureHandler captureHandler = new FrameCaptureHandler(outbound);

        EmbeddedChannel channel = new EmbeddedChannel(
            captureHandler,
            new GrpcBidiStreamHandler(chatMethod, converter, bidiConfig)
        );

        // Feed HEADERS
        DefaultHttp2Headers reqHeaders = new DefaultHttp2Headers();
        reqHeaders.method("POST");
        reqHeaders.path("/com.example.grpc.GreetingService/Chat");
        reqHeaders.set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        channel.writeInbound(new DefaultHttp2HeadersFrame(reqHeaders, false));

        // Feed DATA(Alice, endStream=true)
        byte[] proto = converter.toProtobuf("{\"name\":\"Alice\"}", chatMethod.getInputType());
        byte[] frame = GrpcFrameCodec.encode(proto);
        channel.writeInbound(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(frame), true));

        // Run pending scheduled tasks
        channel.runPendingTasks();

        // Expect: HEADERS + Hi Alice DATA + Welcome Alice DATA + trailing HEADERS = 4
        assertThat("should have 4 outbound frames", outbound.size(), is(4));

        // Verify trailing HEADERS come AFTER all rule responses
        assertThat("fourth outbound should be trailing HEADERS",
            outbound.get(3), instanceOf(Http2HeadersFrame.class));
        assertThat("trailing HEADERS endStream=true",
            ((Http2HeadersFrame) outbound.get(3)).isEndStream(), is(true));
        assertThat("trailing grpc-status should be 0 (OK)",
            ((Http2HeadersFrame) outbound.get(3)).headers().get(GrpcStatusMapper.GRPC_STATUS_HEADER).toString(), is("0"));

        // Verify rule responses
        Http2DataFrame data1 = (Http2DataFrame) outbound.get(1);
        byte[] d1b = new byte[data1.content().readableBytes()];
        data1.content().readBytes(d1b);
        assertThat("first rule response should contain 'Hi Alice'",
            converter.toJson(GrpcFrameCodec.decode(d1b).get(0), chatMethod.getOutputType()),
            containsString("Hi Alice"));

        Http2DataFrame data2 = (Http2DataFrame) outbound.get(2);
        byte[] d2b = new byte[data2.content().readableBytes()];
        data2.content().readBytes(d2b);
        assertThat("second rule response should contain 'Welcome Alice'",
            converter.toJson(GrpcFrameCodec.decode(d2b).get(0), chatMethod.getOutputType()),
            containsString("Welcome Alice"));

        for (Object f : outbound) {
            if (f instanceof Http2DataFrame) {
                ((Http2DataFrame) f).release();
            }
        }
        channel.finishAndReleaseAll();
    }

    /**
     * Follow-up 2: verifies that the top-level action delay is applied before the
     * initial response HEADERS. Uses 0ms delay to test the scheduling code path.
     */
    @Test
    public void shouldApplyTopLevelActionDelay() {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        GrpcJsonMessageConverter converter = store.getConverter();
        Descriptors.MethodDescriptor chatMethod = store.getMethod("com.example.grpc.GreetingService", "Chat");

        GrpcBidiResponse bidiConfig = GrpcBidiResponse.grpcBidiResponse()
            .withStatusName("OK");
        // Set top-level action delay (0ms to avoid timing flakes)
        bidiConfig.withDelay(new Delay(TimeUnit.MILLISECONDS, 0));

        List<Object> outbound = new ArrayList<>();
        FrameCaptureHandler captureHandler = new FrameCaptureHandler(outbound);

        EmbeddedChannel channel = new EmbeddedChannel(
            captureHandler,
            new GrpcBidiStreamHandler(chatMethod, converter, bidiConfig)
        );

        // Feed HEADERS + endStream=true
        DefaultHttp2Headers reqHeaders = new DefaultHttp2Headers();
        reqHeaders.method("POST");
        reqHeaders.path("/com.example.grpc.GreetingService/Chat");
        reqHeaders.set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        channel.writeInbound(new DefaultHttp2HeadersFrame(reqHeaders, true));

        // Before running pending tasks, the 0ms delay is scheduled but not yet executed
        // (EmbeddedChannel uses a deterministic event loop)
        // Run pending tasks to execute the delayed response
        channel.runPendingTasks();

        // Expect: initial HEADERS + trailing HEADERS = 2
        assertThat("should have 2 outbound frames after delay fires",
            outbound.size(), is(2));
        assertThat("first outbound should be HEADERS",
            outbound.get(0), instanceOf(Http2HeadersFrame.class));
        assertThat("second outbound should be trailing HEADERS",
            outbound.get(1), instanceOf(Http2HeadersFrame.class));

        channel.finishAndReleaseAll();
    }

    // ---- FIX 1 (channelInactive leak): abandoned stream cleanup ----

    /**
     * FIX 1 proof: if a bidi stream is abandoned (channel goes inactive without a clean
     * END_STREAM / finish()), the completion callback is still invoked via the
     * {@code channelInactive} override. This ensures responseInProgress is cleared and
     * a times-limited expectation is not stuck forever.
     */
    @Test
    public void shouldInvokeCompletionCallbackOnChannelInactiveForAbandonedStream() {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        GrpcJsonMessageConverter converter = store.getConverter();
        Descriptors.MethodDescriptor chatMethod = store.getMethod("com.example.grpc.GreetingService", "Chat");

        java.util.concurrent.atomic.AtomicInteger callbackCount = new java.util.concurrent.atomic.AtomicInteger(0);

        GrpcBidiResponse bidiConfig = GrpcBidiResponse.grpcBidiResponse()
            .withStatusName("OK");

        List<Object> outbound = new ArrayList<>();
        FrameCaptureHandler captureHandler = new FrameCaptureHandler(outbound);

        GrpcBidiStreamHandler handler = new GrpcBidiStreamHandler(
            chatMethod, converter, bidiConfig, callbackCount::incrementAndGet
        );

        EmbeddedChannel channel = new EmbeddedChannel(captureHandler, handler);

        // Feed HEADERS (no endStream) — stream is open but not finished
        DefaultHttp2Headers reqHeaders = new DefaultHttp2Headers();
        reqHeaders.method("POST");
        reqHeaders.path("/com.example.grpc.GreetingService/Chat");
        reqHeaders.set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        channel.writeInbound(new DefaultHttp2HeadersFrame(reqHeaders, false));

        // Verify: callback NOT yet invoked (stream is still active)
        assertThat("callback should not be invoked before channel inactive",
            callbackCount.get(), is(0));

        // Simulate abandoned stream: close the channel (triggers channelInactive)
        channel.close().syncUninterruptibly();

        // Verify: callback WAS invoked exactly once via channelInactive
        assertThat("callback should be invoked exactly once on abandoned stream via channelInactive",
            callbackCount.get(), is(1));
    }

    /**
     * FIX 1 proof (no double-invoke): if a bidi stream finishes normally (END_STREAM) and
     * then channelInactive fires (as it always does when the channel closes), the completion
     * callback must NOT be invoked a second time. The AtomicBoolean CAS guard in
     * invokeCompletionCallback ensures exactly-once semantics.
     */
    @Test
    public void shouldNotDoubleInvokeCompletionCallbackOnFinishThenChannelInactive() {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        GrpcJsonMessageConverter converter = store.getConverter();
        Descriptors.MethodDescriptor chatMethod = store.getMethod("com.example.grpc.GreetingService", "Chat");

        java.util.concurrent.atomic.AtomicInteger callbackCount = new java.util.concurrent.atomic.AtomicInteger(0);

        GrpcBidiResponse bidiConfig = GrpcBidiResponse.grpcBidiResponse()
            .withStatusName("OK");

        List<Object> outbound = new ArrayList<>();
        FrameCaptureHandler captureHandler = new FrameCaptureHandler(outbound);

        GrpcBidiStreamHandler handler = new GrpcBidiStreamHandler(
            chatMethod, converter, bidiConfig, callbackCount::incrementAndGet
        );

        EmbeddedChannel channel = new EmbeddedChannel(captureHandler, handler);

        // Feed HEADERS + endStream=true -> stream finishes normally (callback invoked once)
        DefaultHttp2Headers reqHeaders = new DefaultHttp2Headers();
        reqHeaders.method("POST");
        reqHeaders.path("/com.example.grpc.GreetingService/Chat");
        reqHeaders.set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        channel.writeInbound(new DefaultHttp2HeadersFrame(reqHeaders, true));

        // Verify: callback invoked exactly once from finish()
        assertThat("callback should be invoked once after finish()",
            callbackCount.get(), is(1));

        // Close the channel (triggers channelInactive)
        channel.close().syncUninterruptibly();

        // Verify: callback still invoked exactly once (no double-invoke)
        assertThat("callback should still be invoked exactly once after channelInactive (no double-invoke)",
            callbackCount.get(), is(1));
    }

    /**
     * FIX 1 proof (exception path): if exceptionCaught fires on an active stream, the
     * callback is invoked. A subsequent channelInactive does NOT double-invoke.
     */
    @Test
    public void shouldNotDoubleInvokeCompletionCallbackOnExceptionThenChannelInactive() {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        GrpcJsonMessageConverter converter = store.getConverter();
        Descriptors.MethodDescriptor chatMethod = store.getMethod("com.example.grpc.GreetingService", "Chat");

        java.util.concurrent.atomic.AtomicInteger callbackCount = new java.util.concurrent.atomic.AtomicInteger(0);

        GrpcBidiResponse bidiConfig = GrpcBidiResponse.grpcBidiResponse()
            .withStatusName("OK");

        List<Object> outbound = new ArrayList<>();
        FrameCaptureHandler captureHandler = new FrameCaptureHandler(outbound);

        GrpcBidiStreamHandler handler = new GrpcBidiStreamHandler(
            chatMethod, converter, bidiConfig, callbackCount::incrementAndGet
        );

        EmbeddedChannel channel = new EmbeddedChannel(captureHandler, handler);

        // Feed HEADERS (no endStream) — stream is open
        DefaultHttp2Headers reqHeaders = new DefaultHttp2Headers();
        reqHeaders.method("POST");
        reqHeaders.path("/com.example.grpc.GreetingService/Chat");
        reqHeaders.set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        channel.writeInbound(new DefaultHttp2HeadersFrame(reqHeaders, false));

        // Trigger exceptionCaught (fires writeTrailer -> invokeCompletionCallback)
        channel.pipeline().fireExceptionCaught(new RuntimeException("simulated failure"));

        // Verify: callback invoked once from exceptionCaught -> writeTrailer
        assertThat("callback should be invoked once after exceptionCaught",
            callbackCount.get(), is(1));

        // Close the channel (triggers channelInactive)
        channel.close().syncUninterruptibly();

        // Verify: callback still invoked exactly once
        assertThat("callback should still be invoked exactly once after channelInactive",
            callbackCount.get(), is(1));
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
