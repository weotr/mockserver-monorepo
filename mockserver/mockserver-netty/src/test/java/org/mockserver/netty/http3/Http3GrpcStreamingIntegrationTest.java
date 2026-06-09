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
import org.mockserver.model.GrpcBidiResponse;
import org.mockserver.model.GrpcBidiRule;
import org.mockserver.model.GrpcStreamResponse;
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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

/**
 * Integration tests for gRPC <strong>streaming</strong> over HTTP/3 -- the two cases
 * that were previously deferred (G16-FOLLOW-UP-5):
 * <ul>
 *   <li><strong>server-streaming</strong> ({@code rpc ListGreetings (HelloRequest) returns
 *       (stream HelloResponse)}): a unary request yields multiple response DATA frames followed
 *       by a trailing HEADERS frame with grpc-status;</li>
 *   <li><strong>bidi-streaming</strong> ({@code rpc Chat (stream HelloRequest) returns
 *       (stream HelloResponse)}): multiple request DATA frames are matched against
 *       {@link GrpcBidiRule}s and produce interleaved response DATA frames, then a trailing
 *       HEADERS frame on FIN.</li>
 * </ul>
 * These use an in-JVM Netty QUIC client and skip gracefully when native QUIC is unavailable.
 */
@SuppressWarnings("deprecation") // NioEventLoopGroup deprecation in Netty 4.2
public class Http3GrpcStreamingIntegrationTest {

    private static final Path DESCRIPTOR_DIR = Paths.get("../mockserver-core/src/test/resources/grpc").toAbsolutePath();
    private static final String SERVICE = "com.example.grpc.GreetingService";

    private MockServer mockServer;
    private MockServerClient mockServerClient;
    private NioEventLoopGroup clientGroup;
    private GrpcProtoDescriptorStore descriptorStore;
    private GrpcJsonMessageConverter converter;

