package org.mockserver.netty.integration.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.load.LoadProfile;
import org.mockserver.load.LoadScenario;
import org.mockserver.load.LoadStep;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.netty.MockServer;
import org.mockserver.serialization.LoadScenarioSerializer;
import org.mockserver.socket.PortFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * End-to-end coverage for the load-scenario registry control plane: a "driver" MockServer (with
 * {@code loadGenerationEnabled=true}) is told via {@code PUT /mockserver/loadScenario} to LOAD
 * (register) a CONSTANT 5-VU/3s scenario whose single step forwards to a separate "target"
 * MockServer, then to TRIGGER it via {@code PUT /mockserver/loadScenario/start}. We poll
 * {@code GET /mockserver/loadScenario/{name}}, then assert that a meaningful number of requests
 * landed on the target and that the driver's status counters were populated.
 */
public class LoadScenarioIntegrationTest {

    private static final int DRIVER_PORT = PortFactory.findFreePort();
    private static final int TARGET_PORT = PortFactory.findFreePort();
    private static MockServer driver;
    private static MockServer target;
    private static MockServerClient driverClient;
    private static MockServerClient targetClient;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    @BeforeClass
    public static void startServers() {
        ConfigurationProperties.loadGenerationEnabled(true);
        driver = new MockServer(DRIVER_PORT);
        target = new MockServer(TARGET_PORT);
        driverClient = new MockServerClient("localhost", DRIVER_PORT);
        targetClient = new MockServerClient("localhost", TARGET_PORT);
    }

    @AfterClass
    public static void stopServers() {
        ConfigurationProperties.loadGenerationEnabled(false);
        if (driverClient != null) {
            driverClient.stop();
        }
        if (targetClient != null) {
            targetClient.stop();
        }
        if (driver != null) {
            driver.stop();
        }
        if (target != null) {
            target.stop();
        }
    }

    @Before
    public void seed() {
        targetClient.reset();
        targetClient
            .when(request().withPath("/load/.*"))
            .respond(response().withStatusCode(200).withBody("ok"));
    }

    @After
    public void stopScenario() throws Exception {
        send("PUT", "/mockserver/loadScenario/stop", "{ \"all\": true }");
        send("DELETE", "/mockserver/loadScenario", null);
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + DRIVER_PORT + path))
            .timeout(Duration.ofSeconds(10));
        if ("PUT".equals(method)) {
            builder.PUT(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        } else if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.DELETE();
        }
        return HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    public void loadsThenTriggersConstantLoadAtTargetAndPopulatesStatus() throws Exception {
        // given a CONSTANT 5-VU / 3s scenario whose step forwards to the target MockServer
        LoadScenario scenario = new LoadScenario()
            .withName("integration-constant")
            .withProfile(LoadProfile.constant(5, 3_000L))
            .withSteps(new LoadStep().withRequest(
                request()
                    .withMethod("GET")
                    .withPath("/load/$iteration.index")
                    .withSocketAddress("localhost", TARGET_PORT, org.mockserver.model.SocketAddress.Scheme.HTTP)
                    .withHeader("Host", "localhost:" + TARGET_PORT)));
        String json = new LoadScenarioSerializer(new MockServerLogger()).serialize(scenario);

        // when LOADED (registered, not run)
        HttpResponse<String> putResponse = send("PUT", "/mockserver/loadScenario", json);
        assertThat(putResponse.statusCode(), is(200));
        assertThat(OBJECT_MAPPER.readTree(putResponse.body()).get("status").asText(), is("loaded"));
        assertThat(OBJECT_MAPPER.readTree(putResponse.body()).get("state").asText(), is("LOADED"));

        // and then TRIGGERED to start
        HttpResponse<String> startResponse = send("PUT", "/mockserver/loadScenario/start", "{ \"name\": \"integration-constant\" }");
        assertThat(startResponse.statusCode(), is(200));
        assertThat(OBJECT_MAPPER.readTree(startResponse.body()).get("status").asText(), is("started"));

        // poll GET {name} until requests have landed (bounded wait)
        long deadline = System.currentTimeMillis() + 8_000L;
        long requestsSent = 0;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> getResponse = send("GET", "/mockserver/loadScenario/integration-constant", null);
            assertThat(getResponse.statusCode(), is(200));
            JsonNode status = OBJECT_MAPPER.readTree(getResponse.body());
            requestsSent = status.has("requestsSent") ? status.get("requestsSent").asLong() : 0;
            if (requestsSent >= 20) {
                break;
            }
            Thread.sleep(50);
        }

        // then the driver dispatched a meaningful number of requests
        assertThat("driver should have sent load requests", requestsSent, greaterThanOrEqualTo(20L));

        // and the target actually received them (verify at least N landed)
        targetClient.verify(request().withPath("/load/.*"),
            org.mockserver.verify.VerificationTimes.atLeast(15));

        // and the status reports RUNNING/COMPLETED with succeeded counters populated
        HttpResponse<String> finalStatus = send("GET", "/mockserver/loadScenario/integration-constant", null);
        JsonNode finalStatusNode = OBJECT_MAPPER.readTree(finalStatus.body());
        assertThat(finalStatusNode.get("state").asText(), anyOf(is("RUNNING"), is("COMPLETED")));
        assertThat(finalStatusNode.get("succeeded").asLong(), greaterThan(0L));
        assertThat(finalStatusNode.has("p50Millis"), is(true));
    }

    @Test
    public void loadAllowedButStartReturns403WhenDisabled() throws Exception {
        ConfigurationProperties.loadGenerationEnabled(false);
        try {
            LoadScenario scenario = new LoadScenario()
                .withName("disabled")
                .withProfile(LoadProfile.constant(1, 1_000L))
                .withSteps(new LoadStep().withRequest(request().withPath("/x")
                    .withSocketAddress("localhost", TARGET_PORT, org.mockserver.model.SocketAddress.Scheme.HTTP)));
            String json = new LoadScenarioSerializer(new MockServerLogger()).serialize(scenario);
            // loading is allowed even when generation is disabled
            HttpResponse<String> putResponse = send("PUT", "/mockserver/loadScenario", json);
            assertThat(putResponse.statusCode(), is(200));
            // but triggering a start is forbidden
            HttpResponse<String> startResponse = send("PUT", "/mockserver/loadScenario/start", "{ \"name\": \"disabled\" }");
            assertThat(startResponse.statusCode(), is(403));
        } finally {
            ConfigurationProperties.loadGenerationEnabled(true);
        }
    }
}
