package org.mockserver.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Tests for the scenario REST endpoints in HttpState:
 * - GET /mockserver/scenario/{name}
 * - PUT /mockserver/scenario/{name}
 * - PUT /mockserver/scenario/{name}/trigger
 */
public class HttpStateScenarioEndpointTest {

    private HttpState httpState;
    private final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();

    private static class FakeResponseWriter extends ResponseWriter {
        public HttpResponse response;
        public int statusCode;

        protected FakeResponseWriter() {
            super(configuration(), new MockServerLogger());
        }

        @Override
        public void sendResponse(HttpRequest request, HttpResponse response) {
            this.response = response;
            this.statusCode = response != null ? response.getStatusCode() : 0;
        }
    }

    @Before
    public void setUp() {
        Configuration configuration = configuration();
        Scheduler scheduler = new Scheduler(configuration, new MockServerLogger(configuration, HttpStateScenarioEndpointTest.class), true);
        httpState = new HttpState(configuration, new MockServerLogger(configuration, HttpStateScenarioEndpointTest.class), scheduler);
    }

    @Test
    public void shouldGetScenarioState() throws Exception {
        // given - no state set, should return "Started"
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest getRequest = request("/mockserver/scenario/myScenario")
            .withMethod("GET");

        // when
        boolean handled = httpState.handle(getRequest, responseWriter, false);

        // then
        assertThat(handled, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(200));
        JsonNode body = objectMapper.readTree(responseWriter.response.getBodyAsString());
        assertThat(body.get("scenarioName").asText(), is("myScenario"));
        assertThat(body.get("currentState").asText(), is("Started"));
    }

    @Test
    public void shouldPutScenarioStateSetImmediately() throws Exception {
        // given
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest putRequest = request("/mockserver/scenario/myScenario")
            .withMethod("PUT")
            .withBody("{\"state\": \"Running\"}");

        // when
        boolean handled = httpState.handle(putRequest, responseWriter, false);

        // then
        assertThat(handled, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(200));
        JsonNode body = objectMapper.readTree(responseWriter.response.getBodyAsString());
        assertThat(body.get("scenarioName").asText(), is("myScenario"));
        assertThat(body.get("currentState").asText(), is("Running"));

        // verify state actually changed via GET
        FakeResponseWriter getWriter = new FakeResponseWriter();
        httpState.handle(request("/mockserver/scenario/myScenario").withMethod("GET"), getWriter, false);
        JsonNode getBody = objectMapper.readTree(getWriter.response.getBodyAsString());
        assertThat(getBody.get("currentState").asText(), is("Running"));
    }

    @Test
    public void shouldPutScenarioStateWithTimedTransition() throws Exception {
        // given - synchronous scheduler fires transitions immediately
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest putRequest = request("/mockserver/scenario/myScenario")
            .withMethod("PUT")
            .withBody("{\"state\": \"Running\", \"transitionAfterMs\": 100, \"nextState\": \"Finished\"}");

        // when
        boolean handled = httpState.handle(putRequest, responseWriter, false);

        // then - response should include transition info
        assertThat(handled, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(200));
        JsonNode body = objectMapper.readTree(responseWriter.response.getBodyAsString());
        assertThat(body.get("scenarioName").asText(), is("myScenario"));
        assertThat(body.get("currentState").asText(), is("Running"));
        assertThat(body.get("nextState").asText(), is("Finished"));
        assertThat(body.get("transitionAfterMs").asLong(), is(100L));

        // verify timed transition fired (synchronous scheduler executes immediately)
        FakeResponseWriter getWriter = new FakeResponseWriter();
        httpState.handle(request("/mockserver/scenario/myScenario").withMethod("GET"), getWriter, false);
        JsonNode getBody = objectMapper.readTree(getWriter.response.getBodyAsString());
        assertThat(getBody.get("currentState").asText(), is("Finished"));
    }

