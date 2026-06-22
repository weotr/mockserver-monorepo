package org.mockserver.examples.mockserver;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.matchers.Times;
import org.mockserver.mock.ResponseMode;
import org.mockserver.model.CrossProtocolScenario;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.JsonBody.json;

/**
 * Runnable, self-asserting examples of MockServer's <b>stateful scenario</b> features, exercised
 * through the typed Java client API. Each of the 5 canonical scenarios is one test method that
 * registers expectations, drives the data plane, and asserts the observed behaviour.
 *
 * <h2>What it demonstrates</h2>
 * <ol>
 *   <li><b>state_machine</b> — a login state machine using {@code withScenarioName} /
 *       {@code withScenarioState} / {@code withNewScenarioState}: an unauthenticated
 *       {@code GET /profile} returns 401 until {@code POST /login} advances the scenario.</li>
 *   <li><b>sequential_cycling</b> — one expectation with multiple responses
 *       ({@code withHttpResponses} + {@link ResponseMode#SEQUENTIAL}) cycling 200, 503, 200, then
 *       back to the first.</li>
 *   <li><b>timed_transition</b> — the scenario control-plane helper
 *       {@code scenario(name).set(state, transitionAfterMs, nextState)} auto-advancing
 *       {@code Deploying} to {@code Deployed} after a delay.</li>
 *   <li><b>external_trigger</b> — {@code scenario(name).trigger(newState)} flipping a health
 *       endpoint from 200 healthy to 503 down on demand.</li>
 *   <li><b>cross_protocol</b> — {@code withCrossProtocolScenario(...)} so that observing
 *       {@code GET /events} advances scenario {@code ConnFlow} to {@code Connected}, which unlocks a
 *       gated {@code GET /api/conn-status}. The same mechanism advances scenarios from DNS_QUERY /
 *       WEBSOCKET_CONNECT / GRPC_REQUEST events.</li>
 * </ol>
 *
 * <h2>How it runs / validates</h2>
 * <p>
 * By default this starts its own embedded {@link ClientAndServer} so it compiles and runs in CI with
 * no external server. To run it against an already-running MockServer (e.g. the Docker validation
 * harness), set the {@code MOCKSERVER_HOST} (default {@code localhost}) and {@code MOCKSERVER_PORT}
 * (default {@code 1080}) environment variables; the example then connects there instead. Either way
 * it {@code PUT /mockserver/reset}s at the start of every scenario so each is self-contained and
 * order-independent.
 * </p>
 *
 * @author jamesdbloom
 */
public class StatefulScenarioExamples {

    private MockServerClient mockServerClient;
    private ClientAndServer embeddedServer;
    private String baseUrl;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    @Before
    public void connectToMockServer() {
        String host = envOrDefault("MOCKSERVER_HOST", "localhost");
        String portValue = envOrDefault("MOCKSERVER_PORT", null);
        if (portValue == null) {
            // no external server configured — start an embedded one so the example is self-contained
            embeddedServer = ClientAndServer.startClientAndServer();
            mockServerClient = embeddedServer;
            baseUrl = "http://localhost:" + embeddedServer.getPort();
        } else {
            int port = Integer.parseInt(portValue);
            mockServerClient = new MockServerClient(host, port);
            baseUrl = "http://" + host + ":" + port;
        }
        // make every scenario self-contained and order-independent
        mockServerClient.reset();
    }

    @After
    public void stopEmbeddedServer() {
        if (embeddedServer != null) {
            embeddedServer.stop();
            embeddedServer = null;
        } else if (mockServerClient != null) {
            mockServerClient.close();
        }
    }

    // 1. state_machine — login flow driven by scenario state transitions
    @Test
    public void stateMachine() throws Exception {
        mockServerClient
            .when(request().withMethod("POST").withPath("/login"), Times.exactly(1))
            .withScenarioName("LoginFlow")
            .withScenarioState("Started")
            .withNewScenarioState("LoggedIn")
            .respond(response().withStatusCode(200).withBody(json("{\"token\":\"abc123\"}")));
        mockServerClient
            .when(request().withMethod("GET").withPath("/profile"))
            .withScenarioName("LoginFlow")
            .withScenarioState("LoggedIn")
            .respond(response().withStatusCode(200).withBody(json("{\"name\":\"Alice\"}")));
        mockServerClient
            .when(request().withMethod("GET").withPath("/profile"))
            .withScenarioName("LoginFlow")
            .withScenarioState("Started")
            .respond(response().withStatusCode(401).withBody(json("{\"error\":\"Not authenticated\"}")));

        // before login the profile is gated
        Response gated = get("/profile");
        assertEquals("GET /profile before login should be 401", 401, gated.status);
        assertTrue("401 body should report not authenticated", gated.body.contains("Not authenticated"));

        // logging in advances the scenario from Started to LoggedIn
        Response login = post("/login", "");
        assertEquals("POST /login should be 200", 200, login.status);
        assertTrue("login body should contain a token", login.body.contains("abc123"));

        // now the profile is unlocked
        Response profile = get("/profile");
        assertEquals("GET /profile after login should be 200", 200, profile.status);
        assertTrue("profile body should name Alice", profile.body.contains("Alice"));

        pass("state_machine");
    }

