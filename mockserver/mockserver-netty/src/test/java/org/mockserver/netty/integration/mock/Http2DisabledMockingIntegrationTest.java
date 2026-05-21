package org.mockserver.netty.integration.mock;

import com.google.common.collect.ImmutableList;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Protocol;
import org.mockserver.netty.MockServer;
import org.mockserver.proxyconfiguration.ProxyConfiguration;
import org.mockserver.scheduler.Scheduler;

import java.util.List;

import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.proxyconfiguration.ProxyConfiguration.proxyConfiguration;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Verifies the http2Enabled=false opt-out: an HTTP/2 capable client is forced to negotiate
 * HTTP/1.1, both for a direct TLS connection and through MockServer's HTTP CONNECT forward proxy.
 * Protocol discriminated expectations make the negotiated protocol observable - the HTTP/1.1
 * expectation matching proves the client was downgraded (issue #2260).
 *
 * @author jamesdbloom
 */
public class Http2DisabledMockingIntegrationTest {

    private static MockServer mockServer;
    private static int mockServerPort;
    private static MockServerClient mockServerClient;
    private static EventLoopGroup clientEventLoopGroup;

    @BeforeClass
    public static void setupFixture() {
        clientEventLoopGroup = new NioEventLoopGroup(3, new Scheduler.SchedulerThreadFactory(Http2DisabledMockingIntegrationTest.class.getSimpleName() + "-eventLoop"));
        mockServer = new MockServer(configuration().http2Enabled(false));
        mockServerPort = mockServer.getLocalPort();
        mockServerClient = new MockServerClient("localhost", mockServerPort);
    }

    @AfterClass
    public static void stopFixture() {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
        clientEventLoopGroup.shutdownGracefully(0, 0, MILLISECONDS).syncUninterruptibly();
    }

    @Before
    public void reset() {
        mockServerClient.reset();
    }

    private HttpResponse send(List<ProxyConfiguration> proxyConfigurations, HttpRequest request) throws Exception {
        return new NettyHttpClient(
            configuration(),
            new MockServerLogger(),
            clientEventLoopGroup,
            proxyConfigurations,
            false
        ).sendRequest(request).get(15, SECONDS);
    }

    private void assertHttp2CapableClientIsForcedToHttp1(List<ProxyConfiguration> proxyConfigurations) throws Exception {
        // given - protocol discriminated expectations so the response body proves which protocol matched
        mockServerClient
            .when(request().withPath("/disabled").withProtocol(Protocol.HTTP_1_1))
            .respond(response().withStatusCode(200).withBody("matched_http1"));
        mockServerClient
            .when(request().withPath("/disabled").withProtocol(Protocol.HTTP_2))
            .respond(response().withStatusCode(201).withBody("matched_http2"));

        // when - an HTTP/2 capable client makes a secure request
        HttpResponse response = send(
            proxyConfigurations,
            request()
                .withMethod("GET")
                .withPath("/disabled")
                .withSecure(true)
                .withProtocol(Protocol.HTTP_2)
                .withHeader(HOST.toString(), "127.0.0.1:" + mockServerPort)
        );

        // then - ALPN advertised only http/1.1 so the HTTP/1.1 expectation matched
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString(), is("matched_http1"));
    }

    @Test(timeout = 30000)
    public void shouldForceHttp1ForDirectHttp2Request() throws Exception {
        assertHttp2CapableClientIsForcedToHttp1(null);
    }

    @Test(timeout = 30000)
    public void shouldForceHttp1ForHttp2RequestViaConnectProxy() throws Exception {
        assertHttp2CapableClientIsForcedToHttp1(
            ImmutableList.of(proxyConfiguration(ProxyConfiguration.Type.HTTPS, "127.0.0.1:" + mockServerPort))
        );
    }
}