    @Test
    public void shouldTriggerScenarioStateTransition() throws Exception {
        // given - set initial state
        FakeResponseWriter setWriter = new FakeResponseWriter();
        httpState.handle(
            request("/mockserver/scenario/myScenario").withMethod("PUT").withBody("{\"state\": \"Step1\"}"),
            setWriter, false
        );

        // when - trigger transition
        FakeResponseWriter triggerWriter = new FakeResponseWriter();
        HttpRequest triggerRequest = request("/mockserver/scenario/myScenario/trigger")
            .withMethod("PUT")
            .withBody("{\"newState\": \"Step3\"}");
        boolean handled = httpState.handle(triggerRequest, triggerWriter, false);

        // then
        assertThat(handled, is(true));
        assertThat(triggerWriter.response.getStatusCode(), is(200));
        JsonNode body = objectMapper.readTree(triggerWriter.response.getBodyAsString());
        assertThat(body.get("scenarioName").asText(), is("myScenario"));
        assertThat(body.get("currentState").asText(), is("Step3"));

        // verify via GET
        FakeResponseWriter getWriter = new FakeResponseWriter();
        httpState.handle(request("/mockserver/scenario/myScenario").withMethod("GET"), getWriter, false);
        JsonNode getBody = objectMapper.readTree(getWriter.response.getBodyAsString());
        assertThat(getBody.get("currentState").asText(), is("Step3"));
    }

    @Test
    public void shouldReturnErrorWhenTriggerMissingNewState() throws Exception {
        // given
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest triggerRequest = request("/mockserver/scenario/myScenario/trigger")
            .withMethod("PUT")
            .withBody("{}");

        // when
        boolean handled = httpState.handle(triggerRequest, responseWriter, false);

        // then
        assertThat(handled, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(400));
        assertThat(responseWriter.response.getBodyAsString(), containsString("newState"));
    }

    @Test
    public void shouldReturnErrorWhenPutMissingState() throws Exception {
        // given
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest putRequest = request("/mockserver/scenario/myScenario")
            .withMethod("PUT")
            .withBody("{}");

        // when
        boolean handled = httpState.handle(putRequest, responseWriter, false);

        // then
        assertThat(handled, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(400));
        assertThat(responseWriter.response.getBodyAsString(), containsString("state"));
    }

    @Test
    public void shouldReturnErrorWhenPutBodyIsEmpty() throws Exception {
        // given
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest putRequest = request("/mockserver/scenario/myScenario")
            .withMethod("PUT");

        // when
        boolean handled = httpState.handle(putRequest, responseWriter, false);

        // then
        assertThat(handled, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(400));
    }

    @Test
    public void shouldReturnErrorWhenTriggerBodyIsEmpty() throws Exception {
        // given
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest triggerRequest = request("/mockserver/scenario/myScenario/trigger")
            .withMethod("PUT");

        // when
        boolean handled = httpState.handle(triggerRequest, responseWriter, false);

        // then
        assertThat(handled, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(400));
    }

    @Test
    public void shouldHandleScenarioEndpointWithoutMockserverPrefix() throws Exception {
        // given
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest getRequest = request("/scenario/testFlow")
            .withMethod("GET");

        // when
        boolean handled = httpState.handle(getRequest, responseWriter, false);

        // then
        assertThat(handled, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(200));
        JsonNode body = objectMapper.readTree(responseWriter.response.getBodyAsString());
        assertThat(body.get("scenarioName").asText(), is("testFlow"));
        assertThat(body.get("currentState").asText(), is("Started"));
    }

    @Test
    public void shouldSetStateWithOnlyStateFieldAndNoTransition() throws Exception {
        // given
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest putRequest = request("/mockserver/scenario/myScenario")
            .withMethod("PUT")
            .withBody("{\"state\": \"Active\"}");

        // when
        boolean handled = httpState.handle(putRequest, responseWriter, false);

        // then
        assertThat(handled, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(200));
        JsonNode body = objectMapper.readTree(responseWriter.response.getBodyAsString());
        assertThat(body.has("nextState"), is(false));
        assertThat(body.has("transitionAfterMs"), is(false));
    }

    @Test
    public void shouldResetClearScenarioTimedTransitions() throws Exception {
        // given - set a scenario state
        FakeResponseWriter setWriter = new FakeResponseWriter();
        httpState.handle(
            request("/mockserver/scenario/myScenario").withMethod("PUT").withBody("{\"state\": \"Running\"}"),
            setWriter, false
        );

        // when - reset
        httpState.reset();

        // then - scenario state should be back to "Started"
        FakeResponseWriter getWriter = new FakeResponseWriter();
        httpState.handle(request("/mockserver/scenario/myScenario").withMethod("GET"), getWriter, false);
        JsonNode getBody = objectMapper.readTree(getWriter.response.getBodyAsString());
        assertThat(getBody.get("currentState").asText(), is("Started"));
    }
}