    // 2. sequential_cycling — multiple responses on one expectation, cycling
    @Test
    public void sequentialCycling() throws Exception {
        mockServerClient
            .when(request().withMethod("GET").withPath("/api/status"))
            .withResponseMode(ResponseMode.SEQUENTIAL)
            .withHttpResponses(Arrays.asList(
                response().withStatusCode(200).withBody(json("{\"status\":\"ok\"}")),
                response().withStatusCode(503).withBody(json("{\"status\":\"degraded\"}")),
                response().withStatusCode(200).withBody(json("{\"status\":\"ok\"}"))
            ));

        // four calls cycle through the three responses, the fourth wrapping back to the first
        assertEquals("call 1 should be 200", 200, get("/api/status").status);
        assertEquals("call 2 should be 503", 503, get("/api/status").status);
        assertEquals("call 3 should be 200", 200, get("/api/status").status);
        assertEquals("call 4 should cycle back to 200", 200, get("/api/status").status);

        pass("sequential_cycling");
    }

    // 3. timed_transition — control-plane helper with a timed auto-transition
    @Test
    public void timedTransition() throws Exception {
        mockServerClient
            .when(request().withMethod("GET").withPath("/status"))
            .withScenarioName("DeployFlow")
            .withScenarioState("Deploying")
            .respond(response().withStatusCode(200).withBody(json("{\"status\":\"deploying\"}")));
        mockServerClient
            .when(request().withMethod("GET").withPath("/status"))
            .withScenarioName("DeployFlow")
            .withScenarioState("Deployed")
            .respond(response().withStatusCode(200).withBody(json("{\"status\":\"complete\"}")));

        // set the starting state, scheduling an automatic transition to Deployed after 1s
        mockServerClient.scenario("DeployFlow").set("Deploying", 1000L, "Deployed");

        Response deploying = get("/status");
        assertEquals("/status should report deploying", 200, deploying.status);
        assertTrue("body should report deploying", deploying.body.contains("deploying"));

        // wait for the scheduled transition to fire
        Thread.sleep(1300L);

        Response complete = get("/status");
        assertEquals("/status should still be 200", 200, complete.status);
        assertTrue("body should report complete after transition", complete.body.contains("complete"));

        pass("timed_transition");
    }

    // 4. external_trigger — control-plane helper flipping state on demand
    @Test
    public void externalTrigger() throws Exception {
        mockServerClient
            .when(request().withMethod("GET").withPath("/health"))
            .withScenarioName("HealthFlow")
            .withScenarioState("Started")
            .respond(response().withStatusCode(200).withBody(json("{\"status\":\"healthy\"}")));
        mockServerClient
            .when(request().withMethod("GET").withPath("/health"))
            .withScenarioName("HealthFlow")
            .withScenarioState("Down")
            .respond(response().withStatusCode(503).withBody(json("{\"status\":\"down\"}")));

        Response healthy = get("/health");
        assertEquals("/health should be 200 while healthy", 200, healthy.status);
        assertTrue("body should report healthy", healthy.body.contains("healthy"));

        // externally trigger the scenario into the Down state
        mockServerClient.scenario("HealthFlow").trigger("Down");

        Response down = get("/health");
        assertEquals("/health should be 503 once down", 503, down.status);
        assertTrue("body should report down", down.body.contains("down"));

        pass("external_trigger");
    }

    // 5. cross_protocol — observing one request unlocks another via a cross-protocol scenario.
    // The same mechanism advances scenarios from DNS_QUERY / WEBSOCKET_CONNECT / GRPC_REQUEST events.
    @Test
    public void crossProtocol() throws Exception {
        mockServerClient
            .when(request().withMethod("GET").withPath("/events"))
            .withCrossProtocolScenario(
                CrossProtocolScenario.onHttpPath("/events", "ConnFlow", "Connected"))
            .respond(response().withStatusCode(200));
        mockServerClient
            .when(request().withMethod("GET").withPath("/api/conn-status"))
            .withScenarioName("ConnFlow")
            .withScenarioState("Connected")
            .respond(response().withStatusCode(200).withBody(json("{\"status\":\"connected\"}")));

        // before the triggering event the gated endpoint is unmatched
        Response before = get("/api/conn-status");
        assertEquals("/api/conn-status should be unmatched (404) before /events", 404, before.status);

        // the /events request fires the cross-protocol trigger advancing ConnFlow to Connected
        assertEquals("GET /events should be 200", 200, get("/events").status);

        Response after = get("/api/conn-status");
        assertEquals("/api/conn-status should be 200 once connected", 200, after.status);
        assertTrue("body should report connected", after.body.contains("connected"));

        pass("cross_protocol");
    }

    // --- data-plane helpers (JDK HttpClient, dependency-light) ---

    private Response get(String path) throws Exception {
        HttpResponse<String> r = httpClient.send(
            HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        return new Response(r.statusCode(), r.body());
    }

    private Response post(String path, String body) throws Exception {
        HttpResponse<String> r = httpClient.send(
            HttpRequest.newBuilder(URI.create(baseUrl + path))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
        return new Response(r.statusCode(), r.body());
    }

    private static void pass(String scenario) {
        System.out.println("PASS scenario=" + scenario);
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isEmpty()) ? fallback : value;
    }

    private static final class Response {
        final int status;
        final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body == null ? "" : body;
        }
    }
}
