package org.mockserver.netty.grpc;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.WireFormat;
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
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.grpc.GrpcServerReflectionHandler;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.netty.HttpRequestHandler;
import org.mockserver.netty.unification.TraceContextHandler;
import org.mockserver.netty.websocketregistry.CallbackWebSocketServerHandler;
import org.mockserver.scheduler.Scheduler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.empty;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * Tests for interactive gRPC Server Reflection over the bidirectional-streaming
 * multiplex pipeline ({@link GrpcBidiReflectionHandler}).
 * <p>
 * Verifies:
 * <ul>
 *   <li>Per-request reflection responses are interleaved (each inbound request gets a
 *       response before the next request is fed)</li>
 *   <li>Both v1 and v1alpha reflection paths are routed to the bidi handler</li>
 *   <li>The router correctly recognises reflection paths and installs
 *       {@link GrpcBidiReflectionHandler} without affecting non-reflection streams</li>
 *   <li>list_services, file_containing_symbol, and file_by_filename all work over
 *       the bidi path</li>
 *   <li>Proper trailing grpc-status=0 on stream end</li>
 * </ul>
 * <p>
 * Uses EmbeddedChannel (no grpc-java dependency). All tests use the
 * {@code greeting.dsc} descriptor which defines {@code com.example.grpc.GreetingService}.
 */
public class GrpcBidiReflectionMultiplexTest {

    private static GrpcProtoDescriptorStore loadDescriptorStore() {
        GrpcProtoDescriptorStore store = new GrpcProtoDescriptorStore(new MockServerLogger());
        store.loadDescriptorSetFromPath(Paths.get("../mockserver-core/src/test/resources/grpc/greeting.dsc"));
        return store;
    }

    // ---- (a) Core interleaving: N reflection requests -> N interleaved responses ----

    /**
     * Drives HEADERS + list_services request + file_containing_symbol request over a
     * single bidi reflection stream. Asserts that each request gets a response DATA
     * frame BEFORE the next request is fed (interleaving proof), and that a trailing
     * HEADERS with grpc-status=0 is written on END_STREAM.
     */
    @Test
    public void shouldInterleaveReflectionResponsesPerRequest() throws IOException {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        GrpcServerReflectionHandler reflectionHandler = new GrpcServerReflectionHandler(store);

        List<Object> outbound = new ArrayList<>();
        FrameCaptureHandler captureHandler = new FrameCaptureHandler(outbound);

        EmbeddedChannel channel = new EmbeddedChannel(
            captureHandler,
            new GrpcBidiReflectionHandler(reflectionHandler)
        );

        // --- STEP 1: Feed HEADERS (v1 path) ---
        DefaultHttp2Headers reqHeaders = new DefaultHttp2Headers();
        reqHeaders.method("POST");
        reqHeaders.path(GrpcServerReflectionHandler.REFLECTION_V1_PATH);
        reqHeaders.set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        channel.writeInbound(new DefaultHttp2HeadersFrame(reqHeaders, false));

        // After HEADERS: expect initial response HEADERS frame
        assertThat("should have initial response HEADERS after inbound HEADERS",
            outbound.size(), is(1));
        assertThat("first outbound should be HEADERS frame",
            outbound.get(0), instanceOf(Http2HeadersFrame.class));
        Http2HeadersFrame initialHeaders = (Http2HeadersFrame) outbound.get(0);
        assertThat("initial HEADERS :status should be 200",
            initialHeaders.headers().status().toString(), is("200"));
        assertThat("initial HEADERS should have content-type=application/grpc",
            initialHeaders.headers().get("content-type").toString(),
            is(GrpcStatusMapper.GRPC_CONTENT_TYPE));
        assertThat("initial HEADERS should not have endStream",
            initialHeaders.isEndStream(), is(false));

        // --- STEP 2: Feed DATA(list_services request, endStream=false) ---
        byte[] listServicesReq = buildListServicesRequest("");
        byte[] frame1 = GrpcFrameCodec.encode(stripGrpcFrame(listServicesReq));
        channel.writeInbound(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(frame1), false));

        // INTERLEAVING ASSERTION: response for list_services appears before next request
        assertThat("should have HEADERS + list_services response BEFORE next request",
            outbound.size(), is(2));
        assertThat("second outbound should be DATA frame",
            outbound.get(1), instanceOf(Http2DataFrame.class));

