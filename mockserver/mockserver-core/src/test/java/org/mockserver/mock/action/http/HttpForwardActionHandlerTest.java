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
import org.mockserver.proxyconfiguration.NoProxyHostsUtils;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsSame.sameInstance;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;

/**
 * @author jamesdbloom
 */
public class HttpForwardActionHandlerTest {

    private HttpForwardActionHandler httpForwardActionHandler;
    private NettyHttpClient mockHttpClient;

    @Before
    public void setupMocks() {
        mockHttpClient = mock(NettyHttpClient.class);
        MockServerLogger logFormatter = mock(MockServerLogger.class);
        httpForwardActionHandler = new HttpForwardActionHandler(logFormatter, Configuration.configuration(), mockHttpClient);
        openMocks(this);
    }

    @Test
    public void shouldHandleHttpRequests() {
        // given
        CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();
        HttpRequest httpRequest = request();
        HttpForward httpForward = forward()
            .withHost("some_host")
            .withPort(1090)
            .withScheme(HttpForward.Scheme.HTTP);
        when(mockHttpClient.sendRequest(httpRequest, InetSocketAddress.createUnresolved(httpForward.getHost(), httpForward.getPort()))).thenReturn(responseFuture);

        // when
        CompletableFuture<HttpResponse> actualHttpResponse = httpForwardActionHandler
            .handle(httpForward, httpRequest)
            .getHttpResponse();

        // then
        assertThat(actualHttpResponse, is(sameInstance(responseFuture)));
        verify(mockHttpClient).sendRequest(httpRequest.withSecure(false), InetSocketAddress.createUnresolved(httpForward.getHost(), httpForward.getPort()));
    }

    @Test
    public void shouldUpdateHostHeaderToForwardTarget() {
        CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();
        HttpRequest httpRequest = request().withHeader("Host", "localhost:1080");
        HttpForward httpForward = forward()
            .withHost("api.example.com")
            .withPort(443)
            .withScheme(HttpForward.Scheme.HTTPS);
        when(mockHttpClient.sendRequest(any(HttpRequest.class), any(InetSocketAddress.class))).thenReturn(responseFuture);

        httpForwardActionHandler.handle(httpForward, httpRequest);

        assertThat(httpRequest.getHeader("Host"), contains("api.example.com"));
    }

    @Test
    public void shouldIncludePortInHostHeaderForNonDefaultPort() {
        CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();
        HttpRequest httpRequest = request().withHeader("Host", "localhost:1080");
        HttpForward httpForward = forward()
            .withHost("api.example.com")
            .withPort(8443)
            .withScheme(HttpForward.Scheme.HTTPS);
        when(mockHttpClient.sendRequest(any(HttpRequest.class), any(InetSocketAddress.class))).thenReturn(responseFuture);

        httpForwardActionHandler.handle(httpForward, httpRequest);

        assertThat(httpRequest.getHeader("Host"), contains("api.example.com:8443"));
    }

    @Test
    public void shouldHandleSecureHttpRequests() {
        // given
        CompletableFuture<HttpResponse> httpResponse = new CompletableFuture<>();
        HttpRequest httpRequest = request();
        HttpForward httpForward = forward()
            .withHost("some_host")
            .withPort(1090)
            .withScheme(HttpForward.Scheme.HTTPS);
        when(mockHttpClient.sendRequest(httpRequest, InetSocketAddress.createUnresolved(httpForward.getHost(), httpForward.getPort()))).thenReturn(httpResponse);

        // when
        CompletableFuture<HttpResponse> actualHttpResponse = httpForwardActionHandler
            .handle(httpForward, httpRequest)
            .getHttpResponse();

        // then
        assertThat(actualHttpResponse, is(sameInstance(httpResponse)));
        verify(mockHttpClient).sendRequest(httpRequest.withSecure(true), InetSocketAddress.createUnresolved(httpForward.getHost(), httpForward.getPort()));
    }

