package org.mockserver.netty.integration.mock;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.model.Body;
import org.mockserver.model.Header;
import org.mockserver.model.HttpOverrideForwardedRequest;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.LogEventRequestAndResponse;
import org.mockserver.netty.MockServer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpOverrideForwardedRequest.forwardOverriddenRequest;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Tests for the streaming response relay feature. When MockServer proxies a streaming
 * response (Server-Sent Events or chunked with no Content-Length), chunks should be
 * relayed incrementally to the client rather than being fully buffered.
 */
public class StreamingProxyResponseIntegrationTest {

    private static MockServerClient mockServerClient;
    private static int mockServerPort;
    private static EventLoopGroup upstreamGroup;
    private static Channel upstreamChannel;
    private static int upstreamPort;

    // Track the original streaming config so we can restore it
    private static boolean originalStreamingEnabled;
    private static String originalProxyRemoteHost;
    private static Integer originalProxyRemotePort;

    @BeforeClass
    public static void startServers() throws Exception {
        // Remember original config
        originalStreamingEnabled = ConfigurationProperties.streamingResponsesEnabled();
        originalProxyRemoteHost = ConfigurationProperties.proxyRemoteHost();
        originalProxyRemotePort = ConfigurationProperties.proxyRemotePort();

        // Enable streaming responses
        ConfigurationProperties.streamingResponsesEnabled(true);
        ConfigurationProperties.maxStreamingCaptureBytes(256);

        // Start a bare Netty HTTP server as the "upstream" that sends chunked responses
        upstreamGroup = new NioEventLoopGroup(1);
        ServerBootstrap b = new ServerBootstrap();
        b.group(upstreamGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(new HttpServerCodec());
                    ch.pipeline().addLast(new HttpObjectAggregator(65536));
                    ch.pipeline().addLast(new ChunkedUpstreamHandler());
                }
            });
        upstreamChannel = b.bind(0).sync().channel();
        upstreamPort = ((InetSocketAddress) upstreamChannel.localAddress()).getPort();

        // Start MockServer configured to forward to the upstream
        ConfigurationProperties.proxyRemoteHost("localhost");
        ConfigurationProperties.proxyRemotePort(upstreamPort);
        mockServerPort = new MockServer().getLocalPort();
        mockServerClient = new MockServerClient("localhost", mockServerPort);
    }

    @AfterClass
    public static void stopServers() {
        stopQuietly(mockServerClient);
        if (upstreamChannel != null) {
            upstreamChannel.close();
        }
        if (upstreamGroup != null) {
            upstreamGroup.shutdownGracefully();
        }
        // Restore original config
        ConfigurationProperties.streamingResponsesEnabled(originalStreamingEnabled);
        ConfigurationProperties.proxyRemoteHost(originalProxyRemoteHost != null ? originalProxyRemoteHost : "");
        ConfigurationProperties.proxyRemotePort(originalProxyRemotePort);
        ConfigurationProperties.maxStreamingCaptureBytes(262144);
    }

    @Before
    public void resetServer() {
        mockServerClient.reset();
    }

    /**
     * A simple upstream handler that responds with a chunked text/event-stream or plain
     * chunked response depending on the request path:
     * - /sse: Server-Sent Events with 3 events, each delayed 100ms
     * - /chunked: chunked response with 3 chunks (not SSE)
     * - /normal: a normal non-streaming response
     * - /large-sse: Server-Sent Events that exceed maxStreamingCaptureBytes
     * - /close-mid-stream: starts sending then closes the connection
     * - /binary-stream: binary data streamed as application/octet-stream
     */
    @ChannelHandler.Sharable
    static class ChunkedUpstreamHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            String path = request.uri();
            if ("/sse".equals(path)) {
                sendSseResponse(ctx);
            } else if ("/chunked".equals(path)) {
                sendChunkedResponse(ctx);
            } else if ("/normal".equals(path)) {
                sendNormalResponse(ctx);
            } else if ("/large-sse".equals(path)) {
                sendLargeSseResponse(ctx);
            } else if ("/close-mid-stream".equals(path)) {
                sendAndCloseResponse(ctx);
            } else if ("/binary-stream".equals(path)) {
                sendBinaryStreamResponse(ctx);
            } else {
                DefaultFullHttpResponse resp = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND,
                    Unpooled.copiedBuffer("Not Found", StandardCharsets.UTF_8)
                );
                HttpUtil.setContentLength(resp, resp.content().readableBytes());
                ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
            }
        }

        private void sendSseResponse(ChannelHandlerContext ctx) {
            DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            head.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream");
            head.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
            head.headers().set(HttpHeaderNames.CONNECTION, "keep-alive");
            HttpUtil.setTransferEncodingChunked(head, true);
            ctx.writeAndFlush(head);

            String[] events = {
                "data: event1\n\n",
                "data: event2\n\n",
                "data: event3\n\n"
            };

            scheduleChunks(ctx, events, 0, 100, true);
        }

        private void sendChunkedResponse(ChannelHandlerContext ctx) {
            DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            head.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
            HttpUtil.setTransferEncodingChunked(head, true);
            ctx.writeAndFlush(head);

            String[] chunks = {
                "{\"chunk\":1}",
                "{\"chunk\":2}",
                "{\"chunk\":3}"
            };

            scheduleChunks(ctx, chunks, 0, 100, true);
        }

        private void sendNormalResponse(ChannelHandlerContext ctx) {
            byte[] body = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            DefaultFullHttpResponse resp = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                Unpooled.copiedBuffer(body)
            );
            resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
            HttpUtil.setContentLength(resp, body.length);
            ctx.writeAndFlush(resp);
        }

        private void sendLargeSseResponse(ChannelHandlerContext ctx) {
            DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            head.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream");
            head.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
            HttpUtil.setTransferEncodingChunked(head, true);
            ctx.writeAndFlush(head);

            // Create events that total > 256 bytes (maxStreamingCaptureBytes in test)
            StringBuilder largeEvent = new StringBuilder("data: ");
            for (int i = 0; i < 300; i++) {
                largeEvent.append("X");
            }
            largeEvent.append("\n\n");

            String[] events = {largeEvent.toString(), "data: final\n\n"};
            scheduleChunks(ctx, events, 0, 50, true);
        }

        private void sendAndCloseResponse(ChannelHandlerContext ctx) {
            DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            head.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream");
            HttpUtil.setTransferEncodingChunked(head, true);
            ctx.writeAndFlush(head);

            // Send one chunk then close
            ctx.executor().schedule(() -> {
                ctx.writeAndFlush(new DefaultHttpContent(
                    Unpooled.copiedBuffer("data: before-close\n\n", StandardCharsets.UTF_8)
                )).addListener(f -> {
                    // Close without sending LastHttpContent
                    ctx.close();
                });
            }, 50, TimeUnit.MILLISECONDS);
        }

        private void sendBinaryStreamResponse(ChannelHandlerContext ctx) {
            DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            head.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/octet-stream");
            HttpUtil.setTransferEncodingChunked(head, true);
            ctx.writeAndFlush(head);

            // Send binary data (non-text bytes)
            byte[] binaryData = new byte[]{0x00, 0x01, 0x02, (byte) 0xFF, (byte) 0xFE, (byte) 0xFD};
            ctx.executor().schedule(() -> {
                ctx.writeAndFlush(new DefaultHttpContent(Unpooled.copiedBuffer(binaryData)))
                    .addListener(f -> ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
                        .addListener(ChannelFutureListener.CLOSE));
            }, 50, TimeUnit.MILLISECONDS);
        }

        private void scheduleChunks(ChannelHandlerContext ctx, String[] chunks, int index, long delayMs, boolean closeAfterLast) {
            if (index >= chunks.length) {
                ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener(f -> {
                    if (closeAfterLast) {
                        ctx.close();
                    }
                });
                return;
            }
            ctx.executor().schedule(() -> {
                if (ctx.channel().isActive()) {
                    ctx.writeAndFlush(new DefaultHttpContent(
                        Unpooled.copiedBuffer(chunks[index], StandardCharsets.UTF_8)
                    )).addListener(f -> {
                        if (f.isSuccess()) {
                            scheduleChunks(ctx, chunks, index + 1, delayMs, closeAfterLast);
                        }
                    });
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        }
    }

    @Test
    public void shouldStreamSseResponseIncrementally() throws Exception {
        // Send request to MockServer (which will proxy to upstream)
        // The upstream sends 3 SSE events with 100ms delays between them
        long startTime = System.currentTimeMillis();

        List<String> receivedLines = new ArrayList<>();
        try (Socket socket = new Socket("localhost", mockServerPort)) {
            socket.setSoTimeout(10000);
            OutputStream output = socket.getOutputStream();
            output.write(("GET /sse HTTP/1.1\r\n" +
                "Host: localhost:" + upstreamPort + "\r\n" +
                "Connection: close\r\n" +
                "\r\n").getBytes(StandardCharsets.UTF_8));
            output.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                receivedLines.add(line);
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;

        // Verify the response contains all SSE events
        String fullResponse = String.join("\n", receivedLines);
        assertThat("response should contain HTTP 200", fullResponse, containsString("200"));
        assertThat("response should contain event1", fullResponse, containsString("data: event1"));
        assertThat("response should contain event2", fullResponse, containsString("data: event2"));
        assertThat("response should contain event3", fullResponse, containsString("data: event3"));

        // Verify it took at least 200ms (3 events with 100ms delays between them)
        // This proves chunks were relayed incrementally, not buffered
        assertThat("response should take at least 200ms (proving streaming)", elapsed, greaterThanOrEqualTo(200L));
    }

    @Test
    public void shouldAggregateChunkedNonSseResponseNormally() throws Exception {
        // A chunked response without text/event-stream should NOT be detected as streaming.
        // It should be fully aggregated and returned with a complete body (regression guard
        // for WAR deployment where Tomcat uses chunked encoding for all responses).
        List<String> receivedLines = new ArrayList<>();
        try (Socket socket = new Socket("localhost", mockServerPort)) {
            socket.setSoTimeout(10000);
            OutputStream output = socket.getOutputStream();
            output.write(("GET /chunked HTTP/1.1\r\n" +
                "Host: localhost:" + upstreamPort + "\r\n" +
                "Connection: close\r\n" +
                "\r\n").getBytes(StandardCharsets.UTF_8));
            output.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                receivedLines.add(line);
            }
        }

        String fullResponse = String.join("\n", receivedLines);
        assertThat("response should contain HTTP 200", fullResponse, containsString("200"));
        assertThat("response should contain chunk1", fullResponse, containsString("{\"chunk\":1}"));
        assertThat("response should contain chunk2", fullResponse, containsString("{\"chunk\":2}"));
        assertThat("response should contain chunk3", fullResponse, containsString("{\"chunk\":3}"));
    }

    @Test
    public void shouldNotStreamNormalResponse() throws Exception {
        List<String> receivedLines = new ArrayList<>();
        try (Socket socket = new Socket("localhost", mockServerPort)) {
            socket.setSoTimeout(5000);
            OutputStream output = socket.getOutputStream();
            output.write(("GET /normal HTTP/1.1\r\n" +
                "Host: localhost:" + upstreamPort + "\r\n" +
                "Connection: close\r\n" +
                "\r\n").getBytes(StandardCharsets.UTF_8));
            output.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                receivedLines.add(line);
            }
        }

        String fullResponse = String.join("\n", receivedLines);
        assertThat("response should contain HTTP 200", fullResponse, containsString("200"));
        assertThat("response should contain the body", fullResponse, containsString("{\"status\":\"ok\"}"));
    }

    @Test
    public void shouldHandleMidStreamClose() throws Exception {
        List<String> receivedLines = new ArrayList<>();
        try (Socket socket = new Socket("localhost", mockServerPort)) {
            socket.setSoTimeout(10000);
            OutputStream output = socket.getOutputStream();
            output.write(("GET /close-mid-stream HTTP/1.1\r\n" +
                "Host: localhost:" + upstreamPort + "\r\n" +
                "Connection: close\r\n" +
                "\r\n").getBytes(StandardCharsets.UTF_8));
            output.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                receivedLines.add(line);
            }
        }

        String fullResponse = String.join("\n", receivedLines);
        // Should get at least the head and partial content
        assertThat("response should contain HTTP 200", fullResponse, containsString("200"));
        assertThat("response should contain data before close", fullResponse, containsString("data: before-close"));
    }

    @Test
    public void shouldFullyAggregateForForwardReplaceWithResponseOverride() throws Exception {
        // When a FORWARD_REPLACE expectation has a response override, the upstream streaming
        // response must be fully aggregated (not streamed) so the override can be applied.
        // The upstream /sse endpoint sends text/event-stream chunked, but the override adds
        // a custom header — the response should arrive with the override applied.
        mockServerClient
            .when(request().withPath("/sse"))
            .forward(
                forwardOverriddenRequest(
                    request().withHeader("Host", "localhost:" + upstreamPort),
                    response().withHeader("X-Custom-Override", "applied")
                )
            );

        List<String> receivedLines = new ArrayList<>();
        try (Socket socket = new Socket("localhost", mockServerPort)) {
            socket.setSoTimeout(15000);
            OutputStream output = socket.getOutputStream();
            output.write(("GET /sse HTTP/1.1\r\n" +
                "Host: localhost:" + upstreamPort + "\r\n" +
                "Connection: close\r\n" +
                "\r\n").getBytes(StandardCharsets.UTF_8));
            output.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                receivedLines.add(line);
            }
        }

        String fullResponse = String.join("\n", receivedLines);
        // The override header should be present (proves the response was fully aggregated
        // and the override was applied)
        assertThat("response should contain the custom override header",
            fullResponse, containsString("X-Custom-Override: applied"));
        // The body should contain all SSE events (fully aggregated)
        assertThat("response should contain event data",
            fullResponse, containsString("data: event1"));
        assertThat("response should contain HTTP 200", fullResponse, containsString("200"));
    }

    @Test
    public void shouldFullyAggregateSseForForwardReplaceWithResponseOverride() throws Exception {
        // When a FORWARD_REPLACE expectation has a response override, even an SSE upstream
        // that would normally be streamed must be fully aggregated so the override applies.
        // Uses /sse to exercise the DISABLE_RESPONSE_STREAMING path for a genuinely
        // streaming upstream.
        mockServerClient
            .when(request().withPath("/sse-override-test"))
            .forward(
                forwardOverriddenRequest(
                    request()
                        .withPath("/sse")
                        .withHeader("Host", "localhost:" + upstreamPort),
                    response().withHeader("X-Override-SSE", "yes")
                )
            );

        List<String> receivedLines = new ArrayList<>();
        try (Socket socket = new Socket("localhost", mockServerPort)) {
            socket.setSoTimeout(15000);
            OutputStream output = socket.getOutputStream();
            output.write(("GET /sse-override-test HTTP/1.1\r\n" +
                "Host: localhost:" + upstreamPort + "\r\n" +
                "Connection: close\r\n" +
                "\r\n").getBytes(StandardCharsets.UTF_8));
            output.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                receivedLines.add(line);
            }
        }

        String fullResponse = String.join("\n", receivedLines);
        assertThat("response should contain override header",
            fullResponse, containsString("X-Override-SSE: yes"));
        assertThat("response should contain SSE event data",
            fullResponse, containsString("data: event1"));
        assertThat("response should contain HTTP 200", fullResponse, containsString("200"));
    }

    @Test
    public void shouldLogSseStreamingResponseAsStringBody() throws Exception {
        // Send an SSE request through the proxy and wait for the stream to complete
        try (Socket socket = new Socket("localhost", mockServerPort)) {
            socket.setSoTimeout(10000);
            OutputStream output = socket.getOutputStream();
            output.write(("GET /sse HTTP/1.1\r\n" +
                "Host: localhost:" + upstreamPort + "\r\n" +
                "Connection: close\r\n" +
                "\r\n").getBytes(StandardCharsets.UTF_8));
            output.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            while (reader.readLine() != null) {
                // consume the full response
            }
        }

        // Allow time for the completion listener to fire and the log entry to be written
        Thread.sleep(1000);

        // Retrieve the recorded request/response pair via the MockServer API
        LogEventRequestAndResponse[] recorded = mockServerClient.retrieveRecordedRequestsAndResponses(
            request().withPath("/sse")
        );
        assertThat("should have recorded at least one SSE request", recorded.length, greaterThanOrEqualTo(1));

        HttpResponse loggedResponse = recorded[0].getHttpResponse();
        assertThat("logged response should not be null", loggedResponse, notNullValue());
        assertThat("logged response should have a body", loggedResponse.getBody(), notNullValue());

        // The key assertion: the body should be a STRING type (not BINARY)
        assertThat("SSE streaming body should be logged as STRING, not BINARY",
            loggedResponse.getBody().getType(), is(Body.Type.STRING));

        // The body content should contain the SSE event text
        String bodyString = loggedResponse.getBodyAsString();
        assertThat("logged body should contain SSE event text",
            bodyString, containsString("data: event1"));
    }

    @Test
    public void shouldLogChunkedNonSseResponseWithBody() throws Exception {
        // Chunked JSON responses are NOT detected as streaming (only SSE is).
        // They are aggregated normally and logged with their full body.
        try (Socket socket = new Socket("localhost", mockServerPort)) {
            socket.setSoTimeout(10000);
            OutputStream output = socket.getOutputStream();
            output.write(("GET /chunked HTTP/1.1\r\n" +
                "Host: localhost:" + upstreamPort + "\r\n" +
                "Connection: close\r\n" +
                "\r\n").getBytes(StandardCharsets.UTF_8));
            output.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            while (reader.readLine() != null) {
                // consume the full response
            }
        }

        Thread.sleep(1000);

        LogEventRequestAndResponse[] recorded = mockServerClient.retrieveRecordedRequestsAndResponses(
            request().withPath("/chunked")
        );
        assertThat("should have recorded at least one chunked request", recorded.length, greaterThanOrEqualTo(1));

        HttpResponse loggedResponse = recorded[0].getHttpResponse();
        assertThat("logged response should not be null", loggedResponse, notNullValue());
        assertThat("logged response should have a body", loggedResponse.getBody(), notNullValue());

        String bodyString = loggedResponse.getBodyAsString();
        assertThat("logged body should contain JSON chunk text",
            bodyString, containsString("\"chunk\""));
    }

    @Test
    public void shouldLogBinaryChunkedResponseWithBody() throws Exception {
        // Binary chunked responses (application/octet-stream) are NOT detected as
        // streaming (only SSE is). They are aggregated normally and logged with their body.
        try (Socket socket = new Socket("localhost", mockServerPort)) {
            socket.setSoTimeout(10000);
            OutputStream output = socket.getOutputStream();
            output.write(("GET /binary-stream HTTP/1.1\r\n" +
                "Host: localhost:" + upstreamPort + "\r\n" +
                "Connection: close\r\n" +
                "\r\n").getBytes(StandardCharsets.UTF_8));
            output.flush();

            // consume the full response (binary, so just read bytes)
            byte[] buf = new byte[4096];
            while (socket.getInputStream().read(buf) != -1) {
                // drain
            }
        }

        Thread.sleep(1000);

        LogEventRequestAndResponse[] recorded = mockServerClient.retrieveRecordedRequestsAndResponses(
            request().withPath("/binary-stream")
        );
        assertThat("should have recorded at least one binary-stream request", recorded.length, greaterThanOrEqualTo(1));

        HttpResponse loggedResponse = recorded[0].getHttpResponse();
        assertThat("logged response should not be null", loggedResponse, notNullValue());
        assertThat("logged response should have a body", loggedResponse.getBody(), notNullValue());

        // Binary chunked body should be logged as BINARY type (standard aggregation)
        assertThat("binary chunked body should be logged as BINARY",
            loggedResponse.getBody().getType(), is(Body.Type.BINARY));
    }
}
