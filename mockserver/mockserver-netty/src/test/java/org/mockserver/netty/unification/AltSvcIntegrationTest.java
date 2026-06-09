package org.mockserver.netty.unification;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.junit.After;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.netty.MockServer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Integration tests for Alt-Svc header advertisement on the TCP response path.
 * <p>
 * These tests start a real MockServer and verify that the Alt-Svc header is
 * correctly present (or absent) in HTTP/1.1 responses depending on the
 * http3Port configuration.
 */
public class AltSvcIntegrationTest {

    private MockServer mockServer;
    private MockServerClient client;

    @After
    public void tearDown() {
        if (client != null) {
            client.close();
            client = null;
        }
        if (mockServer != null) {
            mockServer.stop();
            mockServer = null;
        }
    }

    @Test
    public void shouldAddAltSvcHeaderWhenHttp3PortIsSet() throws Exception {
        // given - MockServer with http3Port set (does not need QUIC to actually start;
        // the Alt-Svc header is added to TCP responses based on the config value alone)
        Configuration config = configuration()
            .http3Port(8443)
            .http3AltSvcMaxAge(3600L);

        mockServer = new MockServer(config, 0);
        int tcpPort = mockServer.getLocalPort();

        client = new MockServerClient("127.0.0.1", tcpPort);
        client.when(
            request().withMethod("GET").withPath("/test")
        ).respond(
            response().withStatusCode(200).withBody("hello")
        );

        // when
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpGet httpGet = new HttpGet("http://127.0.0.1:" + tcpPort + "/test");
            HttpResponse httpResponse = httpClient.execute(httpGet);

            // then
            assertThat("status should be 200", httpResponse.getStatusLine().getStatusCode(), is(200));
            assertThat("response should contain alt-svc header",
                httpResponse.getFirstHeader("alt-svc"), is(notNullValue()));
            assertThat("alt-svc header value should advertise h3",
                httpResponse.getFirstHeader("alt-svc").getValue(),
                is("h3=\":8443\"; ma=3600"));
        }
    }

    @Test
    public void shouldNotAddAltSvcHeaderWhenHttp3PortIsZero() throws Exception {
        // given - MockServer with http3Port=0 (default, disabled)
        Configuration config = configuration()
            .http3Port(0);

        mockServer = new MockServer(config, 0);
        int tcpPort = mockServer.getLocalPort();

        client = new MockServerClient("127.0.0.1", tcpPort);
        client.when(
            request().withMethod("GET").withPath("/test")
        ).respond(
            response().withStatusCode(200).withBody("hello")
        );

        // when
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpGet httpGet = new HttpGet("http://127.0.0.1:" + tcpPort + "/test");
            HttpResponse httpResponse = httpClient.execute(httpGet);

            // then
            assertThat("status should be 200", httpResponse.getStatusLine().getStatusCode(), is(200));
            assertThat("response should NOT contain alt-svc header when http3Port is 0",
                httpResponse.getFirstHeader("alt-svc"), is(nullValue()));
        }
    }

    @Test
    public void shouldNotAddAltSvcHeaderWhenAdvertisingDisabled() throws Exception {
        // given - MockServer with http3Port set but advertisement explicitly disabled
        Configuration config = configuration()
            .http3Port(8443)
            .http3AdvertiseAltSvc(false);

        mockServer = new MockServer(config, 0);
        int tcpPort = mockServer.getLocalPort();

        client = new MockServerClient("127.0.0.1", tcpPort);
        client.when(
            request().withMethod("GET").withPath("/test")
        ).respond(
            response().withStatusCode(200).withBody("hello")
        );

        // when
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpGet httpGet = new HttpGet("http://127.0.0.1:" + tcpPort + "/test");
            HttpResponse httpResponse = httpClient.execute(httpGet);

            // then
            assertThat("status should be 200", httpResponse.getStatusLine().getStatusCode(), is(200));
            assertThat("response should NOT contain alt-svc header when advertising is disabled",
                httpResponse.getFirstHeader("alt-svc"), is(nullValue()));
        }
    }

    @Test
    public void shouldNotClobberUserSetAltSvcHeader() throws Exception {
        // given - MockServer with http3Port set AND an expectation that explicitly sets alt-svc
        Configuration config = configuration()
            .http3Port(8443)
            .http3AltSvcMaxAge(86400L);

        mockServer = new MockServer(config, 0);
        int tcpPort = mockServer.getLocalPort();

        client = new MockServerClient("127.0.0.1", tcpPort);
        client.when(
            request().withMethod("GET").withPath("/custom-alt-svc")
        ).respond(
            response()
                .withStatusCode(200)
                .withHeader("alt-svc", "h3=\":9443\"; ma=7200")
                .withBody("custom")
        );

        // when
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpGet httpGet = new HttpGet("http://127.0.0.1:" + tcpPort + "/custom-alt-svc");
            HttpResponse httpResponse = httpClient.execute(httpGet);

            // then
            assertThat("status should be 200", httpResponse.getStatusLine().getStatusCode(), is(200));
            assertThat("user-set alt-svc should be preserved",
                httpResponse.getFirstHeader("alt-svc").getValue(),
                is("h3=\":9443\"; ma=7200"));
        }
    }

    @Test
    public void shouldUseDefaultMaxAgeWhenNotExplicitlyConfigured() throws Exception {
        // given - MockServer with http3Port set, using default max-age (86400)
        Configuration config = configuration()
            .http3Port(443);

        mockServer = new MockServer(config, 0);
        int tcpPort = mockServer.getLocalPort();

        client = new MockServerClient("127.0.0.1", tcpPort);
        client.when(
            request().withMethod("GET").withPath("/default-max-age")
        ).respond(
            response().withStatusCode(200).withBody("ok")
        );

        // when
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpGet httpGet = new HttpGet("http://127.0.0.1:" + tcpPort + "/default-max-age");
            HttpResponse httpResponse = httpClient.execute(httpGet);

            // then
            assertThat("status should be 200", httpResponse.getStatusLine().getStatusCode(), is(200));
            assertThat("alt-svc should use default max-age of 86400",
                httpResponse.getFirstHeader("alt-svc").getValue(),
                is("h3=\":443\"; ma=86400"));
        }
    }
}
