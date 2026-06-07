package org.mockserver.netty.http3;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.incubator.codec.http3.Http3;
import io.netty.incubator.codec.http3.Http3ServerConnectionHandler;
import io.netty.incubator.codec.quic.InsecureQuicTokenHandler;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.metrics.Metrics;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.socket.tls.KeyAndCertificateFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.mockserver.socket.tls.KeyAndCertificateFactoryFactory.createKeyAndCertificateFactory;

/**
 * HTTP/3 (QUIC) server for MockServer, integrated with the full request pipeline.
 * <p>
 * When started, HTTP/3 requests are routed through the same expectation matching,
 * action handling, recording, and proxy forwarding pipeline as HTTP/1.1 and HTTP/2.
 * <p>
 * The server uses MockServer's configured TLS certificate material. If no custom
 * certificate is configured, MockServer's auto-generated BouncyCastle certificate
 * is used. The QUIC transport requires a native BoringSSL library; if unavailable
 * at startup the server logs a warning and does not start (fail-soft).
 * <p>
 * HTTP/3 is OFF by default ({@code http3Port=0}) and is built on the upstream
 * {@code io.netty.incubator} QUIC codec, which is a pre-release incubator artifact.
 * For this reason HTTP/3 support is labelled <strong>experimental</strong>.
 */
@SuppressWarnings("deprecation") // NioEventLoopGroup deprecation in Netty 4.2
public class Http3Server {

    private static final Logger LOG = LoggerFactory.getLogger(Http3Server.class);

    private volatile Channel channel;
    private volatile NioEventLoopGroup group;

    // pipeline components (null when using the legacy echo-only constructor)
    private final Configuration configuration;
    private final MockServerLogger mockServerLogger;
    private final HttpState httpState;
    private final HttpActionHandler httpActionHandler;

    /**
     * Create an HTTP/3 server wired into MockServer's request pipeline.
     *
     * @param configuration    the server configuration
     * @param mockServerLogger the logger
     * @param httpState        the shared HTTP state (expectations, matchers, etc.)
     * @param httpActionHandler the action handler for processing matched expectations
     */
    public Http3Server(Configuration configuration, MockServerLogger mockServerLogger,
                       HttpState httpState, HttpActionHandler httpActionHandler) {
        this.configuration = configuration;
        this.mockServerLogger = mockServerLogger;
        this.httpState = httpState;
        this.httpActionHandler = httpActionHandler;
    }

    /**
     * Legacy constructor for backwards compatibility (echo-only mode).
     * Used by tests that do not need the full pipeline.
     */
    public Http3Server() {
        this.configuration = null;
        this.mockServerLogger = null;
        this.httpState = null;
        this.httpActionHandler = null;
    }

    /**
     * Start the HTTP/3 server on the given UDP port.
     *
     * @param port UDP port to bind; use 0 for an ephemeral port
     * @return the actual bound port
     * @throws Exception if the server cannot start
     */
    public int start(int port) throws Exception {
        NioEventLoopGroup localGroup = new NioEventLoopGroup(1);
        boolean success = false;
        try {
            // create a shared Metrics instance for all QUIC streams (avoids per-stream allocation)
            Metrics sharedMetrics = configuration != null ? new Metrics(configuration) : null;

            QuicSslContext sslContext = buildQuicSslContext();

            ChannelHandler codec = Http3.newQuicServerCodecBuilder()
                .sslContext(sslContext)
                .maxIdleTimeout(5000, TimeUnit.MILLISECONDS)
                .initialMaxData(10000000)
                .initialMaxStreamDataBidirectionalLocal(1000000)
                .initialMaxStreamDataBidirectionalRemote(1000000)
                .initialMaxStreamsBidirectional(100)
                .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
                .handler(new Http3ServerConnectionHandler(
                    new ChannelInitializer<QuicStreamChannel>() {
                        @Override
                        protected void initChannel(QuicStreamChannel ch) {
                            if (httpState != null && httpActionHandler != null && configuration != null) {
                                ch.pipeline().addLast(new Http3MockServerHandler(
                                    configuration, mockServerLogger, httpState, httpActionHandler, sharedMetrics
                                ));
                            } else {
                                ch.pipeline().addLast(new Http3EchoRequestHandler());
                            }
                        }
                    }
                ))
                .build();

            channel = new Bootstrap()
                .group(localGroup)
                .channel(NioDatagramChannel.class)
                .handler(codec)
                .bind(new InetSocketAddress(port))
                .sync()
                .channel();

            int boundPort = ((InetSocketAddress) channel.localAddress()).getPort();
            LOG.info("HTTP/3 (QUIC) server started on UDP port: {}", boundPort);
            group = localGroup;
            success = true;
            return boundPort;
        } finally {
            if (!success) {
                localGroup.shutdownGracefully();
            }
        }
    }

    /**
     * Returns the bound UDP port, or -1 if not started.
     */
    public int getPort() {
        if (channel != null && channel.localAddress() instanceof InetSocketAddress) {
            return ((InetSocketAddress) channel.localAddress()).getPort();
        }
        return -1;
    }

