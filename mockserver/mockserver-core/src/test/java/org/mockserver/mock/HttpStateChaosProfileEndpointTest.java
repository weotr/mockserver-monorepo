package org.mockserver.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.ObjectMapperFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

/**
 * End-to-end routing tests for the saved chaos profile library endpoints
 * (ADV3) exercised through {@link HttpState#handle}:
 * <ul>
 *   <li>PUT    /mockserver/chaosExperiment/profiles/{name}</li>
 *   <li>GET     /mockserver/chaosExperiment/profiles</li>
 *   <li>GET     /mockserver/chaosExperiment/profiles/{name}</li>
 *   <li>POST    /mockserver/chaosExperiment/apply/{name}</li>
 *   <li>DELETE  /mockserver/chaosExperiment/profiles/{name}</li>
 * </ul>
 */
public class HttpStateChaosProfileEndpointTest {

    private HttpState httpState;
    private final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();

    private static final String DEFINITION_JSON =
        "{\"name\":\"ignored\",\"stages\":[{\"durationMillis\":30000," +
            "\"profiles\":{\"payments.svc\":{\"errorStatusCode\":503,\"errorProbability\":0.5}}}]}";

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
        Configuration configuration = configuration();
        Scheduler scheduler = new Scheduler(configuration, new MockServerLogger(configuration, HttpStateChaosProfileEndpointTest.class), true);
        httpState = new HttpState(configuration, new MockServerLogger(configuration, HttpStateChaosProfileEndpointTest.class), scheduler);
    }

    @After
    public void cleanup() {
        // stop any started experiment so it does not leak into other tests
        org.mockserver.mock.action.http.ChaosExperimentOrchestrator.getInstance().reset();
        // remove any saved profiles from the (backend-backed) library so it does not
        // leak across tests; reset() intentionally does NOT clear the profile library
        FakeResponseWriter rw = new FakeResponseWriter();
        httpState.handle(request("/mockserver/chaosExperiment/profiles/payments-outage").withMethod("DELETE"), rw, false);
        httpState.handle(request("/mockserver/chaosExperiment/profiles/looped").withMethod("DELETE"), rw, false);
    }

    private HttpResponse handle(String path, String method, String body) {
        FakeResponseWriter rw = new FakeResponseWriter();
        HttpRequest req = request(path).withMethod(method);
        if (body != null) {
            req.withBody(body);
        }
        boolean handled = httpState.handle(req, rw, false);
        assertThat("route handled: " + method + " " + path, handled, is(true));
        return rw.response;
    }

    @Test
    public void shouldSaveListApplyAndDeleteRoundTrip() throws Exception {
        // save
        HttpResponse save = handle("/mockserver/chaosExperiment/profiles/payments-outage", "PUT", DEFINITION_JSON);
        assertThat(save.getStatusCode(), is(200));
        JsonNode saveBody = objectMapper.readTree(save.getBodyAsString());
        assertThat(saveBody.get("status").asText(), is("saved"));
        assertThat(saveBody.get("name").asText(), is("payments-outage"));

        // list
        HttpResponse list = handle("/mockserver/chaosExperiment/profiles", "GET", null);
        assertThat(list.getStatusCode(), is(200));
        JsonNode listBody = objectMapper.readTree(list.getBodyAsString());
        assertThat(listBody.get("profiles").size(), is(1));
        assertThat(listBody.get("profiles").get(0).asText(), is("payments-outage"));

        // get single - name override applied
        HttpResponse get = handle("/mockserver/chaosExperiment/profiles/payments-outage", "GET", null);
        assertThat(get.getStatusCode(), is(200));
        JsonNode getBody = objectMapper.readTree(get.getBodyAsString());
        assertThat(getBody.get("name").asText(), is("payments-outage"));

        // apply - starts the experiment
        HttpResponse apply = handle("/mockserver/chaosExperiment/apply/payments-outage", "POST", null);
        assertThat(apply.getStatusCode(), is(200));
        JsonNode applyBody = objectMapper.readTree(apply.getBodyAsString());
        assertThat(applyBody.get("status").asText(), is("started"));
        assertThat(applyBody.get("name").asText(), is("payments-outage"));
        assertThat(applyBody.get("stages").asInt(), is(1));

        // delete
        HttpResponse delete = handle("/mockserver/chaosExperiment/profiles/payments-outage", "DELETE", null);
        assertThat(delete.getStatusCode(), is(200));
        assertThat(objectMapper.readTree(delete.getBodyAsString()).get("status").asText(), is("deleted"));

        // list now empty
        HttpResponse listAfter = handle("/mockserver/chaosExperiment/profiles", "GET", null);
        assertThat(objectMapper.readTree(listAfter.getBodyAsString()).get("profiles").size(), is(0));
    }

    @Test
    public void shouldReturnNotFoundApplyingUnknownProfile() throws Exception {
        HttpResponse apply = handle("/mockserver/chaosExperiment/apply/does-not-exist", "POST", null);
        assertThat(apply.getStatusCode(), is(404));
        JsonNode body = objectMapper.readTree(apply.getBodyAsString());
        assertThat(body.get("error").asText(), containsString("does-not-exist"));
    }

    @Test
    public void shouldReturnNotFoundGettingUnknownProfile() throws Exception {
        HttpResponse get = handle("/mockserver/chaosExperiment/profiles/does-not-exist", "GET", null);
        assertThat(get.getStatusCode(), is(404));
    }

    @Test
    public void shouldReturnAbsentDeletingUnknownProfile() throws Exception {
        HttpResponse delete = handle("/mockserver/chaosExperiment/profiles/does-not-exist", "DELETE", null);
        assertThat(delete.getStatusCode(), is(200));
        assertThat(objectMapper.readTree(delete.getBodyAsString()).get("status").asText(), is("absent"));
    }

    @Test
    public void shouldRejectSavingMalformedProfile() throws Exception {
        HttpResponse save = handle("/mockserver/chaosExperiment/profiles/payments-outage", "PUT", "not json");
        assertThat(save.getStatusCode(), is(400));
    }

    @Test
    public void shouldRejectSavingProfileWithNoBody() throws Exception {
        HttpResponse save = handle("/mockserver/chaosExperiment/profiles/payments-outage", "PUT", null);
        assertThat(save.getStatusCode(), is(400));
    }

    @Test
    public void shouldNotMatchProfileNameRouteForBareProfilesPath() throws Exception {
        // GET /chaosExperiment/profiles must hit the list endpoint, not the {name} route
        HttpResponse list = handle("/mockserver/chaosExperiment/profiles", "GET", null);
        assertThat(list.getStatusCode(), is(200));
        assertThat(objectMapper.readTree(list.getBodyAsString()).has("profiles"), is(true));
    }

    @Test
    public void shouldSurviveResetForSavedProfiles() throws Exception {
        handle("/mockserver/chaosExperiment/profiles/payments-outage", "PUT", DEFINITION_JSON);
        httpState.reset();
        HttpResponse list = handle("/mockserver/chaosExperiment/profiles", "GET", null);
        // reset clears active chaos but NOT the saved-profile template library
        assertThat(objectMapper.readTree(list.getBodyAsString()).get("profiles").size(), is(1));
    }
}
