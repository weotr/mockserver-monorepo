package org.mockserver.netty.integration.mock;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpForward;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Protocol;
import org.mockserver.netty.MockServer;
import org.mockserver.scheduler.Scheduler;

import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;
import static org.mockserver.verify.VerificationTimes.exactly;

/**
 * End-to-end check of the opt-in {@code forwardProxyHttp2Enabled} feature.
 * <p>
 * Topology: an HTTP/2 client -> a "forward" MockServer (with a {@code forward()} expectation) -> a
 * TLS "upstream" MockServer that holds protocol-discriminated expectations. The upstream MockServer's
 * own server pipeline stamps each received request with the ALPN-negotiated protocol, so an expectation
 * keyed on {@code withProtocol(HTTP_2)} vs {@code withProtocol(HTTP_1_1)} is a trusted proof of which
 * protocol the forward actually spoke to the upstream.
 * <ul>
 *   <li>flag ON  + HTTP/2 inbound -> the forward to the TLS upstream is HTTP/2 (preserved).</li>
 *   <li>flag OFF + HTTP/2 inbound -> the forward is forced into HTTP/1.1 (historical behaviour).</li>
 * </ul>
 *
 * @author jamesdbloom
 */
public class ForwardHttp2UpstreamIntegrationTest {

    private static EventLoopGroup clientEventLoopGroup;
    private static MockServer upstreamServer;
    private static MockServerClient upstreamClient;

    @BeforeClass
    public static void setupFixture() {
        clientEventLoopGroup = new NioEventLoopGroup(3, new Scheduler.SchedulerThreadFactory(ForwardHttp2UpstreamIntegrationTest.class.getSimpleName() + "-eventLoop"));
        // a TLS upstream (MockServer) that negotiates HTTP/1.1 or HTTP/2 via ALPN and records the
        // protocol it actually received on each request, so protocol-discriminated expectations work
        upstreamServer = new MockServer();
        upstreamClient = new MockServerClient("localhost", upstreamServer.getLocalPort());
    }

    @AfterClass
    public static void stopFixture() {
        stopQuietly(upstreamClient);
        stopQuietly(upstreamServer);
        if (clientEventLoopGroup != null) {
            clientEventLoopGroup.shutdownGracefully(0, 0, MILLISECONDS).syncUninterruptibly();
        }
    }

    @Before
    public void reset() {
        upstreamClient.reset();
        // protocol-discriminated expectations on the upstream: the response body proves which protocol
        // the forwarded request used to reach the upstream
        upstreamClient
            .when(request().withPath("/forwarded").withProtocol(Protocol.HTTP_1_1))
            .respond(response().withStatusCode(200).withBody("upstream_saw_http1"));
        upstreamClient
            .when(request().withPath("/forwarded").withProtocol(Protocol.HTTP_2))
            .respond(response().withStatusCode(201).withBody("upstream_saw_http2"));
    }

    private HttpResponse forwardHttp2Through(MockServer forwardServer) throws Exception {
        return new NettyHttpClient(
            configuration(),
            new MockServerLogger(),
            clientEventLoopGroup,
            null,
            false
        ).sendRequest(
            request()
                .withMethod("GET")
                .withPath("/forwarded")
                .withSecure(true)
                .withProtocol(Protocol.HTTP_2)
                .withHeader(HOST.toString(), "127.0.0.1:" + forwardServer.getLocalPort())
        ).get(15, SECONDS);
    }

    private MockServer startForwardServer(boolean forwardProxyHttp2Enabled) {
        MockServer forwardServer = new MockServer(configuration().forwardProxyHttp2Enabled(forwardProxyHttp2Enabled));
        new MockServerClient("localhost", forwardServer.getLocalPort())
            .when(request().withPath("/forwarded"))
            .forward(
                forward()
                    .withHost("127.0.0.1")
                    .withPort(upstreamServer.getLocalPort())
                    .withScheme(HttpForward.Scheme.HTTPS)
            );
        return forwardServer;
    }

    @Test(timeout = 30000)
    public void shouldForwardInboundHttp2AsHttp2WhenFlagEnabled() throws Exception {
        // given - flag on
        MockServer forwardServer = startForwardServer(true);
        try {
            // when - an HTTP/2 request is forwarded
            HttpResponse response = forwardHttp2Through(forwardServer);

            // then - the upstream matched the HTTP/2 expectation, proving the forward preserved h2
            assertThat(response.getStatusCode(), is(201));
            assertThat(response.getBodyAsString(), is("upstream_saw_http2"));
            upstreamClient.verify(request().withPath("/forwarded").withProtocol(Protocol.HTTP_2), exactly(1));
        } finally {
            stopQuietly(forwardServer);
        }
    }

    @Test(timeout = 30000)
    public void shouldForceInboundHttp2ToHttp1WhenFlagDisabled() throws Exception {
        // given - default behaviour: flag off
        MockServer forwardServer = startForwardServer(false);
        try {
            // when - the same HTTP/2 request is forwarded with the flag off
            HttpResponse response = forwardHttp2Through(forwardServer);

            // then - the forward was forced into HTTP/1.1, so the upstream matched the HTTP/1.1 expectation
            assertThat(response.getStatusCode(), is(200));
            assertThat(response.getBodyAsString(), is("upstream_saw_http1"));
            upstreamClient.verify(request().withPath("/forwarded").withProtocol(Protocol.HTTP_1_1), exactly(1));
        } finally {
            stopQuietly(forwardServer);
        }
    }
}
