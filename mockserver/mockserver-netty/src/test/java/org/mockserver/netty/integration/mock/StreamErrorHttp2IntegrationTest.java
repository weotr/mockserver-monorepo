package org.mockserver.netty.integration.mock;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpScheme;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2ResetFrame;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.model.HttpError;
import org.mockserver.netty.MockServer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.model.HttpError.error;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Behavioural proof that an {@link HttpError} carrying {@code streamError} resets the matched
 * HTTP/2 stream (RST_STREAM) with the configured error code rather than returning a normal
 * response. Uses an in-JVM Netty HTTP/2 (h2c) multiplex client so the exact RST_STREAM error code
 * can be asserted. Exercises the default connection-level server pipeline
 * (grpcBidiStreamingEnabled off).
 */
public class StreamErrorHttp2IntegrationTest {

    private static MockServer mockServer;
    private static MockServerClient mockServerClient;

    @BeforeClass
    public static void startServer() {
        mockServer = new MockServer();
        mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
    }

    @Test
    public void shouldResetHttp2StreamWithConfiguredErrorCode() throws Exception {
        // given - an expectation that resets the stream with REFUSED_STREAM (0x7)
        mockServerClient
            .when(request().withPath("/stream-reset"))
            .error(error().withStreamError(HttpError.StreamErrorCode.REFUSED_STREAM));

        // when - send an HTTP/2 request over h2c and capture the reset frame the client observes
        long observedErrorCode = sendH2cRequestAndAwaitReset("/stream-reset");

        // then - the client observed an RST_STREAM carrying the configured error code
        assertThat(observedErrorCode, is(0x7L));
    }

    @Test
    public void shouldHonourStreamErrorOverDropConnectionWhenBothSet() throws Exception {
        // given - an error action with BOTH streamError and dropConnection set; streamError must win
        mockServerClient
            .when(request().withPath("/stream-reset-precedence"))
            .error(error()
                .withDropConnection(true)
                .withStreamError(HttpError.StreamErrorCode.REFUSED_STREAM));

        // when
        long observedErrorCode = sendH2cRequestAndAwaitReset("/stream-reset-precedence");

        // then - the stream is reset with the streamError code rather than the connection being dropped
        assertThat(observedErrorCode, is(0x7L));
    }

    @Test
    public void shouldResetHttp2StreamWithRawNumericErrorCode() throws Exception {
        // given - a raw numeric HTTP/2 error code (ENHANCE_YOUR_CALM = 0xb)
        mockServerClient
            .when(request().withPath("/stream-reset-raw"))
            .error(error().withStreamError(0xbL));

        // when
        long observedErrorCode = sendH2cRequestAndAwaitReset("/stream-reset-raw");

        // then
        assertThat(observedErrorCode, is(0xbL));
    }

    private long sendH2cRequestAndAwaitReset(String path) throws Exception {
        NioEventLoopGroup group = new NioEventLoopGroup();
        try {
            CompletableFuture<Long> resetCodeFuture = new CompletableFuture<>();

            Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(Http2FrameCodecBuilder.forClient().build());
                        // inbound child streams initiated by the server are not expected; provide a
                        // no-op initializer for completeness
                        ch.pipeline().addLast(new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
                            @Override
                            protected void initChannel(Channel ch) {
                                ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
                            }
                        }));
                    }
                });

            Channel parent = bootstrap.connect("localhost", mockServer.getLocalPort()).sync().channel();

            Http2StreamChannel streamChannel = new Http2StreamChannelBootstrap(parent)
                .handler(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        if (msg instanceof Http2ResetFrame) {
                            resetCodeFuture.complete(((Http2ResetFrame) msg).errorCode());
                        }
                        io.netty.util.ReferenceCountUtil.release(msg);
                    }

                    @Override
                    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                        // a peer RST_STREAM on the connection-level adapter path is surfaced to the
                        // child stream channel as an inbound user event, not a channelRead
                        if (evt instanceof Http2ResetFrame) {
                            resetCodeFuture.complete(((Http2ResetFrame) evt).errorCode());
                        }
                    }

                    @Override
                    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                        resetCodeFuture.completeExceptionally(cause);
                    }
                })
                .open()
                .sync()
                .getNow();

            Http2Headers headers = new DefaultHttp2Headers()
                .method(HttpMethod.GET.asciiName())
                .scheme(HttpScheme.HTTP.name())
                .authority("localhost:" + mockServer.getLocalPort())
                .path(path);
            streamChannel.writeAndFlush(new DefaultHttp2HeadersFrame(headers, true));

            return resetCodeFuture.get(10, TimeUnit.SECONDS);
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }
}
