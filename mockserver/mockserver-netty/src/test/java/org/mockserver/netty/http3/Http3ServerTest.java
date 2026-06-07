package org.mockserver.netty.http3;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.incubator.codec.http3.DefaultHttp3DataFrame;
import io.netty.incubator.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.incubator.codec.http3.Http3;
import io.netty.incubator.codec.http3.Http3ClientConnectionHandler;
import io.netty.incubator.codec.http3.Http3DataFrame;
import io.netty.incubator.codec.http3.Http3HeadersFrame;
import io.netty.incubator.codec.http3.Http3RequestStreamInboundHandler;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.netty.MockServer;

import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Tests the HTTP/3 server integration with MockServer's expectation pipeline.
 * <p>
 * The tests skip gracefully when the native QUIC transport (BoringSSL) is not
 * available on the build platform.
 */
@SuppressWarnings("deprecation") // NioEventLoopGroup deprecation in Netty 4.2
public class Http3ServerTest {

    private MockServer mockServer;
    private Http3Server standaloneServer;
    private NioEventLoopGroup clientGroup;

    @Before
    public void setUp() {
        assumeQuicAvailable();
    }

    @After
    public void tearDown() {
        if (mockServer != null) {
            mockServer.stop();
            mockServer = null;
        }
        if (standaloneServer != null) {
            standaloneServer.stop();
            standaloneServer = null;
        }
        if (clientGroup != null) {
            clientGroup.shutdownGracefully();
            clientGroup = null;
        }
    }

    @Test
    public void shouldServeHttp3EchoResponse() throws Exception {
        // start server in echo-only mode (legacy constructor)
        standaloneServer = new Http3Server();
        int port = standaloneServer.start(0);
        assertThat("server should bind to a port", port > 0, is(true));
        assertThat("getPort should return the bound port", standaloneServer.getPort(), is(port));

        // verify echo response
        String[] result = sendHttp3Request(port, "GET", "/hello", null);
        assertThat("status should be 200", result[0], is("200"));
        assertThat("body should contain method", result[1], containsString("method: GET"));
        assertThat("body should contain path", result[1], containsString("path: /hello"));
    }

    @Test
    public void shouldRouteRequestThroughExpectationPipeline() throws Exception {
        // start MockServer with HTTP/3 on ephemeral port
        // use port 0 via the Http3Server directly, but trigger lifecycle via config
        // We set http3Port to a non-zero value; MockServer will bind it
        Configuration config = configuration().http3Port(0);
        // http3Port(0) means disabled. We need to use a real port.
        // Use a helper to find an available UDP port.
        int udpPort = findAvailableUdpPort();
        config.http3Port(udpPort);

        mockServer = new MockServer(config, 0);

        int http3Port = mockServer.getHttp3Port();
        Assume.assumeTrue("HTTP/3 server did not start (QUIC unavailable or port conflict)", http3Port > 0);

        // add an expectation via the client API (connecting to the existing server)
        MockServerClient client = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
        client.when(
            request().withMethod("GET").withPath("/greet")
        ).respond(
            response()
                .withStatusCode(200)
                .withHeader("content-type", "application/json")
                .withBody("{\"message\":\"Hello from HTTP/3!\"}")
        );

        // send HTTP/3 request and verify it matches the expectation
        String[] result = sendHttp3Request(http3Port, "GET", "/greet", null);
        assertThat("status should be 200", result[0], is("200"));
        assertThat("body should contain expectation response",
            result[1], containsString("Hello from HTTP/3!"));
    }

    @Test
    public void shouldReturnNotFoundForUnmatchedRequest() throws Exception {
        int udpPort = findAvailableUdpPort();
        Configuration config = configuration()
            .http3Port(udpPort)
            .attemptToProxyIfNoMatchingExpectation(false); // disable proxy attempt for clean 404

        mockServer = new MockServer(config, 0);

        int http3Port = mockServer.getHttp3Port();
        Assume.assumeTrue("HTTP/3 server did not start", http3Port > 0);

        // send request with no matching expectation
        String[] result = sendHttp3Request(http3Port, "GET", "/nonexistent", null);
        assertThat("status should be 404 for unmatched request", result[0], is("404"));
    }

