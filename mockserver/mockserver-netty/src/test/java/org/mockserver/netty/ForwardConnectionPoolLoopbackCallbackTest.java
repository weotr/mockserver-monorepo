package org.mockserver.netty;

import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.model.Header;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * NORMAL-phase (surefire, not failsafe) behavioural guard for the connection-pool default-on
 * self-deadlock. The forward connection pool is only safe to default on because the outbound
 * forward/proxy HTTP client runs on its OWN event-loop group, disjoint from the server worker group:
 * a pooled keep-alive connection reused inside a synchronous local object-callback (which runs ON a
 * server worker thread and makes a blocking call back to the same server) is therefore never pinned to
 * the very worker thread that is blocked in that callback.
 * <p>
 * This area has regressed TWICE because per-unit {@code -Dtest} verification skipped the FAILSAFE
 * integration phase where the deadlock surfaces — the canonical reproduction,
 * {@code WebsocketCallbackRegistryIntegrationTest.shouldAllowUseOfSameWebsocketClientInsideCallbackViaLocalJVM},
 * is an {@code *IntegrationTest} and so never runs under a plain {@code -Dtest} surefire invocation.
 * This class is the SUREFIRE-PHASE analogue of that test: its name ends in {@code Test} (not
 * {@code IntegrationTest}) ON PURPOSE so a plain {@code -Dtest=ForwardConnectionPoolLoopbackCallbackTest}
 * (or any ordinary surefire run) executes it and catches a regression without the failsafe phase.
 * <p>
 * It reproduces the proven-deadlocking shape directly: pooling ON + a synchronous local object response
 * callback that reuses the same {@link MockServerClient} against the same server (a blocking loopback
 * registration call) from inside the callback. Pooling is enabled per-instance via the
 * {@link Configuration} passed to {@link MockServer}, so the test mutates no JVM-global state and is
 * parallel-safe.
 * <p>
 * Proof this is a real guard (not a no-op): with the disjoint-group fix temporarily reverted
 * ({@code MockServer.java} wired to pass {@code getEventLoopGroup()} instead of
 * {@code getForwardClientEventLoopGroup()} into the forward {@code HttpActionHandler}),
 * {@link #shouldNotDeadlockWhenLocalCallbackReusesSameClientWithPoolingOn()} TIMES OUT (the blocking
 * in-callback loopback is pinned to the blocked worker — exactly the failure the failsafe test exhibits
 * under the same revert). Restoring the disjoint group makes it pass in well under a second. See the
 * session report for the revert/restore run.
 */
public class ForwardConnectionPoolLoopbackCallbackTest {

    /**
     * Deterministic deadlock repro (surefire phase). A single server with pooling ON registers, inside
     * a synchronous local object response-callback, a NESTED expectation via the SAME
     * {@link MockServerClient} — a blocking control-plane loopback call back to the same server made on a
     * worker thread. With the forward/proxy client sharing the worker group, that blocking loopback can
     * be pinned to the very worker thread that is blocked waiting for it, self-deadlocking the event
     * loop (this is exactly what the failsafe LocalJVM callback test does, and it hangs under the same
     * revert). The dedicated forward-client group keeps the loopback on a separate event loop so the
     * registration completes and the request returns promptly.
     * <p>
     * Asserted to complete WELL under the forward socket timeout (hard 20s JUnit timeout; the real round
     * trip is expected in well under a second).
     */
    @Test(timeout = 20_000)
    public void shouldNotDeadlockWhenLocalCallbackReusesSameClientWithPoolingOn() {
        Configuration configuration = configuration().forwardConnectionPoolEnabled(true);
        MockServer mockServer = new MockServer(configuration);
        try {
            int port = mockServer.getLocalPort();
            final MockServerClient mockServerClient = new MockServerClient("localhost", port);

            int total = 20;
            for (int i = 0; i < total; i++) {
                final int id = i; // capture a stable id per expectation (no shared mutable field in the lambda)
                mockServerClient
                    .when(request().withPath("/outer_" + id))
                    .respond(httpRequest -> {
                        // BLOCKING loopback control-plane call back to the same server, from inside the
                        // callback on a worker thread — the shape that self-deadlocks a shared group.
                        mockServerClient
                            .when(request().withPath("/inner_" + id))
                            .respond(innerRequest -> response().withBody("inner_" + id));
                        return response().withBody("outer_" + id);
                    });
            }

            // when - drive each outer request (and its nested inner) so the pooled loopback is exercised
            for (int i = 0; i < total; i++) {
                assertThat(blockingGet(port, "/outer_" + i), is("outer_" + i));
                assertThat(blockingGet(port, "/inner_" + i), is("inner_" + i));
            }
        } finally {
            mockServer.stop();
        }
    }

