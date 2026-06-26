package org.mockserver.netty.integration.authenticateddataplane;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpResponse;
import org.mockserver.scheduler.Scheduler;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * End-to-end integration tests for the opt-in data-plane (mocked endpoint) authentication gate.
 *
 * <p>Configuration is supplied via a per-server {@link Configuration} instance (not the static
 * {@code ConfigurationProperties}), so this test mutates no JVM-global state and is parallel-safe —
 * it therefore does NOT need to live in the sequential Surefire phase.
 */
public class DataPlaneAuthenticationIntegrationTest {

    private static final String BASIC_USER = "user";
    private static final String BASIC_PASS = "secret";
    private static final String BEARER_TOKEN = "bearer-token-123";
    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String API_KEY_VALUE = "key-abc";

    private static ClientAndServer mockServer;
    private static EventLoopGroup clientEventLoopGroup;
    private static NettyHttpClient httpClient;

    @BeforeClass
    public static void startServer() {
        Configuration configuration = configuration()
            .dataPlaneAuthenticationRequired(true)
            .dataPlaneBasicAuthenticationUsername(BASIC_USER)
            .dataPlaneBasicAuthenticationPassword(BASIC_PASS)
            .dataPlaneBasicAuthenticationRealm("data-plane-realm")
            .dataPlaneBearerAuthenticationToken(BEARER_TOKEN)
            .dataPlaneApiKeyAuthenticationHeader(API_KEY_HEADER)
            .dataPlaneApiKeyAuthenticationValue(API_KEY_VALUE);

        mockServer = ClientAndServer.startClientAndServer(configuration);

        // The control plane is NOT gated by data-plane auth, so this expectation set-up succeeds
        // without any data-plane credentials.
        mockServer
            .when(request().withPath("/mocked"))
            .respond(response().withStatusCode(200).withBody("mocked-body"));

        clientEventLoopGroup = new NioEventLoopGroup(3, new Scheduler.SchedulerThreadFactory(DataPlaneAuthenticationIntegrationTest.class.getSimpleName() + "-eventLoop"));
        httpClient = new NettyHttpClient(configuration(), new MockServerLogger(), clientEventLoopGroup, null, false);
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(mockServer);
        if (clientEventLoopGroup != null) {
            clientEventLoopGroup.shutdownGracefully(0, 1, TimeUnit.MILLISECONDS);
        }
    }

