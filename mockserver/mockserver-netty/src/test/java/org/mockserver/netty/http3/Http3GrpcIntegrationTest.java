package org.mockserver.netty.http3;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.http3.Http3ClientConnectionHandler;
import io.netty.handler.codec.http3.Http3DataFrame;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import io.netty.handler.codec.http3.Http3RequestStreamInboundHandler;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.grpc.GrpcFrameCodec;
import org.mockserver.grpc.GrpcJsonMessageConverter;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.netty.MockServer;

import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.JsonBody.json;

/**
 * Integration tests for gRPC over HTTP/3.
 * <p>
 * These tests use an in-JVM Netty QUIC client to send raw gRPC-framed requests
 * over HTTP/3 and verify that MockServer correctly:
 * <ul>
 *   <li>Decodes the gRPC length-prefixed message</li>
 *   <li>Converts protobuf to JSON for expectation matching</li>
 *   <li>Encodes the matched response back to gRPC framing</li>
 *   <li>Sends grpc-status in a trailing HEADERS frame (not initial headers)</li>
 * </ul>
 * <p>
 * The tests require the native QUIC transport (BoringSSL) and skip gracefully on
 * platforms where it is not available.
 */
@SuppressWarnings("deprecation") // NioEventLoopGroup deprecation in Netty 4.2
public class Http3GrpcIntegrationTest {

    // path to the test .dsc file, relative to mockserver-netty module
    private static final Path DESCRIPTOR_DIR = Paths.get("../mockserver-core/src/test/resources/grpc").toAbsolutePath();

    private MockServer mockServer;
    private MockServerClient mockServerClient;
    private NioEventLoopGroup clientGroup;
    private GrpcProtoDescriptorStore descriptorStore;
    private GrpcJsonMessageConverter converter;

    @Before
    public void setUp() {
        assumeQuicAvailable();

        // load the descriptor store for building protobuf test messages
        descriptorStore = new GrpcProtoDescriptorStore(new MockServerLogger());
        descriptorStore.loadDescriptorSetFromPath(DESCRIPTOR_DIR.resolve("greeting.dsc"));
        converter = descriptorStore.getConverter();
    }

    @After
    public void tearDown() {
        if (mockServerClient != null) {
            mockServerClient.close();
            mockServerClient = null;
        }
        if (mockServer != null) {
            mockServer.stop();
            mockServer = null;
        }
        if (clientGroup != null) {
            clientGroup.shutdownGracefully();
            clientGroup = null;
        }
    }

    /**
     * Unary gRPC call over HTTP/3: send a HelloRequest, receive a HelloResponse
     * with correct gRPC framing and grpc-status=0 in trailing HEADERS.
     */
    @Test
    public void shouldHandleUnaryGrpcCallOverHttp3() throws Exception {
        // given -- start MockServer with H3 and gRPC descriptors
        int http3Port = startMockServerWithGrpc();

        // set up a gRPC unary expectation: match on the JSON-decoded body.
        // Use json() matcher because the protobuf-to-JSON printer produces
        // pretty-printed output (with whitespace/newlines).
        mockServerClient.when(
            request()
                .withMethod("POST")
                .withPath("/com.example.grpc.GreetingService/Greeting")
                .withBody(json("{\"name\":\"World\"}"))
        ).respond(
            response()
                .withStatusCode(200)
                .withBody("{\"greeting\":\"Hello World\"}")
        );

        // build the gRPC request: protobuf-encode + gRPC-frame the HelloRequest
        byte[] protobufRequest = converter.toProtobuf(
            "{\"name\":\"World\"}",
            descriptorStore.getMethod("com.example.grpc.GreetingService", "Greeting").getInputType()
        );
        byte[] grpcFrame = GrpcFrameCodec.encode(protobufRequest);

        // when -- send gRPC request over HTTP/3
        GrpcH3Response result = sendGrpcOverHttp3(
            http3Port,
            "/com.example.grpc.GreetingService/Greeting",
            grpcFrame
        );

        // then -- verify response
        assertThat("initial headers should have :status=200", result.initialStatus, is("200"));
        assertThat("initial headers should have content-type=application/grpc",
            result.initialHeaders.get("content-type"), is(GrpcStatusMapper.GRPC_CONTENT_TYPE));

        // grpc-status MUST be in trailing HEADERS, NOT in initial HEADERS
        assertThat("initial headers should NOT contain grpc-status",
            result.initialHeaders.containsKey(GrpcStatusMapper.GRPC_STATUS_HEADER), is(false));
        assertThat("trailing headers should contain grpc-status=0",
            result.trailingHeaders.get(GrpcStatusMapper.GRPC_STATUS_HEADER), is("0"));

        // verify the response body is a valid gRPC-framed protobuf
        assertThat("should have response body data", result.bodyBytes.length, is(greaterThan(0)));
        List<byte[]> decodedMessages = GrpcFrameCodec.decode(result.bodyBytes);
        assertThat("should have exactly one gRPC message", decodedMessages.size(), is(1));

        // decode the protobuf response back to JSON and verify
        String responseJson = converter.toJson(
            decodedMessages.get(0),
            descriptorStore.getMethod("com.example.grpc.GreetingService", "Greeting").getOutputType()
        );
        assertThat("response JSON should contain 'Hello World'",
            responseJson, containsString("Hello World"));
    }