    /**
     * Concurrency stress: many concurrent forward requests through a pooling-enabled proxy, each
     * triggering a nested blocking loopback callback on a separate backend. Asserts no timeouts, every
     * response correct, AND that the proxy's forward connection pool actually reused connections to the
     * backend (the backend accepted far fewer connections than the number of forwarded requests) —
     * proving the disjoint forward-client group holds under load. The blocking loopback runs off the
     * backend's event loop (on a dedicated pool) so the only way the round trip can hang is a pinned
     * pooled forward connection.
     */
    @Test(timeout = 60_000)
    public void shouldStayDeadlockFreeAndReuseConnectionsUnderConcurrentLoopbackLoad() throws Exception {
        Configuration proxyConfig = configuration().forwardConnectionPoolEnabled(true);
        MockServer proxy = new MockServer(proxyConfig);
        CountingBackend backend = new CountingBackend(port -> "serve_" + blockingGet(port, "/leaf"));
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            int proxyPort = proxy.getLocalPort();
            MockServerClient proxyClient = new MockServerClient("localhost", proxyPort);

            proxyClient.when(request().withPath("/leaf")).respond(response().withBody("leaf_body"));
            proxyClient
                .when(request().withPath("/outer"))
                .forward(httpRequest -> httpRequest.clone()
                    .withPath("/serve")
                    .replaceHeader(new Header("Host", "127.0.0.1:" + backend.port())));
            backend.setLoopbackTargetPort(proxyPort);

            int threads = 8;
            int requestsPerThread = 15;
            int totalRequests = threads * requestsPerThread;
            AtomicInteger failures = new AtomicInteger();
            Future<?>[] futures = new Future<?>[threads];
            for (int t = 0; t < threads; t++) {
                futures[t] = executor.submit(() -> {
                    for (int i = 0; i < requestsPerThread; i++) {
                        try {
                            if (!"serve_leaf_body".equals(blockingGet(proxyPort, "/outer"))) {
                                failures.incrementAndGet();
                            }
                        } catch (Exception e) {
                            failures.incrementAndGet();
                        }
                    }
                });
            }
            for (Future<?> future : futures) {
                future.get(50, TimeUnit.SECONDS);
            }

            // then - no hangs, every response correct
            assertThat("no failed/hung loopback callbacks under load", failures.get(), is(0));
            // and the proxy's forward pool reused connections to the backend: far fewer than total forwards
            assertThat("forward connection pool reused upstream connections under concurrent load",
                backend.acceptedConnections(), is(lessThan(totalRequests)));
        } finally {
            executor.shutdownNow();
            backend.stop();
            proxy.stop();
        }
    }

    /**
     * Opt-out end-to-end guard ({@code forwardConnectionPoolEnabled=false}): a forwarded request must
     * use a FRESH upstream connection per request rather than reusing a pooled one. A counting backend
     * records distinct TCP connections; with pooling disabled, N sequential forwards through MockServer
     * open N backend connections (no reuse). This complements the unit-level {@code unpooledClient}
     * assertion in {@code NettyHttpClientConnectionPoolTest} with a real end-to-end forward through a
     * running MockServer.
     */
    @Test(timeout = 30_000)
    public void shouldOpenFreshUpstreamConnectionPerForwardWhenPoolingDisabled() throws Exception {
        CountingBackend backend = new CountingBackend(port -> "backend_body");
        Configuration configuration = configuration().forwardConnectionPoolEnabled(false);
        MockServer mockServer = new MockServer(configuration);
        try {
            int port = mockServer.getLocalPort();
            MockServerClient client = new MockServerClient("localhost", port);

            client
                .when(request().withPath("/proxy"))
                .forward(httpRequest -> httpRequest.clone()
                    .withPath("/")
                    .replaceHeader(new Header("Host", "127.0.0.1:" + backend.port())));

            int requests = 5;
            for (int i = 0; i < requests; i++) {
                assertThat(blockingGet(port, "/proxy"), is("backend_body"));
            }

            // then - pooling disabled => a fresh upstream connection per forwarded request (no reuse)
            assertThat("pooling disabled opens a fresh backend connection per forward",
                backend.acceptedConnections(), is(requests));
        } finally {
            backend.stop();
            mockServer.stop();
        }
    }

    /**
     * RECURSION + POOL-STARVATION guard for the root callback-dispatch fix. Local callbacks now run on a
     * dedicated UNBOUNDED local-callback executor rather than inline on the worker event loop. This test
     * proves that moving them off-loop did not simply relocate the deadlock onto a bounded callback pool:
     * it drives {@code concurrentRequests} (far more than {@code actionHandlerThreadCount}) simultaneous
     * outer requests, EACH of whose local response callback makes a BLOCKING loopback to a NESTED path
     * whose own local response callback makes a SECOND blocking loopback (two-deep recursion). If local
     * callbacks shared a bounded pool, the outer callbacks would exhaust it while blocked on the inner
     * callbacks and the inner callbacks could never be scheduled — a classic pool-exhaustion deadlock.
     * With the unbounded local-callback executor every inner callback gets its own thread, so all
     * requests complete. Pooling is ON (per-instance) throughout. Hard 60s timeout.
     */
    @Test(timeout = 60_000)
    public void shouldNotDeadlockUnderConcurrentRecursiveLocalCallbacksWithPoolingOn() throws Exception {
        // pin the action-handler (scheduler) pool small so concurrentRequests dwarfs it: if local
        // callbacks ran on THAT bounded pool, the nested blocking loopbacks would deadlock.
        Configuration configuration = configuration()
            .forwardConnectionPoolEnabled(true)
            .actionHandlerThreadCount(4);
        MockServer mockServer = new MockServer(configuration);
        ExecutorService executor = Executors.newFixedThreadPool(32);
        try {
            int port = mockServer.getLocalPort();
            final MockServerClient mockServerClient = new MockServerClient("localhost", port);

            int concurrentRequests = 40;

            // inner expectations: a local response callback that itself makes a blocking loopback (depth 2)
            for (int i = 0; i < concurrentRequests; i++) {
                final int id = i;
                mockServerClient
                    .when(request().withPath("/leaf_" + id))
                    .respond(response().withBody("leaf_" + id));
                mockServerClient
                    .when(request().withPath("/inner_" + id))
                    .respond(innerRequest -> response().withBody(blockingGet(port, "/leaf_" + id)));
            }
            // outer expectations: a local response callback whose blocking loopback hits an inner callback
            for (int i = 0; i < concurrentRequests; i++) {
                final int id = i;
                mockServerClient
                    .when(request().withPath("/outer_" + id))
                    .respond(outerRequest -> response().withBody(blockingGet(port, "/inner_" + id)));
            }

            AtomicInteger failures = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);
            Future<?>[] futures = new Future<?>[concurrentRequests];
            for (int i = 0; i < concurrentRequests; i++) {
                final int id = i;
                futures[i] = executor.submit(() -> {
                    try {
                        start.await();
                        if (!("leaf_" + id).equals(blockingGet(port, "/outer_" + id))) {
                            failures.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    }
                });
            }
            // fire them all at once to maximise concurrent in-flight blocking callbacks
            start.countDown();
            for (Future<?> future : futures) {
                future.get(50, TimeUnit.SECONDS);
            }

            assertThat("no deadlock/timeout under concurrent two-deep recursive local callbacks", failures.get(), is(0));
        } finally {
            executor.shutdownNow();
            mockServer.stop();
        }
    }

    /**
     * Blocking HTTP GET, used both to drive a server from the test thread and INSIDE a backend's body
     * function to make the loopback call that exercises the self-deadlock path.
     */
    private static String blockingGet(int port, String path) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL("http://localhost:" + port + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new RuntimeException("unexpected status " + status + " for " + path);
            }
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                int c;
                while ((c = reader.read()) != -1) {
                    body.append((char) c);
                }
            }
            return body.toString();
        } catch (Exception e) {
            throw new RuntimeException("loopback GET " + path + " failed: " + e.getMessage(), e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Minimal HTTP/1.1 backend that counts accepted TCP connections and replies with the body produced
     * by a supplied function (which may itself make a blocking loopback call). Bodies are produced OFF
     * the Netty event loop on a dedicated pool so a blocking loopback never starves the backend's own
     * inbound workers. Always keep-alive, so a pooling-enabled forward reuses one connection while a
     * pooling-disabled forward opens a fresh connection per request.
     */
    private static final class CountingBackend {

        private final io.netty.channel.EventLoopGroup bossGroup = new io.netty.channel.nio.NioEventLoopGroup(1);
        private final io.netty.channel.EventLoopGroup workerGroup = new io.netty.channel.nio.NioEventLoopGroup(4);
        private final ExecutorService bodyExecutor = Executors.newCachedThreadPool();
        private final AtomicInteger accepted = new AtomicInteger();
        private final io.netty.channel.Channel serverChannel;
        private final java.util.function.IntFunction<String> bodyFunction;
        private volatile int loopbackTargetPort;

        CountingBackend(java.util.function.IntFunction<String> bodyFunction) {
            this.bodyFunction = bodyFunction;
            io.netty.bootstrap.ServerBootstrap bootstrap = new io.netty.bootstrap.ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(io.netty.channel.socket.nio.NioServerSocketChannel.class)
                .childHandler(new io.netty.channel.ChannelInitializer<io.netty.channel.socket.SocketChannel>() {
                    @Override
                    protected void initChannel(io.netty.channel.socket.SocketChannel ch) {
                        accepted.incrementAndGet();
                        ch.pipeline().addLast(new io.netty.handler.codec.http.HttpServerCodec());
                        ch.pipeline().addLast(new io.netty.handler.codec.http.HttpObjectAggregator(64 * 1024));
                        ch.pipeline().addLast(new io.netty.channel.SimpleChannelInboundHandler<io.netty.handler.codec.http.FullHttpRequest>() {
                            @Override
                            protected void channelRead0(io.netty.channel.ChannelHandlerContext ctx, io.netty.handler.codec.http.FullHttpRequest msg) {
                                bodyExecutor.execute(() -> {
                                    byte[] body = bodyFunction.apply(loopbackTargetPort).getBytes(StandardCharsets.UTF_8);
                                    ctx.executor().execute(() -> {
                                        io.netty.handler.codec.http.FullHttpResponse response = new io.netty.handler.codec.http.DefaultFullHttpResponse(
                                            io.netty.handler.codec.http.HttpVersion.HTTP_1_1,
                                            io.netty.handler.codec.http.HttpResponseStatus.OK,
                                            ctx.alloc().buffer().writeBytes(body)
                                        );
                                        response.headers().set(io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH, body.length);
                                        response.headers().set(io.netty.handler.codec.http.HttpHeaderNames.CONNECTION,
                                            io.netty.handler.codec.http.HttpHeaderValues.KEEP_ALIVE);
                                        ctx.writeAndFlush(response);
                                    });
                                });
                            }
                        });
                    }
                });
            serverChannel = bootstrap.bind(new java.net.InetSocketAddress("127.0.0.1", 0)).syncUninterruptibly().channel();
        }

        void setLoopbackTargetPort(int port) {
            this.loopbackTargetPort = port;
        }

        int port() {
            return ((java.net.InetSocketAddress) serverChannel.localAddress()).getPort();
        }

        int acceptedConnections() {
            return accepted.get();
        }

        void stop() {
            serverChannel.close().syncUninterruptibly();
            bossGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
            workerGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
            bodyExecutor.shutdownNow();
        }
    }
}