    private HttpResponse send(org.mockserver.model.HttpRequest httpRequest) {
        try {
            int port = mockServer.getPort();
            httpRequest.withHeader("Host", "localhost:" + port);
            boolean isDebug = java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().toString().contains("-agentlib:jdwp");
            return httpClient
                .sendRequest(httpRequest, new InetSocketAddress("localhost", port))
                .get(30, isDebug ? TimeUnit.MINUTES : TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static String basicHeader(String username, String password) {
        return "Basic " + Base64.getEncoder()
            .encodeToString((username + ':' + password).getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ rejection

    @Test
    public void shouldRejectDataPlaneRequestWithNoCredentials() {
        HttpResponse response = send(request().withMethod("GET").withPath("/mocked"));

        assertThat(response.getStatusCode(), is(401));
        // Basic is configured, so the Basic challenge with the configured realm is advertised.
        assertThat(response.getFirstHeader("WWW-Authenticate"), containsString("Basic realm=\"data-plane-realm\""));
        assertThat(response.getBodyAsString(), equalTo("Unauthorized for data plane"));
        // never leak credentials in the body
        assertThat(response.getBodyAsString(), is(equalTo("Unauthorized for data plane")));
    }

    @Test
    public void shouldRejectDataPlaneRequestWithWrongBasicPassword() {
        HttpResponse response = send(request()
            .withMethod("GET").withPath("/mocked")
            .withHeader("Authorization", basicHeader(BASIC_USER, "wrong")));

        assertThat(response.getStatusCode(), is(401));
    }

    @Test
    public void shouldRejectDataPlaneRequestWithWrongBearerToken() {
        HttpResponse response = send(request()
            .withMethod("GET").withPath("/mocked")
            .withHeader("Authorization", "Bearer wrong-token"));

        assertThat(response.getStatusCode(), is(401));
    }

    @Test
    public void shouldRejectDataPlaneRequestWithWrongApiKey() {
        HttpResponse response = send(request()
            .withMethod("GET").withPath("/mocked")
            .withHeader(API_KEY_HEADER, "wrong-key"));

        assertThat(response.getStatusCode(), is(401));
    }

    // ------------------------------------------------------------------ acceptance (mock served)

    @Test
    public void shouldServeMockedResponseWithValidBasicCredentials() {
        HttpResponse response = send(request()
            .withMethod("GET").withPath("/mocked")
            .withHeader("Authorization", basicHeader(BASIC_USER, BASIC_PASS)));

        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString(), equalTo("mocked-body"));
    }

    @Test
    public void shouldServeMockedResponseWithValidBearerToken() {
        HttpResponse response = send(request()
            .withMethod("GET").withPath("/mocked")
            .withHeader("Authorization", "Bearer " + BEARER_TOKEN));

        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString(), equalTo("mocked-body"));
    }

    @Test
    public void shouldServeMockedResponseWithValidApiKey() {
        HttpResponse response = send(request()
            .withMethod("GET").withPath("/mocked")
            .withHeader(API_KEY_HEADER, API_KEY_VALUE));

        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString(), equalTo("mocked-body"));
    }

    // ------------------------------------------------------------------ control plane unaffected

    @Test
    public void shouldReachControlPlaneStatusWithoutDataPlaneCredentials() {
        // Liveness/status probe must remain reachable without data-plane credentials.
        HttpResponse response = send(request().withMethod("PUT").withPath("/mockserver/status"));

        assertThat(response.getStatusCode(), is(200));
    }

    @Test
    public void shouldAdministerServerViaControlPlaneWithoutDataPlaneCredentials() {
        // Creating a new expectation through the control plane must work with NO data-plane credentials,
        // so an operator can still administer a server whose data plane is locked down.
        HttpResponse response = send(request()
            .withMethod("PUT").withPath("/mockserver/expectation")
            .withBody("{\"httpRequest\":{\"path\":\"/admin-added\"},\"httpResponse\":{\"body\":\"added\"}}"));

        assertThat(response.getStatusCode(), is(201));

        // and the newly-added mock is then itself gated by data-plane auth
        HttpResponse unauthenticated = send(request().withMethod("GET").withPath("/admin-added"));
        assertThat(unauthenticated.getStatusCode(), is(401));

        HttpResponse authenticated = send(request()
            .withMethod("GET").withPath("/admin-added")
            .withHeader("Authorization", "Bearer " + BEARER_TOKEN));
        assertThat(authenticated.getStatusCode(), is(200));
        assertThat(authenticated.getBodyAsString(), equalTo("added"));
    }

    // ------------------------------------------------------------------ default-off (separate server)

    @Test
    public void shouldNotGateWhenDataPlaneAuthenticationDisabled() {
        ClientAndServer openServer = ClientAndServer.startClientAndServer(configuration());
        try {
            openServer
                .when(request().withPath("/open"))
                .respond(response().withStatusCode(200).withBody("open-body"));

            int port = openServer.getPort();
            HttpResponse response = httpClient
                .sendRequest(request().withMethod("GET").withPath("/open").withHeader("Host", "localhost:" + port),
                    new InetSocketAddress("localhost", port))
                .get(30, TimeUnit.SECONDS);

            assertThat(response.getStatusCode(), is(200));
            assertThat(response.getBodyAsString(), equalTo("open-body"));
            // no challenge header on a default-off server (getFirstHeader returns "" when absent)
            assertThat(response.getFirstHeader("WWW-Authenticate"), is(""));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        } finally {
            stopQuietly(openServer);
        }
    }
}