    @Before
    public void setUp() {
        assumeQuicAvailable();
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
     * Server-streaming: a single unary request produces three response messages (one with a
     * per-message delay) framed as separate DATA frames, then grpc-status=0 in trailing HEADERS.
     */
    @Test
    public void shouldHandleServerStreamingGrpcOverHttp3() throws Exception {
        int http3Port = startMockServer(configuration()
            .grpcDescriptorDirectory(DESCRIPTOR_DIR.toString())
            .attemptToProxyIfNoMatchingExpectation(false));

        mockServerClient.when(
            request().withMethod("POST").withPath("/" + SERVICE + "/ListGreetings")
        ).respondWithGrpcStream(
            GrpcStreamResponse.grpcStreamResponse()
                .withStatusName("OK")
                .withMessage("{\"greeting\": \"Hello 1\"}")
                .withMessage("{\"greeting\": \"Hello 2\"}", org.mockserver.model.Delay.milliseconds(20))
                .withMessage("{\"greeting\": \"Hello 3\"}")
        );

        byte[] grpcFrame = GrpcFrameCodec.encode(converter.toProtobuf(
            "{\"name\":\"World\"}",
            descriptorStore.getMethod(SERVICE, "ListGreetings").getInputType()
        ));

        GrpcH3Response result = sendGrpcOverHttp3(http3Port, "/" + SERVICE + "/ListGreetings",
            java.util.Collections.singletonList(grpcFrame));

        assertThat("initial :status should be 200", result.initialStatus, is("200"));
        assertThat("initial content-type should be application/grpc",
            result.initialHeaders.get("content-type"), is(GrpcStatusMapper.GRPC_CONTENT_TYPE));
        assertThat("grpc-status must NOT be in initial headers",
            result.initialHeaders.containsKey(GrpcStatusMapper.GRPC_STATUS_HEADER), is(false));
        assertThat("trailing grpc-status should be 0",
            result.trailingHeaders.get(GrpcStatusMapper.GRPC_STATUS_HEADER), is("0"));

        List<byte[]> messages = GrpcFrameCodec.decode(result.bodyBytes);
        assertThat("should receive three streamed messages", messages.size(), is(3));
        assertThat(decode(messages.get(0), "ListGreetings"), containsString("Hello 1"));
        assertThat(decode(messages.get(1), "ListGreetings"), containsString("Hello 2"));
        assertThat(decode(messages.get(2), "ListGreetings"), containsString("Hello 3"));
    }

    /**
     * Bidi-streaming: two inbound request messages are matched against rules and produce two
     * response messages; grpc-status=0 is sent in trailing HEADERS after FIN.
     */
    @Test
    public void shouldHandleBidiStreamingGrpcOverHttp3() throws Exception {
        int http3Port = startMockServer(configuration()
            .grpcDescriptorDirectory(DESCRIPTOR_DIR.toString())
            .grpcBidiStreamingEnabled(true)
            .attemptToProxyIfNoMatchingExpectation(false));

        mockServerClient.when(
            request().withMethod("POST").withPath("/" + SERVICE + "/Chat")
        ).respondWithGrpcBidi(
            GrpcBidiResponse.grpcBidiResponse()
                .withStatusName("OK")
                .withRule(GrpcBidiRule.grpcBidiRule(".*Alice.*").withResponse("{\"greeting\": \"Hello Alice\"}"))
                .withRule(GrpcBidiRule.grpcBidiRule(".*Bob.*").withResponse("{\"greeting\": \"Hello Bob\"}"))
        );

        byte[] alice = GrpcFrameCodec.encode(converter.toProtobuf(
            "{\"name\":\"Alice\"}", descriptorStore.getMethod(SERVICE, "Chat").getInputType()));
        byte[] bob = GrpcFrameCodec.encode(converter.toProtobuf(
            "{\"name\":\"Bob\"}", descriptorStore.getMethod(SERVICE, "Chat").getInputType()));

        GrpcH3Response result = sendGrpcOverHttp3(http3Port, "/" + SERVICE + "/Chat",
            java.util.Arrays.asList(alice, bob));

        assertThat("initial :status should be 200", result.initialStatus, is("200"));
        assertThat("trailing grpc-status should be 0",
            result.trailingHeaders.get(GrpcStatusMapper.GRPC_STATUS_HEADER), is("0"));

        List<byte[]> messages = GrpcFrameCodec.decode(result.bodyBytes);
        assertThat("should receive two rule-driven responses", messages.size(), is(2));
        assertThat(decode(messages.get(0), "Chat"), containsString("Hello Alice"));
        assertThat(decode(messages.get(1), "Chat"), containsString("Hello Bob"));

        mockServerClient.verify(request().withPath("/" + SERVICE + "/Chat"));
    }

    /**
     * When bidi streaming is NOT enabled, a Chat request with a GrpcBidiResponse expectation
     * falls through to the normal pipeline (HttpActionHandler responds 501), confirming the
     * H3 bidi branch is correctly gated by {@code grpcBidiStreamingEnabled}.
     */
    @Test
    public void shouldNotRouteBidiWhenDisabledOverHttp3() throws Exception {
        int http3Port = startMockServer(configuration()
            .grpcDescriptorDirectory(DESCRIPTOR_DIR.toString())
            .attemptToProxyIfNoMatchingExpectation(false));

        mockServerClient.when(
            request().withMethod("POST").withPath("/" + SERVICE + "/Chat")
        ).respondWithGrpcBidi(
            GrpcBidiResponse.grpcBidiResponse()
                .withStatusName("OK")
                .withRule(GrpcBidiRule.grpcBidiRule(".*Alice.*").withResponse("{\"greeting\": \"Hello Alice\"}"))
        );

        byte[] alice = GrpcFrameCodec.encode(converter.toProtobuf(
            "{\"name\":\"Alice\"}", descriptorStore.getMethod(SERVICE, "Chat").getInputType()));

        GrpcH3Response result = sendGrpcOverHttp3(http3Port, "/" + SERVICE + "/Chat",
            java.util.Collections.singletonList(alice));

        // With bidi disabled, the GRPC_BIDI_RESPONSE action falls through to HttpActionHandler
        // (which responds 501); over the gRPC/HTTP3 writer that 501 surfaces as a gRPC error
        // (non-zero grpc-status, no streamed rule responses) -- crucially NOT the bidi rule output.
        String grpcStatus = result.trailingHeaders.get(GrpcStatusMapper.GRPC_STATUS_HEADER);
        if (grpcStatus == null) {
            grpcStatus = result.initialHeaders.get(GrpcStatusMapper.GRPC_STATUS_HEADER);
        }
        assertThat("bidi rule responses must NOT be produced when bidi is disabled",
            result.bodyBytes.length, is(0));
        assertThat("grpc-status should be a non-OK error when bidi is disabled",
            grpcStatus, is(not("0")));
    }

    // ---- helpers ----

    private String decode(byte[] protobuf, String method) {
        return converter.toJson(protobuf, descriptorStore.getMethod(SERVICE, method).getOutputType());
    }

    private int startMockServer(Configuration config) {
        int udpPort = findAvailableUdpPort();
        config.http3Port(udpPort);
        mockServer = new MockServer(config, 0);
        int http3Port = mockServer.getHttp3Port();
        Assume.assumeTrue("HTTP/3 server did not start", http3Port > 0);
        mockServerClient = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
        return http3Port;
    }

    static class GrpcH3Response {
        final String initialStatus;
        final Map<String, String> initialHeaders;
        final Map<String, String> trailingHeaders;
        final byte[] bodyBytes;

        GrpcH3Response(String initialStatus, Map<String, String> initialHeaders,
                       Map<String, String> trailingHeaders, byte[] bodyBytes) {
            this.initialStatus = initialStatus;
            this.initialHeaders = initialHeaders;
            this.trailingHeaders = trailingHeaders;
            this.bodyBytes = bodyBytes;
        }
    }

    /**
     * Send a gRPC request over HTTP/3 as one HEADERS frame followed by the given DATA frames
     * (the last carries FIN), and capture all response DATA bytes plus the initial and trailing
     * HEADERS frames. Works for unary, server-streaming, and (client sends all frames up-front)
     * bidi-streaming.
     */
    private GrpcH3Response sendGrpcOverHttp3(int port, String path, List<byte[]> requestFrames) throws Exception {
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

        List<Http3HeadersFrame> headerFrames = new ArrayList<>();
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
                    doneQueue.offer(true);
                    ctx.close();
                }
            }
        ).sync().getNow();

        DefaultHttp3HeadersFrame requestHeaders = new DefaultHttp3HeadersFrame();
        requestHeaders.headers().method("POST");
        requestHeaders.headers().path(path);
        requestHeaders.headers().scheme("https");
        requestHeaders.headers().authority("127.0.0.1:" + port);
        requestHeaders.headers().add("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        requestHeaders.headers().add("te", "trailers");
        requestStream.write(requestHeaders).sync();

        for (int i = 0; i < requestFrames.size(); i++) {
            DefaultHttp3DataFrame data = new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(requestFrames.get(i)));
            if (i == requestFrames.size() - 1) {
                requestStream.writeAndFlush(data).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT).sync();
            } else {
                requestStream.writeAndFlush(data).sync();
            }
        }

        doneQueue.poll(10, TimeUnit.SECONDS);

        byte[] responseBody;
        synchronized (bodyAccumulator) {
            responseBody = bodyAccumulator.toByteArray();
        }

        quicChannel.close().sync();
        clientChannel.close().sync();

        Map<String, String> initialHeaders = new ConcurrentHashMap<>();
        Map<String, String> trailingHeaders = new ConcurrentHashMap<>();
        String initialStatus = "null";
        synchronized (headerFrames) {
            if (!headerFrames.isEmpty()) {
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
                Http3HeadersFrame trailing = headerFrames.get(headerFrames.size() - 1);
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

    private static int findAvailableUdpPort() {
        try (java.net.DatagramSocket socket = new java.net.DatagramSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            return 0;
        }
    }

    private static void assumeQuicAvailable() {
        try {
            Assume.assumeTrue(
                "native QUIC transport not available on this platform -- skipping HTTP/3 gRPC streaming test",
                io.netty.handler.codec.quic.Quic.isAvailable()
            );
        } catch (Throwable t) {
            Assume.assumeNoException("native QUIC transport failed to load -- skipping HTTP/3 gRPC streaming test", t);
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