    /**
     * gRPC error over HTTP/3: when the gRPC method is unknown, grpc-status
     * should be UNIMPLEMENTED (12) in trailing HEADERS.
     */
    @Test
    public void shouldReturnGrpcErrorForUnknownMethodOverHttp3() throws Exception {
        // given
        int http3Port = startMockServerWithGrpc();

        // build a minimal gRPC request body
        byte[] protobufRequest = converter.toProtobuf(
            "{\"name\":\"test\"}",
            descriptorStore.getMethod("com.example.grpc.GreetingService", "Greeting").getInputType()
        );
        byte[] grpcFrame = GrpcFrameCodec.encode(protobufRequest);

        // when -- send to a non-existent method
        GrpcH3Response result = sendGrpcOverHttp3(
            http3Port,
            "/com.example.grpc.NonExistentService/DoSomething",
            grpcFrame
        );

        // then -- should get a trailers-only error response
        assertThat("should have :status=200", result.initialStatus, is("200"));

        // for trailers-only, grpc-status is in the initial (and only) HEADERS frame
        String grpcStatus = result.trailingHeaders.get(GrpcStatusMapper.GRPC_STATUS_HEADER);
        if (grpcStatus == null) {
            grpcStatus = result.initialHeaders.get(GrpcStatusMapper.GRPC_STATUS_HEADER);
        }
        assertThat("grpc-status should be UNIMPLEMENTED (12)",
            grpcStatus, is(String.valueOf(GrpcStatusMapper.GrpcStatusCode.UNIMPLEMENTED.getCode())));
    }

    /**
     * gRPC request without descriptors: when no .dsc files are loaded, the
     * request body stays as gRPC-framed binary and can match raw binary
     * expectations, and grpc-status is sent in trailing HEADERS.
     */
    @Test
    public void shouldHandleGrpcWithoutDescriptorsOverHttp3() throws Exception {
        // given -- start MockServer with H3 but WITHOUT gRPC descriptors
        int udpPort = findAvailableUdpPort();
        Configuration config = configuration()
            .http3Port(udpPort)
            .attemptToProxyIfNoMatchingExpectation(false);

        mockServer = new MockServer(config, 0);
        int http3Port = mockServer.getHttp3Port();
        Assume.assumeTrue("HTTP/3 server did not start", http3Port > 0);

        mockServerClient = new MockServerClient("127.0.0.1", mockServer.getLocalPort());

        // set up an expectation matching the path (body is raw gRPC binary)
        mockServerClient.when(
            request()
                .withMethod("POST")
                .withPath("/some.Service/SomeMethod")
        ).respond(
            response()
                .withStatusCode(200)
                .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
                .withHeader(GrpcStatusMapper.GRPC_STATUS_HEADER, "0")
        );

        // build a minimal gRPC-framed body (raw bytes, no protobuf needed)
        byte[] grpcFrame = GrpcFrameCodec.encode("test-data".getBytes());

        // when
        GrpcH3Response result = sendGrpcOverHttp3(
            http3Port,
            "/some.Service/SomeMethod",
            grpcFrame
        );

        // then
        assertThat("initial headers should have :status=200", result.initialStatus, is("200"));

        // grpc-status should be in trailing headers (or initial for trailers-only)
        String grpcStatus = result.trailingHeaders.get(GrpcStatusMapper.GRPC_STATUS_HEADER);
        if (grpcStatus == null) {
            grpcStatus = result.initialHeaders.get(GrpcStatusMapper.GRPC_STATUS_HEADER);
        }
        assertThat("grpc-status should be 0 (OK)", grpcStatus, is("0"));
    }

