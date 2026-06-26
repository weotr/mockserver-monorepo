package org.mockserver.mock.action.http;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockserver.configuration.Configuration;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpForward;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Protocol;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.Is.is;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;

/**
 * Verifies the opt-in {@code forwardProxyHttp2Enabled} protocol-selection policy in
 * {@link HttpForwardAction#sendRequest}: by default the forwarded request's protocol is nulled
 * (HTTP/1.1), and when enabled the inbound request's protocol is preserved.
 *
 * @author jamesdbloom
 */
public class HttpForwardActionHttp2Test {

    private NettyHttpClient mockHttpClient;
    private MockServerLogger logFormatter;

    @Before
    public void setupMocks() {
        mockHttpClient = mock(NettyHttpClient.class);
        logFormatter = mock(MockServerLogger.class);
    }

    private HttpRequest captureForwardedRequest(Configuration configuration, HttpRequest inbound) {
        HttpForwardActionHandler handler = new HttpForwardActionHandler(logFormatter, configuration, mockHttpClient);
        CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();
        when(mockHttpClient.sendRequest(any(HttpRequest.class), any(InetSocketAddress.class))).thenReturn(responseFuture);
        HttpForward httpForward = forward()
            .withHost("some_host")
            .withPort(1090)
            .withScheme(HttpForward.Scheme.HTTPS);

        handler.handle(httpForward, inbound);

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockHttpClient).sendRequest(requestCaptor.capture(), any(InetSocketAddress.class));
        return requestCaptor.getValue();
    }

    @Test
    public void shouldForceHttp1WhenFlagDisabledAndInboundIsHttp2() {
        // given - flag off (the default), inbound request is HTTP/2
        Configuration configuration = Configuration.configuration();
        assertThat(configuration.forwardProxyHttp2Enabled(), is(false));
        HttpRequest inbound = request().withProtocol(Protocol.HTTP_2);

        // when
        HttpRequest forwarded = captureForwardedRequest(configuration, inbound);

        // then - protocol is nulled so NettyHttpClient defaults to HTTP/1.1 (byte-identical to historical)
        assertThat(forwarded.getProtocol(), is(nullValue()));
    }

    @Test
    public void shouldForceHttp1WhenFlagDisabledAndInboundHasNoProtocol() {
        // given - flag off, inbound has no protocol
        Configuration configuration = Configuration.configuration();
        HttpRequest inbound = request();

        // when
        HttpRequest forwarded = captureForwardedRequest(configuration, inbound);

        // then - still nulled, HTTP/1.1
        assertThat(forwarded.getProtocol(), is(nullValue()));
    }

    @Test
    public void shouldPreserveHttp2WhenFlagEnabledAndInboundIsHttp2() {
        // given - flag on, inbound request is HTTP/2
        Configuration configuration = Configuration.configuration().forwardProxyHttp2Enabled(true);
        HttpRequest inbound = request().withProtocol(Protocol.HTTP_2);

        // when
        HttpRequest forwarded = captureForwardedRequest(configuration, inbound);

        // then - the inbound HTTP/2 protocol is preserved on the forwarded request
        assertThat(forwarded.getProtocol(), is(Protocol.HTTP_2));
    }

    @Test
    public void shouldLeaveHttp1WhenFlagEnabledButInboundHasNoProtocol() {
        // given - flag on, but inbound has no protocol marker
        Configuration configuration = Configuration.configuration().forwardProxyHttp2Enabled(true);
        HttpRequest inbound = request();

        // when
        HttpRequest forwarded = captureForwardedRequest(configuration, inbound);

        // then - nothing to preserve, so HTTP/1.1 (null protocol)
        assertThat(forwarded.getProtocol(), is(nullValue()));
    }

    @Test
    public void shouldPreserveHttp1_1WhenFlagEnabledAndInboundIsExplicitHttp1_1() {
        // given - flag on, inbound is explicit HTTP/1.1
        Configuration configuration = Configuration.configuration().forwardProxyHttp2Enabled(true);
        HttpRequest inbound = request().withProtocol(Protocol.HTTP_1_1);

        // when
        HttpRequest forwarded = captureForwardedRequest(configuration, inbound);

        // then - preserved as HTTP/1.1
        assertThat(forwarded.getProtocol(), is(Protocol.HTTP_1_1));
    }
}