    /**
     * Stop the HTTP/3 server and release resources.
     */
    public void stop() {
        if (channel != null) {
            try {
                channel.close().sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            channel = null;
        }
        if (group != null) {
            group.shutdownGracefully();
            group = null;
        }
        LOG.info("HTTP/3 (QUIC) server stopped");
    }

    /**
     * Build the QUIC SSL context using MockServer's configured TLS material when
     * available, falling back to a self-signed certificate when no configuration
     * is provided (legacy/echo mode).
     */
    private QuicSslContext buildQuicSslContext() throws Exception {
        PrivateKey privateKey;
        X509Certificate[] certChain;

        if (configuration != null && mockServerLogger != null) {
            // use MockServer's TLS certificate infrastructure
            KeyAndCertificateFactory keyAndCertFactory = createKeyAndCertificateFactory(configuration, mockServerLogger);
            if (keyAndCertFactory.certificateNotYetCreated()) {
                keyAndCertFactory.buildAndSavePrivateKeyAndX509Certificate();
            }
            privateKey = keyAndCertFactory.privateKey();
            List<X509Certificate> chain = keyAndCertFactory.certificateChain();
            certChain = chain.toArray(new X509Certificate[0]);
            LOG.info("HTTP/3 server using MockServer's configured TLS certificate");
        } else {
            // legacy self-signed fallback
            java.security.KeyPair keyPair = generateKeyPair();
            privateKey = keyPair.getPrivate();
            certChain = new X509Certificate[]{generateSelfSignedCert(keyPair)};
            LOG.info("HTTP/3 server using self-signed certificate (no configuration provided)");
        }

        return QuicSslContextBuilder
            .forServer(privateKey, null, certChain)
            .applicationProtocols(Http3.supportedApplicationProtocols())
            .build();
    }

    /**
     * Check whether the native QUIC transport is available on this platform.
     *
     * @return true if the native BoringSSL QUIC library is loadable
     */
    public static boolean isQuicAvailable() {
        try {
            return io.netty.incubator.codec.quic.Quic.isAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    // -- self-signed cert generation for legacy mode --

    private static java.security.KeyPair generateKeyPair() throws Exception {
        java.security.KeyPairGenerator keyPairGen = java.security.KeyPairGenerator.getInstance("EC");
        keyPairGen.initialize(256, new java.security.SecureRandom());
        return keyPairGen.generateKeyPair();
    }

    private static X509Certificate generateSelfSignedCert(java.security.KeyPair keyPair) throws Exception {
        org.bouncycastle.asn1.x500.X500Name issuer = new org.bouncycastle.asn1.x500.X500Name("CN=MockServer HTTP/3, O=MockServer");
        java.math.BigInteger serial = new java.math.BigInteger(64, new java.security.SecureRandom());
        java.util.Date notBefore = new java.util.Date();
        java.util.Date notAfter = new java.util.Date(notBefore.getTime() + TimeUnit.DAYS.toMillis(365));

        org.bouncycastle.operator.ContentSigner signer = new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withECDSA")
            .build(keyPair.getPrivate());
        org.bouncycastle.cert.X509CertificateHolder holder = new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
            issuer, serial, notBefore, notAfter, issuer, keyPair.getPublic()
        ).build(signer);

        return new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter().getCertificate(holder);
    }

    /**
     * Legacy echo request handler, kept for backward compatibility and basic
     * transport-level testing when the full pipeline is not wired.
     */
    static class Http3EchoRequestHandler extends io.netty.incubator.codec.http3.Http3RequestStreamInboundHandler {

        @Override
        protected void channelRead(
            io.netty.channel.ChannelHandlerContext ctx,
            io.netty.incubator.codec.http3.Http3HeadersFrame headersFrame
        ) {
            CharSequence methodSeq = headersFrame.headers().method();
            CharSequence pathSeq = headersFrame.headers().path();
            String method = methodSeq != null ? methodSeq.toString() : "UNKNOWN";
            String path = pathSeq != null ? pathSeq.toString() : "/";

            String responseBody = "MockServer HTTP/3 echo - method: " + method + ", path: " + path;
            byte[] bodyBytes = responseBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);

            io.netty.incubator.codec.http3.DefaultHttp3HeadersFrame responseHeaders = new io.netty.incubator.codec.http3.DefaultHttp3HeadersFrame();
            responseHeaders.headers().status("200");
            responseHeaders.headers().add("content-type", "text/plain; charset=utf-8");
            responseHeaders.headers().addInt("content-length", bodyBytes.length);
            responseHeaders.headers().add("server", "mockserver-http3-experimental");

            ctx.write(responseHeaders);
            ctx.writeAndFlush(new io.netty.incubator.codec.http3.DefaultHttp3DataFrame(
                io.netty.buffer.Unpooled.wrappedBuffer(bodyBytes)
            )).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
        }

        @Override
        protected void channelRead(
            io.netty.channel.ChannelHandlerContext ctx,
            io.netty.incubator.codec.http3.Http3DataFrame dataFrame
        ) {
            // echo handler: ignore request body data frames
            io.netty.util.ReferenceCountUtil.release(dataFrame);
        }

        @Override
        protected void channelInputClosed(io.netty.channel.ChannelHandlerContext ctx) {
            // stream input closed by peer - nothing to do for the echo handler
        }
    }
}