    /**
     * Verify that non-gRPC requests over HTTP/3 are NOT affected by the gRPC
     * detection path -- a normal JSON request still works as before.
     */
    @Test
    public void shouldNotAffectNonGrpcRequestsOverHttp3() throws Exception {
        // given
        int http3Port = startMockServerWithGrpc();

        mockServerClient.when(
            request()
                .withMethod("GET")
                .withPath("/api/hello")
        ).respond(
            response()
                .withStatusCode(200)
                .withBody("hello-world")
        );

        // when -- send a normal HTTP/3 request (not gRPC)
        Http3ResponseCapture result = sendNonGrpcHttp3Request(http3Port, "GET", "/api/hello");

        // then
        assertThat("status should be 200", result.status, is("200"));
        assertThat("body should be 'hello-world'", result.body, is("hello-world"));
        assertThat("should NOT have grpc-status in headers",
            result.headers.containsKey(GrpcStatusMapper.GRPC_STATUS_HEADER), is(false));
    }

    // ---- helper methods ----

    /**
     * Start a MockServer with HTTP/3 enabled and gRPC descriptors loaded.
     * Returns the HTTP/3 port.
     */
    private int startMockServerWithGrpc() {
        int udpPort = findAvailableUdpPort();
        Configuration config = configuration()
            .http3Port(udpPort)
            .grpcDescriptorDirectory(DESCRIPTOR_DIR.toString())
            .attemptToProxyIfNoMatchingExpectation(false);

        mockServer = new MockServer(config, 0);
        int http3Port = mockServer.getHttp3Port();
        Assume.assumeTrue("HTTP/3 server did not start", http3Port > 0);

        mockServerClient = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
        return http3Port;
    }

    /**
     * Holds the response from a gRPC-over-HTTP/3 call, with initial headers,
     * trailing headers, and body bytes captured separately.
     */
    static class GrpcH3Response {
        final String initialStatus;
        final Map<String, String> initialHeaders;
        final Map<String, String> trailingHeaders;
        final byte[] bodyBytes;

        GrpcH3Response(
            String initialStatus,
            Map<String, String> initialHeaders,
            Map<String, String> trailingHeaders,
            byte[] bodyBytes
        ) {
            this.initialStatus = initialStatus;
            this.initialHeaders = initialHeaders;
            this.trailingHeaders = trailingHeaders;
            this.bodyBytes = bodyBytes;
        }
    }

