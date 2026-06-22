package org.mockserver.client;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.model.HttpRequest;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsCollectionWithSize.hasSize;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;
import static org.mockserver.model.HttpResponse.response;

/**
 * Verifies the typed scenario control-plane helper on {@link MockServerClient} issues the correct
 * HTTP method, path, and body, and parses scenario responses.
 *
 * @author jamesdbloom
 */
public class MockServerClientScenarioHelperTest {

    @Mock
    private NettyHttpClient mockHttpClient;
    @InjectMocks
    private MockServerClient mockServerClient;

    @Captor
    ArgumentCaptor<HttpRequest> httpRequestArgumentCaptor;

    @Before
    public void setupFixture() {
        mockServerClient = new MockServerClient("localhost", 1090);
        openMocks(this);
    }

    @Test
    public void shouldGetScenarioState() {
        // given
        when(mockHttpClient.sendRequest(any(HttpRequest.class), anyLong(), any(TimeUnit.class), anyBoolean()))
            .thenReturn(response().withBody("{\"scenarioName\":\"Deploy\",\"currentState\":\"Deploying\"}"));

        // when
        String state = mockServerClient.scenario("Deploy").state();

        // then
        assertThat(state, is("Deploying"));
        verify(mockHttpClient, atLeastOnce()).sendRequest(
            httpRequestArgumentCaptor.capture(), anyLong(), any(TimeUnit.class), anyBoolean());
        HttpRequest sent = lastSentTo("/mockserver/scenario/Deploy");
        assertThat(sent.getMethod().getValue(), is("GET"));
    }

    @Test
    public void shouldSetScenarioState() {
        // given
        when(mockHttpClient.sendRequest(any(HttpRequest.class), anyLong(), any(TimeUnit.class), anyBoolean()))
            .thenReturn(response().withBody("{\"scenarioName\":\"Deploy\",\"currentState\":\"Deploying\"}"));

        // when
        mockServerClient.scenario("Deploy").set("Deploying");

        // then
        verify(mockHttpClient, atLeastOnce()).sendRequest(
            httpRequestArgumentCaptor.capture(), anyLong(), any(TimeUnit.class), anyBoolean());
        HttpRequest sent = lastSentTo("/mockserver/scenario/Deploy");
        assertThat(sent.getMethod().getValue(), is("PUT"));
        assertThat(sent.getBodyAsString(), containsString("\"state\":\"Deploying\""));
    }

    @Test
    public void shouldSetScenarioStateWithTimedTransition() {
        // given
        when(mockHttpClient.sendRequest(any(HttpRequest.class), anyLong(), any(TimeUnit.class), anyBoolean()))
            .thenReturn(response().withBody("{\"scenarioName\":\"Deploy\",\"currentState\":\"Deploying\"}"));

        // when
        mockServerClient.scenario("Deploy").set("Deploying", 5000L, "Deployed");

        // then
        verify(mockHttpClient, atLeastOnce()).sendRequest(
            httpRequestArgumentCaptor.capture(), anyLong(), any(TimeUnit.class), anyBoolean());
        HttpRequest sent = lastSentTo("/mockserver/scenario/Deploy");
        assertThat(sent.getMethod().getValue(), is("PUT"));
        String body = sent.getBodyAsString();
        assertThat(body, containsString("\"state\":\"Deploying\""));
        assertThat(body, containsString("\"transitionAfterMs\":5000"));
        assertThat(body, containsString("\"nextState\":\"Deployed\""));
    }

    @Test
    public void shouldTriggerScenarioState() {
        // given
        when(mockHttpClient.sendRequest(any(HttpRequest.class), anyLong(), any(TimeUnit.class), anyBoolean()))
            .thenReturn(response().withBody("{\"scenarioName\":\"Deploy\",\"currentState\":\"Failed\"}"));

        // when
        mockServerClient.scenario("Deploy").trigger("Failed");

        // then
        verify(mockHttpClient, atLeastOnce()).sendRequest(
            httpRequestArgumentCaptor.capture(), anyLong(), any(TimeUnit.class), anyBoolean());
        HttpRequest sent = lastSentTo("/mockserver/scenario/Deploy/trigger");
        assertThat(sent.getMethod().getValue(), is("PUT"));
        assertThat(sent.getBodyAsString(), containsString("\"newState\":\"Failed\""));
    }

    @Test
    public void shouldListScenarios() {
        // given
        when(mockHttpClient.sendRequest(any(HttpRequest.class), anyLong(), any(TimeUnit.class), anyBoolean()))
            .thenReturn(response().withBody("{\"scenarios\":[" +
                "{\"scenarioName\":\"Deploy\",\"currentState\":\"Deploying\"}," +
                "{\"scenarioName\":\"Login\",\"currentState\":null}]}"));

        // when
        List<MockServerClient.Scenario> scenarios = mockServerClient.scenarios();

        // then
        assertThat(scenarios, hasSize(2));
        assertThat(scenarios.get(0).scenarioName(), is("Deploy"));
        assertThat(scenarios.get(0).currentState(), is("Deploying"));
        assertThat(scenarios.get(1).scenarioName(), is("Login"));
        assertThat(scenarios.get(1).currentState(), is((String) null));
        verify(mockHttpClient, atLeastOnce()).sendRequest(
            httpRequestArgumentCaptor.capture(), anyLong(), any(TimeUnit.class), anyBoolean());
        HttpRequest sent = lastSentTo("/mockserver/scenario");
        assertThat(sent.getMethod().getValue(), is("GET"));
    }

    @Test
    public void shouldRejectBlankScenarioName() {
        assertThrows(IllegalArgumentException.class, () -> mockServerClient.scenario(""));
    }

    private HttpRequest lastSentTo(String path) {
        return httpRequestArgumentCaptor.getAllValues().stream()
            .filter(r -> path.equals(r.getPath().getValue()))
            .reduce((first, second) -> second)
            .orElseThrow(() -> new AssertionError("no request was sent to " + path));
    }
}
