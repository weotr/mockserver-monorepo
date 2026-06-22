package org.mockserver.examples.mockserver;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.load.LoadProfile;
import org.mockserver.load.LoadScenario;
import org.mockserver.load.LoadStage;
import org.mockserver.load.LoadStep;
import org.mockserver.load.RampCurve;
import org.mockserver.model.Delay;
import org.mockserver.model.HttpTemplate;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Runnable, self-asserting example of MockServer's <b>Load Scenario registry</b>, exercised
 * through the typed Java client API.
 *
 * <p>A "load scenario" is a named, server-side traffic generator: you register it once (its
 * profile of ramp/hold/pause stages and the request steps it drives), then start/stop it by name.
 * While running it generates synthetic traffic against the data plane and reports live
 * throughput/latency status. This test demonstrates the full registry lifecycle with the typed
 * client:
 *
 * <ul>
 *   <li>{@link MockServerClient#loadScenario(LoadScenario)} — register/upsert
 *       ({@code PUT /mockserver/loadScenario}); always allowed.</li>
 *   <li>{@link MockServerClient#startLoadScenarios(String...)} — start one/many
 *       ({@code PUT .../start}); requires {@code loadGenerationEnabled=true}.</li>
 *   <li>{@link MockServerClient#loadScenarios()} — list all ({@code GET /mockserver/loadScenario}).</li>
 *   <li>{@link MockServerClient#getLoadScenario(String)} — one scenario + live status
 *       ({@code GET .../{name}}).</li>
 *   <li>{@link MockServerClient#stopLoadScenarios(String...)} — stop one/many; no args = stop all
 *       ({@code PUT .../stop}).</li>
 *   <li>{@link MockServerClient#runLoadScenario(LoadScenario)} — register + start in one call.</li>
 *   <li>{@link MockServerClient#deleteLoadScenario(String)} /
 *       {@link MockServerClient#clearLoadScenarios()} — delete one / clear all.</li>
 * </ul>
 *
 * <h2>How it runs / validates</h2>
 * <p>
 * By default this starts its own embedded {@link ClientAndServer} <b>with load generation
 * enabled</b> ({@code configuration().loadGenerationEnabled(true)}) so it compiles and runs in CI
 * with no external server. To run it against an already-running MockServer (e.g. the Docker
 * validation harness), set {@code MOCKSERVER_HOST} (default {@code localhost}) and
 * {@code MOCKSERVER_PORT} (default {@code 1080}); that server must itself be started with
 * {@code -Dmockserver.loadGenerationEnabled=true}, otherwise starting a scenario returns HTTP 403.
 * Either way it resets at the start so the example is self-contained.
 * </p>
 *
 * @author jamesdbloom
 */
public class LoadScenarioExamples {

    private MockServerClient mockServerClient;
    private ClientAndServer embeddedServer;

    @Before
    public void connectToMockServer() {
        String host = envOrDefault("MOCKSERVER_HOST", "localhost");
        String portValue = envOrDefault("MOCKSERVER_PORT", null);
        if (portValue == null) {
            // no external server configured — start an embedded one with load generation enabled
            Configuration configuration = configuration().loadGenerationEnabled(true);
            embeddedServer = ClientAndServer.startClientAndServer(configuration, 0);
            mockServerClient = embeddedServer;
        } else {
            int port = Integer.parseInt(portValue);
            mockServerClient = new MockServerClient(host, port);
        }
        mockServerClient.reset();
    }

    @After
    public void stopEmbeddedServer() {
        if (mockServerClient != null) {
            try {
                mockServerClient.stopLoadScenarios();
                mockServerClient.clearLoadScenarios();
            } catch (Exception ignore) {
                // best-effort cleanup
            }
        }
        if (embeddedServer != null) {
            embeddedServer.stop();
            embeddedServer = null;
        } else if (mockServerClient != null) {
            mockServerClient.close();
        }
    }

    // register -> start -> list (RUNNING) -> status -> stop -> clear
    @Test
    public void registerStartListStop() throws Exception {
        // a catch-all target expectation so the generated traffic gets a 200 to measure
        mockServerClient
            .when(request().withPath("/.*"))
            .respond(response().withStatusCode(200).withBody("ok"));

        // a realistic multi-stage scenario: a linear RATE ramp (5 -> 50 req/s, capped at 50 VUs),
        // then a 25-VU hold, then a PAUSE; two Velocity-templated steps drive each iteration;
        // withStartDelayMillis defers load for half a second after the scenario is started.
        LoadScenario scenario = LoadScenario.loadScenario("checkout-load")
            .withTemplateType(HttpTemplate.TemplateType.VELOCITY)
            .withMaxRequests(100000)
            .withStartDelayMillis(500)
            .withLabels(Map.of("team", "payments", "env", "staging"))
            .withProfile(LoadProfile.of(
                LoadStage.rampRate(5, 50, 30000, RampCurve.LINEAR).withMaxVus(50),
                LoadStage.constantVus(25, 60000),
                LoadStage.pause(10000)
            ))
            .withSteps(
                LoadStep.loadStep(request().withMethod("GET").withPath("/products/$!iteration.index"))
                    .withName("browse")
                    .withThinkTime(new Delay(TimeUnit.MILLISECONDS, 500)),
                LoadStep.loadStep(request().withMethod("POST").withPath("/cart/checkout")
                        .withBody("{\"item\":\"$!iteration.index\",\"qty\":1}"))
                    .withName("checkout")
                    .withLabels(Map.of("critical", "true"))
            );

        // 1. register (does NOT start it yet)
        String registered = mockServerClient.loadScenario(scenario);
        assertTrue("register should return state LOADED, was: " + registered,
            registered.contains("LOADED"));
        System.out.println("registered \"checkout-load\"");

        // 2. start it (startLoadScenarios is varargs — one or many names). Because the
        // scenario sets startDelayMillis the start response reports PENDING (it flips to
        // RUNNING only once the delay elapses); the RUNNING state is asserted from the
        // listing below, after sleeping past the delay.
        String started = mockServerClient.startLoadScenarios("checkout-load");
        assertTrue("start should be accepted as PENDING or RUNNING (is loadGenerationEnabled=true?), was: " + started,
            started.contains("PENDING") || started.contains("RUNNING"));
        System.out.println("started \"checkout-load\"");
        Thread.sleep(1500);

        // 3. list all registered scenarios; checkout-load should be RUNNING
        String listing = mockServerClient.loadScenarios();
        assertTrue("listing should show checkout-load RUNNING, was: " + listing,
            listing.contains("checkout-load") && listing.contains("RUNNING"));
        System.out.println("listed checkout-load RUNNING");

        // one scenario's live status (throughput/latency, current stage, ...)
        String status = mockServerClient.getLoadScenario("checkout-load");
        System.out.println("status: " + status.replaceAll("\\s+", " "));

        // 4. stop it (stopLoadScenarios with no args stops ALL running scenarios)
        String stopped = mockServerClient.stopLoadScenarios("checkout-load");
        assertTrue("stop should return state STOPPED, was: " + stopped,
            stopped.contains("STOPPED"));
        System.out.println("stopped \"checkout-load\"");

        // tidy up the registry
        mockServerClient.clearLoadScenarios();

        System.out.println("PASS load_scenario=registry");
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isEmpty()) ? fallback : value;
    }
}