        // Verify the response contains service names
        Http2DataFrame listResp = (Http2DataFrame) outbound.get(1);
        byte[] listRespBytes = extractDataBytes(listResp);
        List<String> serviceNames = parseListServicesFromGrpcFrame(listRespBytes);
        assertThat("list_services response should contain GreetingService",
            serviceNames, hasItem("com.example.grpc.GreetingService"));

        // --- STEP 3: Feed DATA(file_containing_symbol request, endStream=true) ---
        byte[] fileSymbolReq = buildFileContainingSymbolRequest("com.example.grpc.GreetingService");
        byte[] frame2 = GrpcFrameCodec.encode(stripGrpcFrame(fileSymbolReq));
        channel.writeInbound(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(frame2), true));

        // After END_STREAM: expect file_containing_symbol response + trailing HEADERS
        assertThat("should have 4 outbound frames (HEADERS + list_resp + file_resp + trailing HEADERS)",
            outbound.size(), is(4));

        // Verify file_containing_symbol response
        assertThat("third outbound should be DATA frame",
            outbound.get(2), instanceOf(Http2DataFrame.class));
        Http2DataFrame fileResp = (Http2DataFrame) outbound.get(2);
        byte[] fileRespBytes = extractDataBytes(fileResp);
        List<byte[]> fdProtos = parseFileDescriptorFromGrpcFrame(fileRespBytes);
        assertThat("file_containing_symbol should return file descriptor protos",
            fdProtos, is(not(empty())));
        DescriptorProtos.FileDescriptorProto fdProto =
            DescriptorProtos.FileDescriptorProto.parseFrom(fdProtos.get(0));
        assertThat("file descriptor should be greeting.proto",
            fdProto.getName(), is("greeting.proto"));

        // Verify trailing HEADERS with grpc-status=0
        assertThat("fourth outbound should be HEADERS frame",
            outbound.get(3), instanceOf(Http2HeadersFrame.class));
        Http2HeadersFrame trailing = (Http2HeadersFrame) outbound.get(3);
        assertThat("trailing HEADERS must have endStream=true",
            trailing.isEndStream(), is(true));
        assertThat("trailing HEADERS should have grpc-status=0",
            trailing.headers().get(GrpcStatusMapper.GRPC_STATUS_HEADER).toString(), is("0"));

        releaseDataFrames(outbound);
        channel.finishAndReleaseAll();
    }

    // ---- (b) v1alpha path works identically ----

    @Test
    public void shouldHandleV1AlphaReflectionPath() throws IOException {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        GrpcServerReflectionHandler reflectionHandler = new GrpcServerReflectionHandler(store);

        List<Object> outbound = new ArrayList<>();
        FrameCaptureHandler captureHandler = new FrameCaptureHandler(outbound);

        EmbeddedChannel channel = new EmbeddedChannel(
            captureHandler,
            new GrpcBidiReflectionHandler(reflectionHandler)
        );

        // Feed HEADERS with v1alpha path
        DefaultHttp2Headers reqHeaders = new DefaultHttp2Headers();
        reqHeaders.method("POST");
        reqHeaders.path(GrpcServerReflectionHandler.REFLECTION_V1ALPHA_PATH);
        reqHeaders.set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        channel.writeInbound(new DefaultHttp2HeadersFrame(reqHeaders, false));

        // Feed list_services + END_STREAM
        byte[] listReq = buildListServicesRequest("");
        byte[] frame = GrpcFrameCodec.encode(stripGrpcFrame(listReq));
        channel.writeInbound(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(frame), true));

        // Expect: HEADERS + list response + trailing HEADERS
        assertThat("should have 3 outbound frames",
            outbound.size(), is(3));

        Http2DataFrame listResp = (Http2DataFrame) outbound.get(1);
        byte[] listRespBytes = extractDataBytes(listResp);
        List<String> serviceNames = parseListServicesFromGrpcFrame(listRespBytes);
        assertThat("list_services should work over v1alpha path",
            serviceNames, hasItem("com.example.grpc.GreetingService"));

        Http2HeadersFrame trailing = (Http2HeadersFrame) outbound.get(2);
        assertThat("trailing grpc-status should be 0",
            trailing.headers().get(GrpcStatusMapper.GRPC_STATUS_HEADER).toString(), is("0"));

        releaseDataFrames(outbound);
        channel.finishAndReleaseAll();
    }

    // ---- (c) Router routes reflection path to GrpcBidiReflectionHandler ----

    /**
     * Drives a reflection-path HEADERS through {@link GrpcBidiRouterHandler} and asserts
     * that the resulting pipeline contains {@link GrpcBidiReflectionHandler} (NOT
     * {@link GrpcBidiStreamHandler} and NOT the re-aggregating chain).
     */
    @Test
    public void shouldRouteReflectionPathToBidiReflectionHandler() {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        Configuration config = configuration();
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
            grpcRespHandler, grpcReqHandler, reqHandler,
            httpState
        );

        EmbeddedChannel channel = new EmbeddedChannel(router);

        // Feed HEADERS for v1 reflection path
        DefaultHttp2Headers h2Headers = new DefaultHttp2Headers();
        h2Headers.method("POST");
        h2Headers.path(GrpcServerReflectionHandler.REFLECTION_V1_PATH);
        h2Headers.set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        channel.writeInbound(new DefaultHttp2HeadersFrame(h2Headers, false));

        // After routing: pipeline should contain GrpcBidiReflectionHandler
        assertThat("pipeline should contain GrpcBidiReflectionHandler",
            channel.pipeline().get(GrpcBidiReflectionHandler.class), is(notNullValue()));
        assertThat("pipeline should NOT contain GrpcBidiStreamHandler",
            channel.pipeline().get(GrpcBidiStreamHandler.class), is(nullValue()));
        assertThat("pipeline should NOT contain GrpcBidiRouterHandler (replaced)",
            channel.pipeline().get(GrpcBidiRouterHandler.class), is(nullValue()));
        assertThat("pipeline should NOT contain re-aggregating chain",
            channel.pipeline().get(Http2StreamFrameToHttpObjectCodec.class), is(nullValue()));

        channel.finishAndReleaseAll();
    }

    /**
     * Confirms that a non-reflection gRPC path is NOT routed to the reflection handler
     * (router falls through to normal method resolution / re-aggregating chain).
     */
    @Test
    public void shouldNotRouteNonReflectionPathToReflectionHandler() {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        Configuration config = configuration();
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
            grpcRespHandler, grpcReqHandler, reqHandler,
            httpState
        );

        EmbeddedChannel channel = new EmbeddedChannel(router);

        // Feed HEADERS for a normal unary method
        DefaultHttp2Headers h2Headers = new DefaultHttp2Headers();
        h2Headers.method("POST");
        h2Headers.path("/com.example.grpc.GreetingService/Greeting");
        h2Headers.set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        channel.writeInbound(new DefaultHttp2HeadersFrame(h2Headers, false));

        // Should use re-aggregating chain, not reflection handler
        assertThat("pipeline should NOT contain GrpcBidiReflectionHandler",
            channel.pipeline().get(GrpcBidiReflectionHandler.class), is(nullValue()));
        assertThat("pipeline should contain re-aggregating chain",
            channel.pipeline().get(Http2StreamFrameToHttpObjectCodec.class), is(notNullValue()));

        channel.finishAndReleaseAll();
    }

    // ---- (d) HEADERS-only stream (endStream on HEADERS) ----

    @Test
    public void shouldFinishOnHeadersOnlyStream() {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        GrpcServerReflectionHandler reflectionHandler = new GrpcServerReflectionHandler(store);

        List<Object> outbound = new ArrayList<>();
        FrameCaptureHandler captureHandler = new FrameCaptureHandler(outbound);

        EmbeddedChannel channel = new EmbeddedChannel(
            captureHandler,
            new GrpcBidiReflectionHandler(reflectionHandler)
        );

        // Feed HEADERS with endStream=true (no DATA frames)
        DefaultHttp2Headers reqHeaders = new DefaultHttp2Headers();
        reqHeaders.method("POST");
        reqHeaders.path(GrpcServerReflectionHandler.REFLECTION_V1_PATH);
        reqHeaders.set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        channel.writeInbound(new DefaultHttp2HeadersFrame(reqHeaders, true));

        // Expect: initial HEADERS + trailing HEADERS
        assertThat("should have 2 outbound frames (HEADERS + trailing HEADERS)",
            outbound.size(), is(2));
        assertThat("first should be initial HEADERS",
            outbound.get(0), instanceOf(Http2HeadersFrame.class));
        assertThat("second should be trailing HEADERS with grpc-status=0",
            outbound.get(1), instanceOf(Http2HeadersFrame.class));
        Http2HeadersFrame trailing = (Http2HeadersFrame) outbound.get(1);
        assertThat("trailing should have endStream=true",
            trailing.isEndStream(), is(true));
        assertThat("trailing grpc-status should be 0",
            trailing.headers().get(GrpcStatusMapper.GRPC_STATUS_HEADER).toString(), is("0"));

        channel.finishAndReleaseAll();
    }

    // ---- (e) file_by_filename over bidi ----

    @Test
    public void shouldHandleFileByFilenameOverBidi() throws IOException {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        GrpcServerReflectionHandler reflectionHandler = new GrpcServerReflectionHandler(store);

        List<Object> outbound = new ArrayList<>();
        FrameCaptureHandler captureHandler = new FrameCaptureHandler(outbound);

        EmbeddedChannel channel = new EmbeddedChannel(
            captureHandler,
            new GrpcBidiReflectionHandler(reflectionHandler)
        );

        DefaultHttp2Headers reqHeaders = new DefaultHttp2Headers();
        reqHeaders.method("POST");
        reqHeaders.path(GrpcServerReflectionHandler.REFLECTION_V1_PATH);
        reqHeaders.set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        channel.writeInbound(new DefaultHttp2HeadersFrame(reqHeaders, false));

        // Feed file_by_filename request + END_STREAM
        byte[] fileReq = buildFileByFilenameRequest("greeting.proto");
        byte[] frame = GrpcFrameCodec.encode(stripGrpcFrame(fileReq));
        channel.writeInbound(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(frame), true));

        // Expect: HEADERS + file response + trailing HEADERS
        assertThat("should have 3 outbound frames", outbound.size(), is(3));

        Http2DataFrame fileResp = (Http2DataFrame) outbound.get(1);
        byte[] respBytes = extractDataBytes(fileResp);
        List<byte[]> fdProtos = parseFileDescriptorFromGrpcFrame(respBytes);
        assertThat("should return file descriptor protos", fdProtos, is(not(empty())));
        DescriptorProtos.FileDescriptorProto fdProto =
            DescriptorProtos.FileDescriptorProto.parseFrom(fdProtos.get(0));
        assertThat("file descriptor name should be greeting.proto",
            fdProto.getName(), is("greeting.proto"));

        releaseDataFrames(outbound);
        channel.finishAndReleaseAll();
    }

    // ---- (f) Multiple requests in single DATA frame ----

    @Test
    public void shouldHandleMultipleRequestsInSingleDataFrame() throws IOException {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        GrpcServerReflectionHandler reflectionHandler = new GrpcServerReflectionHandler(store);

        List<Object> outbound = new ArrayList<>();
        FrameCaptureHandler captureHandler = new FrameCaptureHandler(outbound);

        EmbeddedChannel channel = new EmbeddedChannel(
            captureHandler,
            new GrpcBidiReflectionHandler(reflectionHandler)
        );

        DefaultHttp2Headers reqHeaders = new DefaultHttp2Headers();
        reqHeaders.method("POST");
        reqHeaders.path(GrpcServerReflectionHandler.REFLECTION_V1_PATH);
        reqHeaders.set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        channel.writeInbound(new DefaultHttp2HeadersFrame(reqHeaders, false));

        // Build two gRPC-framed requests concatenated in a single byte array
        byte[] req1Proto = stripGrpcFrame(buildListServicesRequest(""));
        byte[] req2Proto = stripGrpcFrame(buildFileContainingSymbolRequest("com.example.grpc.GreetingService"));
        byte[] frame1 = GrpcFrameCodec.encode(req1Proto);
        byte[] frame2 = GrpcFrameCodec.encode(req2Proto);

        // Concatenate both frames into a single DATA frame
        byte[] combined = new byte[frame1.length + frame2.length];
        System.arraycopy(frame1, 0, combined, 0, frame1.length);
        System.arraycopy(frame2, 0, combined, frame1.length, frame2.length);

        channel.writeInbound(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(combined), true));

        // Expect: HEADERS + 2 response DATA + trailing HEADERS = 4
        assertThat("should have 4 outbound frames",
            outbound.size(), is(4));

        // Verify first response (list_services)
        Http2DataFrame resp1 = (Http2DataFrame) outbound.get(1);
        byte[] resp1Bytes = extractDataBytes(resp1);
        List<String> names = parseListServicesFromGrpcFrame(resp1Bytes);
        assertThat("first response should be list_services",
            names, hasItem("com.example.grpc.GreetingService"));

        // Verify second response (file_containing_symbol)
        Http2DataFrame resp2 = (Http2DataFrame) outbound.get(2);
        byte[] resp2Bytes = extractDataBytes(resp2);
        List<byte[]> fdProtos = parseFileDescriptorFromGrpcFrame(resp2Bytes);
        assertThat("second response should contain file descriptor",
            fdProtos, is(not(empty())));

        // Trailing HEADERS
        Http2HeadersFrame trailing = (Http2HeadersFrame) outbound.get(3);
        assertThat("trailing grpc-status should be 0",
            trailing.headers().get(GrpcStatusMapper.GRPC_STATUS_HEADER).toString(), is("0"));

        releaseDataFrames(outbound);
        channel.finishAndReleaseAll();
    }

    // ---- (g) Router routes v1alpha reflection to bidi handler ----

    @Test
    public void shouldRouteV1AlphaReflectionPathToBidiReflectionHandler() {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        Configuration config = configuration();
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
            grpcRespHandler, grpcReqHandler, reqHandler,
            httpState
        );

        EmbeddedChannel channel = new EmbeddedChannel(router);

        DefaultHttp2Headers h2Headers = new DefaultHttp2Headers();
        h2Headers.method("POST");
        h2Headers.path(GrpcServerReflectionHandler.REFLECTION_V1ALPHA_PATH);
        h2Headers.set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        channel.writeInbound(new DefaultHttp2HeadersFrame(h2Headers, false));

        assertThat("pipeline should contain GrpcBidiReflectionHandler for v1alpha",
            channel.pipeline().get(GrpcBidiReflectionHandler.class), is(notNullValue()));
        assertThat("pipeline should NOT contain GrpcBidiRouterHandler",
            channel.pipeline().get(GrpcBidiRouterHandler.class), is(nullValue()));

        channel.finishAndReleaseAll();
    }

    // ---- (h) No descriptor store -> reflection not routed (falls through) ----

    @Test
    public void shouldNotRouteReflectionWhenNoDescriptorStore() {
        Configuration config = configuration();
        MockServerLogger logger = new MockServerLogger();
        HttpState httpState = new HttpState(config, logger, mock(Scheduler.class));

        CallbackWebSocketServerHandler wsHandler = new CallbackWebSocketServerHandler(httpState);
        DashboardWebSocketHandler dashHandler = new DashboardWebSocketHandler(httpState, false, false);
        TraceContextHandler traceHandler = new TraceContextHandler(config);
        HttpRequestHandler reqHandler = new HttpRequestHandler(
            config, mock(org.mockserver.lifecycle.LifeCycle.class), httpState,
            mock(org.mockserver.mock.action.http.HttpActionHandler.class)
        );

        // No descriptor store -> no reflection handler
        GrpcBidiRouterHandler router = new GrpcBidiRouterHandler(
            config, null, logger, false, null,
            wsHandler, dashHandler, null, traceHandler, null,
            null, null, reqHandler,
            httpState
        );

        EmbeddedChannel channel = new EmbeddedChannel(router);

        DefaultHttp2Headers h2Headers = new DefaultHttp2Headers();
        h2Headers.method("POST");
        h2Headers.path(GrpcServerReflectionHandler.REFLECTION_V1_PATH);
        h2Headers.set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        channel.writeInbound(new DefaultHttp2HeadersFrame(h2Headers, false));

        // Without a descriptor store, reflection should not be routed to bidi handler
        assertThat("pipeline should NOT contain GrpcBidiReflectionHandler",
            channel.pipeline().get(GrpcBidiReflectionHandler.class), is(nullValue()));

        channel.finishAndReleaseAll();
    }

    // ---- Helper methods to build gRPC-framed ServerReflectionRequest messages ----

    private static byte[] buildListServicesRequest(String host) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CodedOutputStream cos = CodedOutputStream.newInstance(baos);
        if (host != null && !host.isEmpty()) {
            cos.writeString(GrpcServerReflectionHandler.REQ_HOST_FIELD, host);
        }
        cos.writeString(GrpcServerReflectionHandler.REQ_LIST_SERVICES_FIELD, "*");
        cos.flush();
        return GrpcServerReflectionHandler.grpcFrame(baos.toByteArray());
    }

    private static byte[] buildFileContainingSymbolRequest(String symbol) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CodedOutputStream cos = CodedOutputStream.newInstance(baos);
        cos.writeString(GrpcServerReflectionHandler.REQ_FILE_CONTAINING_SYMBOL_FIELD, symbol);
        cos.flush();
        return GrpcServerReflectionHandler.grpcFrame(baos.toByteArray());
    }

    private static byte[] buildFileByFilenameRequest(String filename) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CodedOutputStream cos = CodedOutputStream.newInstance(baos);
        cos.writeString(GrpcServerReflectionHandler.REQ_FILE_BY_FILENAME_FIELD, filename);
        cos.flush();
        return GrpcServerReflectionHandler.grpcFrame(baos.toByteArray());
    }

    // ---- Helper methods to parse gRPC-framed ServerReflectionResponse ----

    private static List<String> parseListServicesFromGrpcFrame(byte[] grpcFramedBytes) throws IOException {
        // The DATA frame content is a gRPC frame; strip the 5-byte header to get the proto
        byte[] proto = stripGrpcFrameFromData(grpcFramedBytes);
        CodedInputStream cis = CodedInputStream.newInstance(proto);
        List<String> serviceNames = new ArrayList<>();

        while (!cis.isAtEnd()) {
            int tag = cis.readTag();
            int fieldNumber = WireFormat.getTagFieldNumber(tag);
            if (fieldNumber == GrpcServerReflectionHandler.RESP_LIST_SERVICES_RESPONSE_FIELD) {
                byte[] listServiceBytes = cis.readByteArray();
                CodedInputStream inner = CodedInputStream.newInstance(listServiceBytes);
                while (!inner.isAtEnd()) {
                    int innerTag = inner.readTag();
                    int innerField = WireFormat.getTagFieldNumber(innerTag);
                    if (innerField == GrpcServerReflectionHandler.LSR_SERVICE_FIELD) {
                        byte[] serviceBytes = inner.readByteArray();
                        CodedInputStream svcCis = CodedInputStream.newInstance(serviceBytes);
                        while (!svcCis.isAtEnd()) {
                            int svcTag = svcCis.readTag();
                            if (WireFormat.getTagFieldNumber(svcTag) == GrpcServerReflectionHandler.SR_NAME_FIELD) {
                                serviceNames.add(svcCis.readString());
                            } else {
                                svcCis.skipField(svcTag);
                            }
                        }
                    } else {
                        inner.skipField(innerTag);
                    }
                }
            } else {
                cis.skipField(tag);
            }
        }
        return serviceNames;
    }

    private static List<byte[]> parseFileDescriptorFromGrpcFrame(byte[] grpcFramedBytes) throws IOException {
        byte[] proto = stripGrpcFrameFromData(grpcFramedBytes);
        CodedInputStream cis = CodedInputStream.newInstance(proto);
        List<byte[]> fdProtos = new ArrayList<>();

        while (!cis.isAtEnd()) {
            int tag = cis.readTag();
            int fieldNumber = WireFormat.getTagFieldNumber(tag);
            if (fieldNumber == GrpcServerReflectionHandler.RESP_FILE_DESCRIPTOR_RESPONSE_FIELD) {
                byte[] fdrBytes = cis.readByteArray();
                CodedInputStream inner = CodedInputStream.newInstance(fdrBytes);
                while (!inner.isAtEnd()) {
                    int innerTag = inner.readTag();
                    if (WireFormat.getTagFieldNumber(innerTag) == GrpcServerReflectionHandler.FDR_FILE_DESCRIPTOR_PROTO_FIELD) {
                        fdProtos.add(inner.readByteArray());
                    } else {
                        inner.skipField(innerTag);
                    }
                }
            } else {
                cis.skipField(tag);
            }
        }
        return fdProtos;
    }

    /**
     * Strips the gRPC frame header from a gRPC-framed request built by the
     * build*Request helpers (which use GrpcServerReflectionHandler.grpcFrame).
     */
    private static byte[] stripGrpcFrame(byte[] grpcFramed) {
        byte[] proto = new byte[grpcFramed.length - 5];
        System.arraycopy(grpcFramed, 5, proto, 0, proto.length);
        return proto;
    }

    /**
     * Strips the gRPC frame header from DATA frame bytes captured by the outbound handler.
     */
    private static byte[] stripGrpcFrameFromData(byte[] data) {
        // The DATA frame content is a complete gRPC frame (5-byte header + proto)
        List<byte[]> decoded = GrpcFrameCodec.decode(data);
        if (decoded.isEmpty()) {
            return new byte[0];
        }
        return decoded.get(0);
    }

    private static byte[] extractDataBytes(Http2DataFrame dataFrame) {
        byte[] bytes = new byte[dataFrame.content().readableBytes()];
        dataFrame.content().readBytes(bytes);
        return bytes;
    }

    private static void releaseDataFrames(List<Object> outbound) {
        for (Object frame : outbound) {
            if (frame instanceof Http2DataFrame) {
                ((Http2DataFrame) frame).release();
            }
        }
    }

    // ---- Capture helper (same pattern as GrpcBidiInterleavingMultiplexTest) ----

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