    /**
     * Send a raw gRPC-framed request over HTTP/3 and capture the response,
     * distinguishing between initial and trailing HEADERS frames.
     */
    private GrpcH3Response sendGrpcOverHttp3(int port, String path, byte[] grpcBody) throws Exception {
        if (clientGroup != null) {
            clientGroup.shutdownGracefully().sync();
        }
        clientGroup = new NioEventLoopGroup(1);

        QuicSslContext clientSslContext = QuicSslContextBuilder.forClient()
            .trustManager(trustAllManager())
            .applicationProtocols(Http3.supportedApplicationProtocols())
            .build();

        Channel clientChannel = new Bootstrap()
            .group(clientGroup)
            .channel(NioDatagramChannel.class)
            .handler(Http3.newQuicClientCodecBuilder()
                .sslContext(clientSslContext)
                .maxIdleTimeout(5000, TimeUnit.MILLISECONDS)
                .initialMaxData(10000000)
                .initialMaxStreamDataBidirectionalLocal(1000000)
                .initialMaxStreamsBidirectional(100)
                .build())
            .bind(0)
            .sync()
            .channel();

        QuicChannel quicChannel = QuicChannel.newBootstrap(clientChannel)
            .handler(new Http3ClientConnectionHandler())
            .remoteAddress(new InetSocketAddress("127.0.0.1", port))
            .connect()
            .get(5, TimeUnit.SECONDS);

        // Track multiple HEADERS frames to distinguish initial vs trailing
        List<Http3HeadersFrame> headerFrames = new ArrayList<>();
        BlockingQueue<byte[]> bodyQueue = new LinkedBlockingQueue<>();
        BlockingQueue<Boolean> doneQueue = new LinkedBlockingQueue<>();
        ByteArrayOutputStream bodyAccumulator = new ByteArrayOutputStream();

        QuicStreamChannel requestStream = Http3.newRequestStream(
            quicChannel,
            new Http3RequestStreamInboundHandler() {
                @Override
                protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame headersFrame) {
                    synchronized (headerFrames) {
                        headerFrames.add(headersFrame);
                    }
                }

                @Override
                protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame dataFrame) {
                    ByteBuf content = dataFrame.content();
                    byte[] bytes = new byte[content.readableBytes()];
                    content.readBytes(bytes);
                    content.release();
                    synchronized (bodyAccumulator) {
                        bodyAccumulator.write(bytes, 0, bytes.length);
                    }
                }

                @Override
                protected void channelInputClosed(ChannelHandlerContext ctx) {
                    synchronized (bodyAccumulator) {
                        bodyQueue.offer(bodyAccumulator.toByteArray());
                    }
                    doneQueue.offer(true);
                    ctx.close();
                }
            }
        ).sync().getNow();

        // send gRPC request: HEADERS + DATA (with SHUTDOWN_OUTPUT)
        DefaultHttp3HeadersFrame requestHeaders = new DefaultHttp3HeadersFrame();
        requestHeaders.headers().method("POST");
        requestHeaders.headers().path(path);
        requestHeaders.headers().scheme("https");
        requestHeaders.headers().authority("127.0.0.1:" + port);
        requestHeaders.headers().add("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        requestHeaders.headers().add("te", "trailers");

        requestStream.write(requestHeaders).sync();
        requestStream.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(grpcBody)))
            .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT)
            .sync();

        // wait for response
        doneQueue.poll(10, TimeUnit.SECONDS);
        byte[] responseBody = bodyQueue.poll(1, TimeUnit.SECONDS);
        if (responseBody == null) {
            responseBody = new byte[0];
        }

        quicChannel.close().sync();
        clientChannel.close().sync();

        // parse the captured HEADERS frames into initial + trailing
        Map<String, String> initialHeaders = new ConcurrentHashMap<>();
        Map<String, String> trailingHeaders = new ConcurrentHashMap<>();
        String initialStatus = "null";

        synchronized (headerFrames) {
            if (!headerFrames.isEmpty()) {
                // first HEADERS frame is the initial response
                Http3HeadersFrame first = headerFrames.get(0);
                CharSequence status = first.headers().status();
                initialStatus = status != null ? status.toString() : "null";
                first.headers().forEach(entry -> {
                    String name = entry.getKey().toString();
                    if (!name.startsWith(":")) {
                        initialHeaders.put(name, entry.getValue().toString());
                    }
                });
            }
            if (headerFrames.size() > 1) {
                // second HEADERS frame is the trailing headers
                Http3HeadersFrame trailing = headerFrames.get(1);
                trailing.headers().forEach(entry -> {
                    String name = entry.getKey().toString();
                    if (!name.startsWith(":")) {
                        trailingHeaders.put(name, entry.getValue().toString());
                    }
                });
            }
        }

        return new GrpcH3Response(initialStatus, initialHeaders, trailingHeaders, responseBody);
    }

    /**
     * Response capture for non-gRPC HTTP/3 requests.
     */
    static class Http3ResponseCapture {
        final String status;
        final String body;
        final Map<String, String> headers;

        Http3ResponseCapture(String status, String body, Map<String, String> headers) {
            this.status = status;
            this.body = body;
            this.headers = headers;
        }
    }

    /**
     * Send a non-gRPC HTTP/3 request and capture the response.
     */
    private Http3ResponseCapture sendNonGrpcHttp3Request(int port, String method, String path) throws Exception {
        if (clientGroup != null) {
            clientGroup.shutdownGracefully().sync();
        }
        clientGroup = new NioEventLoopGroup(1);

        QuicSslContext clientSslContext = QuicSslContextBuilder.forClient()
            .trustManager(trustAllManager())
            .applicationProtocols(Http3.supportedApplicationProtocols())
            .build();

        Channel clientChannel = new Bootstrap()
            .group(clientGroup)
            .channel(NioDatagramChannel.class)
            .handler(Http3.newQuicClientCodecBuilder()
                .sslContext(clientSslContext)
                .maxIdleTimeout(5000, TimeUnit.MILLISECONDS)
                .initialMaxData(10000000)
                .initialMaxStreamDataBidirectionalLocal(1000000)
                .initialMaxStreamsBidirectional(100)
                .build())
            .bind(0)
            .sync()
            .channel();

        QuicChannel quicChannel = QuicChannel.newBootstrap(clientChannel)
            .handler(new Http3ClientConnectionHandler())
            .remoteAddress(new InetSocketAddress("127.0.0.1", port))
            .connect()
            .get(5, TimeUnit.SECONDS);

        BlockingQueue<String> statusQueue = new LinkedBlockingQueue<>();
        BlockingQueue<String> bodyQueue = new LinkedBlockingQueue<>();
        Map<String, String> responseHeaders = new ConcurrentHashMap<>();

        QuicStreamChannel requestStream = Http3.newRequestStream(
            quicChannel,
            new Http3RequestStreamInboundHandler() {
                private final StringBuilder bodyBuilder = new StringBuilder();

                @Override
                protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame headersFrame) {
                    CharSequence status = headersFrame.headers().status();
                    statusQueue.offer(status != null ? status.toString() : "null");
                    headersFrame.headers().forEach(entry -> {
                        String name = entry.getKey().toString();
                        if (!name.startsWith(":")) {
                            responseHeaders.put(name, entry.getValue().toString());
                        }
                    });
                }

                @Override
                protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame dataFrame) {
                    ByteBuf content = dataFrame.content();
                    bodyBuilder.append(content.toString(java.nio.charset.StandardCharsets.UTF_8));
                    content.release();
                }

                @Override
                protected void channelInputClosed(ChannelHandlerContext ctx) {
                    bodyQueue.offer(bodyBuilder.toString());
                    ctx.close();
                }
            }
        ).sync().getNow();

        DefaultHttp3HeadersFrame requestHeaders = new DefaultHttp3HeadersFrame();
        requestHeaders.headers().method(method);
        requestHeaders.headers().path(path);
        requestHeaders.headers().scheme("https");
        requestHeaders.headers().authority("127.0.0.1:" + port);

        requestStream.writeAndFlush(requestHeaders)
            .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT)
            .sync();

        String status = statusQueue.poll(5, TimeUnit.SECONDS);
        String responseBody = bodyQueue.poll(5, TimeUnit.SECONDS);

        quicChannel.close().sync();
        clientChannel.close().sync();

        return new Http3ResponseCapture(
            status != null ? status : "null",
            responseBody != null ? responseBody : "",
            responseHeaders
        );
    }

    private static int findAvailableUdpPort() {
        try (java.net.DatagramSocket socket = new java.net.DatagramSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            return 0;
        }
    }

    private static void assumeQuicAvailable() {
        try {
            boolean available = io.netty.handler.codec.quic.Quic.isAvailable();
            Assume.assumeTrue(
                "native QUIC transport not available on this platform -- skipping HTTP/3 gRPC test",
                available
            );
        } catch (Throwable t) {
            Assume.assumeNoException(
                "native QUIC transport failed to load -- skipping HTTP/3 gRPC test",
                t
            );
        }
    }

    @SuppressWarnings("TrustAllX509TrustManager")
    private static TrustManager trustAllManager() {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
            }

            @Override
            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                return new java.security.cert.X509Certificate[0];
            }
        };
    }
}