    @Test
    public void shouldPassUnresolvedAddressToConnectPathSoDnsRunsOffTheCallingThread() {
        // given
        CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();
        HttpRequest httpRequest = request();
        HttpForward httpForward = forward()
            .withHost("api.example.com")
            .withPort(1090)
            .withScheme(HttpForward.Scheme.HTTP);
        ArgumentCaptor<InetSocketAddress> addressCaptor = ArgumentCaptor.forClass(InetSocketAddress.class);
        when(mockHttpClient.sendRequest(any(HttpRequest.class), any(InetSocketAddress.class))).thenReturn(responseFuture);

        // when
        httpForwardActionHandler.handle(httpForward, httpRequest);

        // then - the connect target is unresolved so Netty's event-loop resolver does the blocking DNS,
        // not the calling thread, while the hostname/port are preserved for SNI, pooling and host header
        verify(mockHttpClient).sendRequest(any(HttpRequest.class), addressCaptor.capture());
        InetSocketAddress captured = addressCaptor.getValue();
        assertThat(captured.isUnresolved(), is(true));
        assertThat(captured.getHostString(), is("api.example.com"));
        assertThat(captured.getPort(), is(1090));
    }

    @Test
    public void shouldBlockForwardToPrivateTargetWhenSsrfGuardEnabled() {
        // given - SSRF guard on, target resolves to a loopback/private address
        Configuration configuration = Configuration.configuration().forwardProxyBlockPrivateNetworks(true);
        HttpForwardActionHandler handler = new HttpForwardActionHandler(mock(MockServerLogger.class), configuration, mockHttpClient);
        HttpRequest httpRequest = request();
        HttpForward httpForward = forward()
            .withHost("127.0.0.1")
            .withPort(1090)
            .withScheme(HttpForward.Scheme.HTTP);

        // when
        HttpResponse response = handler.handle(httpForward, httpRequest).getHttpResponse().join();

        // then - request is rejected with a bad gateway and never forwarded
        assertThat(response.getStatusCode(), is(502));
        verify(mockHttpClient, never()).sendRequest(any(HttpRequest.class), any(InetSocketAddress.class));
    }

    @Test
    public void shouldDocumentNoProxyMatchingForUnresolvedForwardTarget() {
        // Documents the post-change no_proxy edge: forward targets are now unresolved, so the
        // connect-target InetSocketAddress carries a hostname literal but no resolved IP. The
        // hostname-form no_proxy entry still matches (via getHostString), but an IP-literal no_proxy
        // entry can no longer match a hostname target by its resolved IP (getAddress() is null), so
        // NettyHttpClient.isHostNotOnNoProxyHostList skips its IP-literal branch for such targets.

        // given - the exact connect target the handler hands to the http client
        CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();
        HttpForward httpForward = forward()
            .withHost("api.example.com")
            .withPort(1090)
            .withScheme(HttpForward.Scheme.HTTP);
        ArgumentCaptor<InetSocketAddress> addressCaptor = ArgumentCaptor.forClass(InetSocketAddress.class);
        when(mockHttpClient.sendRequest(any(HttpRequest.class), any(InetSocketAddress.class))).thenReturn(responseFuture);
        httpForwardActionHandler.handle(httpForward, request());
        verify(mockHttpClient).sendRequest(any(HttpRequest.class), addressCaptor.capture());
        InetSocketAddress forwardTarget = addressCaptor.getValue();

        // then - unresolved: no resolved IP, so the IP-literal no_proxy branch is never reached
        assertThat(forwardTarget.isUnresolved(), is(true));
        assertThat(forwardTarget.getAddress(), is(nullValue()));

        // hostname-form no_proxy entry still matches the unresolved target by its host string
        assertThat(NoProxyHostsUtils.isHostOnNoProxyList(forwardTarget.getHostString(), "api.example.com"), is(true));
        assertThat(NoProxyHostsUtils.isHostOnNoProxyList(forwardTarget.getHostString(), "*.example.com"), is(true));

        // an IP-literal no_proxy entry does NOT match the hostname target (no resolved IP to compare)
        assertThat(NoProxyHostsUtils.isHostOnNoProxyList(forwardTarget.getHostString(), "93.184.216.34"), is(false));
    }
}