    @Test
    public void shouldHandlePostRequestWithBody() throws Exception {
        int udpPort = findAvailableUdpPort();
        Configuration config = configuration().http3Port(udpPort);

        mockServer = new MockServer(config, 0);

        int http3Port = mockServer.getHttp3Port();
        Assume.assumeTrue("HTTP/3 server did not start", http3Port > 0);

        MockServerClient client = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
        client.when(
            request().withMethod("POST").withPath("/api/data")
        ).respond(
            response()
                .withStatusCode(201)
                .withBody("created")
        );

        String[] result = sendHttp3Request(http3Port, "POST", "/api/data",
            "{\"key\":\"value\"}".getBytes(StandardCharsets.UTF_8));
        assertThat("status should be 201", result[0], is("201"));
        assertThat("body should be 'created'", result[1], is("created"));
    }

    // ---- helper methods ----

    /**
     * Find an available UDP port by binding to port 0 and then closing.
     */
    private static int findAvailableUdpPort() {
        try (java.net.DatagramSocket socket = new java.net.DatagramSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            // fallback: return a high port that's unlikely to be in use
            return 0;
        }
    }

    /**
     * Send an HTTP/3 request to the given port and return [status, body].
     */
    private String[] sendHttp3Request(int port, String method, String path, byte[] body) throws Exception {
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

        QuicStreamChannel requestStream = Http3.newRequestStream(
            quicChannel,
            new Http3RequestStreamInboundHandler() {
                private final StringBuilder bodyBuilder = new StringBuilder();

                @Override
                protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame headersFrame) {
                    CharSequence status = headersFrame.headers().status();
                    statusQueue.offer(status != null ? status.toString() : "null");
                }

                @Override
                protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame dataFrame) {
                    ByteBuf content = dataFrame.content();
                    bodyBuilder.append(content.toString(StandardCharsets.UTF_8));
                    content.release();
                }

                @Override
                protected void channelInputClosed(ChannelHandlerContext ctx) {
                    bodyQueue.offer(bodyBuilder.toString());
                    ctx.close();
                }
            }
        ).sync().getNow();

        // send request headers
        DefaultHttp3HeadersFrame requestHeaders = new DefaultHttp3HeadersFrame();
        requestHeaders.headers().method(method);
        requestHeaders.headers().path(path);
        requestHeaders.headers().scheme("https");
        requestHeaders.headers().authority("127.0.0.1:" + port);

        if (body != null && body.length > 0) {
            requestHeaders.headers().add("content-type", "application/json");
            requestStream.write(requestHeaders).sync();
            requestStream.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(body)))
                .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT)
                .sync();
        } else {
            requestStream.writeAndFlush(requestHeaders)
                .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT)
                .sync();
        }

        // wait for response
        String status = statusQueue.poll(5, TimeUnit.SECONDS);
        String responseBody = bodyQueue.poll(5, TimeUnit.SECONDS);

        quicChannel.close().sync();
        clientChannel.close().sync();

        return new String[]{
            status != null ? status : "null",
            responseBody != null ? responseBody : ""
        };
    }

    /**
     * Assume-skip the test when the native QUIC library is not loadable.
     */
    private static void assumeQuicAvailable() {
        try {
            boolean available = io.netty.incubator.codec.quic.Quic.isAvailable();
            Assume.assumeTrue(
                "native QUIC transport not available on this platform -- skipping HTTP/3 test",
                available
            );
        } catch (Throwable t) {
            Assume.assumeNoException(
                "native QUIC transport failed to load -- skipping HTTP/3 test",
                t
            );
        }
    }

    /**
     * Creates a trust-all TrustManager for the self-signed certificate.
     */
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
