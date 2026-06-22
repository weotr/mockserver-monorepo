package org.mockserver.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.authentication.AuthenticationHandler;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.action.http.LoadScenarioOrchestrator;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.concurrent.CompletableFuture;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * End-to-end routing tests for the load-scenario REGISTRY control plane exercised through
 * {@link HttpState#handle}:
 * <ul>
 *   <li>{@code PUT /mockserver/loadScenario} loads (registers) a scenario — it does NOT run, and is
 *       allowed even when load generation is disabled.</li>
 *   <li>{@code GET /mockserver/loadScenario} lists all registered scenarios; {@code GET .../{name}}
 *       returns one (404 if absent).</li>
 *   <li>{@code PUT /mockserver/loadScenario/start} triggers registered scenarios to run (403 when
 *       disabled, 404 for an unknown name).</li>
 *   <li>{@code PUT /mockserver/loadScenario/stop} stops running scenarios.</li>
 *   <li>{@code DELETE .../{name}} and {@code DELETE} remove from the registry.</li>
 * </ul>
 *
 * <p>State-mutating: flips the static {@code loadGenerationEnabled} property, drives the process-wide
 * {@link LoadScenarioOrchestrator} singleton, and writes to the registry, so it must run in the
 * sequential Surefire phase. A synchronous fake sender is installed so no real network traffic is
 * generated.
 */
public class HttpStateLoadScenarioEndpointTest {

    private HttpState httpState;
    private final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();

    private boolean originalEnabled;

    private static class FakeResponseWriter extends ResponseWriter {
        public HttpResponse response;

        protected FakeResponseWriter() {
            super(configuration(), new MockServerLogger());
        }

        @Override
        public void sendResponse(HttpRequest request, HttpResponse response) {
            this.response = response;
        }
    }

    @Before
    public void setUp() {
        originalEnabled = ConfigurationProperties.loadGenerationEnabled();
        LoadScenarioOrchestrator.getInstance().reset();
        // Install a synchronous fake sender so the orchestrator can start without a Netty runtime.
        LoadScenarioOrchestrator.getInstance().setSender(req -> CompletableFuture.completedFuture(response().withStatusCode(200)));
        rebuildHttpState(true);
        // start from an empty registry each test
        delete();
    }

    @After
    public void tearDown() {
        delete();
        LoadScenarioOrchestrator.getInstance().reset();
        LoadScenarioOrchestrator.getInstance().setSender(null);
        ConfigurationProperties.loadGenerationEnabled(originalEnabled);
    }

    private void rebuildHttpState(boolean enabled) {
        ConfigurationProperties.loadGenerationEnabled(enabled);
        Configuration configuration = configuration().loadGenerationEnabled(enabled);
        Scheduler scheduler = new Scheduler(configuration, new MockServerLogger(configuration, HttpStateLoadScenarioEndpointTest.class), true);
        httpState = new HttpState(configuration, new MockServerLogger(configuration, HttpStateLoadScenarioEndpointTest.class), scheduler);
        LoadScenarioOrchestrator.getInstance().setConfiguration(configuration);
    }

    private HttpResponse put(String body) {
        FakeResponseWriter rw = new FakeResponseWriter();
        HttpRequest req = request("/mockserver/loadScenario").withMethod("PUT");
        if (body != null) {
            req.withBody(body);
        }
        assertThat("route handled", httpState.handle(req, rw, false), is(true));
        return rw.response;
    }

    private HttpResponse start(String body) {
        FakeResponseWriter rw = new FakeResponseWriter();
        HttpRequest req = request("/mockserver/loadScenario/start").withMethod("PUT");
        if (body != null) {
            req.withBody(body);
        }
        assertThat("route handled", httpState.handle(req, rw, false), is(true));
        return rw.response;
    }

    private HttpResponse stop(String body) {
        FakeResponseWriter rw = new FakeResponseWriter();
        HttpRequest req = request("/mockserver/loadScenario/stop").withMethod("PUT");
        if (body != null) {
            req.withBody(body);
        }
        assertThat("route handled", httpState.handle(req, rw, false), is(true));
        return rw.response;
    }

    private HttpResponse get() {
        FakeResponseWriter rw = new FakeResponseWriter();
        assertThat("route handled", httpState.handle(request("/mockserver/loadScenario").withMethod("GET"), rw, false), is(true));
        return rw.response;
    }

    private HttpResponse getOne(String name) {
        FakeResponseWriter rw = new FakeResponseWriter();
        assertThat("route handled", httpState.handle(request("/mockserver/loadScenario/" + name).withMethod("GET"), rw, false), is(true));
        return rw.response;
    }

    private HttpResponse deleteOne(String name) {
        FakeResponseWriter rw = new FakeResponseWriter();
        assertThat("route handled", httpState.handle(request("/mockserver/loadScenario/" + name).withMethod("DELETE"), rw, false), is(true));
        return rw.response;
    }

    private HttpResponse delete() {
        FakeResponseWriter rw = new FakeResponseWriter();
        assertThat("route handled", httpState.handle(request("/mockserver/loadScenario").withMethod("DELETE"), rw, false), is(true));
        return rw.response;
    }

    private static String scenarioJson(String name) {
        return "{ \"name\": \"" + name + "\", " +
            "\"profile\": { \"stages\": [ { \"type\": \"VU\", \"vus\": 1, \"durationMillis\": 60000 } ] }, " +
            "\"steps\": [ { \"request\": { \"path\": \"/health\", \"headers\": { \"Host\": [\"target\"] } } } ] }";
    }

    private JsonNode body(HttpResponse response) throws Exception {
        return objectMapper.readTree(response.getBodyAsString());
    }

    // --- LOAD / register ---

    @Test
    public void putLoadsScenarioWithoutRunningIt() throws Exception {
        HttpResponse response = put(scenarioJson("smoke"));
        assertThat(response.getStatusCode(), is(200));
        JsonNode b = body(response);
        assertThat(b.get("status").asText(), is("loaded"));
        assertThat(b.get("name").asText(), is("smoke"));
        assertThat(b.get("state").asText(), is("LOADED"));
        // loading does not start a run
        assertThat(LoadScenarioOrchestrator.getInstance().isActive("smoke"), is(false));
    }

    @Test
    public void loadingIsAllowedWhenGenerationDisabled() throws Exception {
        rebuildHttpState(false);
        HttpResponse response = put(scenarioJson("disabled-load"));
        assertThat(response.getStatusCode(), is(200));
        assertThat(body(response).get("status").asText(), is("loaded"));
    }

    @Test
    public void loadingSameNameReplaces() throws Exception {
        put(scenarioJson("dup"));
        put(scenarioJson("dup"));
        JsonNode list = body(get()).get("scenarios");
        long count = 0;
        for (JsonNode s : list) {
            if ("dup".equals(s.get("name").asText())) {
                count++;
            }
        }
        assertThat("loading the same name replaces, not appends", count, is(1L));
    }

    @Test
    public void getListsAllRegisteredScenariosAsLoaded() throws Exception {
        put(scenarioJson("one"));
        put(scenarioJson("two"));
        JsonNode list = body(get()).get("scenarios");
        assertThat(list.isArray(), is(true));
        assertThat(list.size(), is(2));
        boolean foundOne = false;
        for (JsonNode s : list) {
            if ("one".equals(s.get("name").asText())) {
                foundOne = true;
                assertThat(s.get("state").asText(), is("LOADED"));
                assertThat(s.get("definition").get("name").asText(), is("one"));
            }
        }
        assertThat(foundOne, is(true));
    }

    @Test
    public void getOneReturnsScenario() throws Exception {
        put(scenarioJson("single"));
        HttpResponse response = getOne("single");
        assertThat(response.getStatusCode(), is(200));
        JsonNode b = body(response);
        assertThat(b.get("name").asText(), is("single"));
        assertThat(b.get("state").asText(), is("LOADED"));
        assertThat(b.get("definition").get("steps").get(0).get("request").get("path").asText(), is("/health"));
    }

    @Test
    public void getOneReturns404WhenUnknown() throws Exception {
        HttpResponse response = getOne("missing");
        assertThat(response.getStatusCode(), is(404));
        assertThat(body(response).get("error").asText(), containsString("missing"));
    }

    @Test
    public void deleteOneRemovesFromRegistry() throws Exception {
        put(scenarioJson("removable"));
        HttpResponse response = deleteOne("removable");
        assertThat(response.getStatusCode(), is(200));
        assertThat(body(response).get("status").asText(), is("deleted"));
        assertThat(getOne("removable").getStatusCode(), is(404));
    }

    @Test
    public void deleteAllClearsRegistry() throws Exception {
        put(scenarioJson("a"));
        put(scenarioJson("b"));
        HttpResponse response = delete();
        assertThat(response.getStatusCode(), is(200));
        assertThat(body(get()).get("scenarios").size(), is(0));
    }

    // --- TRIGGER (start) ---

    @Test
    public void startTriggersOneScenarioByName() throws Exception {
        put(scenarioJson("runnable"));
        HttpResponse response = start("{ \"name\": \"runnable\" }");
        try {
            assertThat(response.getStatusCode(), is(200));
            JsonNode b = body(response);
            assertThat(b.get("status").asText(), is("started"));
            JsonNode started = b.get("started");
            assertThat(started.get(0).get("name").asText(), is("runnable"));
            assertThat(started.get(0).get("state").asText(), is("RUNNING"));
            assertThat(LoadScenarioOrchestrator.getInstance().isActive("runnable"), is(true));
        } finally {
            stop(null);
        }
    }

    @Test
    public void startTriggersMultipleScenariosByName() throws Exception {
        put(scenarioJson("multi-a"));
        put(scenarioJson("multi-b"));
        HttpResponse response = start("{ \"names\": [ \"multi-a\", \"multi-b\" ] }");
        try {
            assertThat(response.getStatusCode(), is(200));
            assertThat(body(response).get("started").size(), is(2));
            assertThat(LoadScenarioOrchestrator.getInstance().isActive("multi-a"), is(true));
            assertThat(LoadScenarioOrchestrator.getInstance().isActive("multi-b"), is(true));
        } finally {
            stop(null);
        }
    }

    @Test
    public void startReturns403WhenGenerationDisabled() throws Exception {
        rebuildHttpState(false);
        put(scenarioJson("disabled-start"));
        HttpResponse response = start("{ \"name\": \"disabled-start\" }");
        assertThat(response.getStatusCode(), is(403));
        assertThat(body(response).get("error").asText(), containsString("load generation not enabled"));
    }

    @Test
    public void startReturns404ForUnknownName() throws Exception {
        HttpResponse response = start("{ \"name\": \"never-loaded\" }");
        assertThat(response.getStatusCode(), is(404));
        assertThat(body(response).get("error").asText(), containsString("never-loaded"));
    }

    @Test
    public void startRejectsWhenExceedingConcurrentCap() throws Exception {
        int original = ConfigurationProperties.loadGenerationMaxConcurrentScenarios();
        ConfigurationProperties.loadGenerationMaxConcurrentScenarios(1);
        try {
            rebuildHttpState(true);
            put(scenarioJson("cap-1"));
            put(scenarioJson("cap-2"));
            assertThat(start("{ \"name\": \"cap-1\" }").getStatusCode(), is(200));
            HttpResponse response = start("{ \"name\": \"cap-2\" }");
            assertThat(response.getStatusCode(), is(400));
            assertThat(body(response).get("error").asText(), containsString("maximum of 1"));
        } finally {
            stop(null);
            ConfigurationProperties.loadGenerationMaxConcurrentScenarios(original);
        }
    }

    // --- STOP ---

    @Test
    public void stopAllStopsRunningScenarios() throws Exception {
        put(scenarioJson("stoppable"));
        start("{ \"name\": \"stoppable\" }");
        assertThat(LoadScenarioOrchestrator.getInstance().isActive("stoppable"), is(true));

        HttpResponse response = stop(null);
        assertThat(response.getStatusCode(), is(200));
        assertThat(body(response).get("status").asText(), is("stopped"));
        assertThat(LoadScenarioOrchestrator.getInstance().isActive("stoppable"), is(false));
        // remains registered (STOPPED) and re-startable
        assertThat(getOne("stoppable").getStatusCode(), is(200));
        assertThat(body(getOne("stoppable")).get("state").asText(), is("STOPPED"));
    }

    @Test
    public void stoppedScenarioCanBeReStarted() throws Exception {
        put(scenarioJson("restart"));
        start("{ \"name\": \"restart\" }");
        stop("{ \"names\": [ \"restart\" ] }");
        assertThat(LoadScenarioOrchestrator.getInstance().isActive("restart"), is(false));

        HttpResponse reStart = start("{ \"name\": \"restart\" }");
        try {
            assertThat(reStart.getStatusCode(), is(200));
            assertThat(LoadScenarioOrchestrator.getInstance().isActive("restart"), is(true));
        } finally {
            stop(null);
        }
    }

    // --- validation / auth ---

    @Test
    public void putReturns400ForMalformedBody() {
        HttpResponse response = put("{ not valid json");
        assertThat(response.getStatusCode(), is(400));
        assertThat(response.getBodyAsString(), containsString("invalid load scenario definition"));
    }

    @Test
    public void putReturns400ForBlankBody() {
        HttpResponse response = put(null);
        assertThat(response.getStatusCode(), is(400));
    }

    @Test
    public void putReturns400WhenCapExceeded() throws Exception {
        ConfigurationProperties.loadGenerationMaxVirtualUsers(2);
        try {
            rebuildHttpState(true);
            String b = "{ \"name\": \"big\", " +
                "\"profile\": { \"stages\": [ { \"type\": \"VU\", \"vus\": 100, \"durationMillis\": 1000 } ] }, " +
                "\"steps\": [ { \"request\": { \"path\": \"/x\" } } ] }";
            HttpResponse response = put(b);
            assertThat(response.getStatusCode(), is(400));
            assertThat(response.getBodyAsString(), containsString("exceeding the maximum of 2"));
        } finally {
            ConfigurationProperties.loadGenerationMaxVirtualUsers(50);
        }
    }

    @Test
    public void putRequiresControlPlaneAuthentication() {
        AuthenticationHandler rejectingHandler = req -> false;
        httpState.setControlPlaneAuthenticationHandler(rejectingHandler);

        HttpResponse response = put(scenarioJson("auth"));
        assertThat(response.getStatusCode(), is(401));
        assertThat(response.getBodyAsString(), containsString("Unauthorized for control plane"));
    }

    @Test
    public void startDelayIsEchoedInListing() throws Exception {
        String b = "{ \"name\": \"delayed\", \"startDelayMillis\": 2000, " +
            "\"profile\": { \"stages\": [ { \"type\": \"VU\", \"vus\": 1, \"durationMillis\": 60000 } ] }, " +
            "\"steps\": [ { \"request\": { \"path\": \"/health\", \"headers\": { \"Host\": [\"target\"] } } } ] }";
        put(b);
        JsonNode one = body(getOne("delayed"));
        assertThat(one.get("startDelayMillis").asLong(), is(2000L));
        assertThat(one.get("definition").get("startDelayMillis").asLong(), is(2000L));
    }
}
