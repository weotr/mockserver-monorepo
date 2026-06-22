package org.mockserver.mock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.CaptureRule;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.serialization.model.HttpResponseDTO;
import org.mockserver.templates.engine.ScenarioStateTemplateTestLock;
import org.mockserver.templates.engine.javascript.JavaScriptTemplateEngine;
import org.slf4j.event.Level;

import java.util.Arrays;
import java.util.Collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assume.assumeThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.CaptureRule.Source.cookie;
import static org.mockserver.model.CaptureRule.Source.header;
import static org.mockserver.model.CaptureRule.Source.jsonPath;
import static org.mockserver.model.CaptureRule.Source.pathParameter;
import static org.mockserver.model.CaptureRule.Source.queryStringParameter;
import static org.mockserver.model.CaptureRule.Source.xpath;
import static org.mockserver.model.HttpRequest.request;

/**
 * WS2.2: declarative capture rules. {@link CaptureProcessor} extracts value(s)
 * from a matched request into scenario state on the live {@link ScenarioManager}
 * (wired into {@link CrossProtocolEventBus#getInstance()}), so a later request's
 * response template can read them via {@code scenario.get(name)} (WS2.1).
 *
 * @author jamesdbloom
 */
public class CaptureProcessorTest {

    private static final Configuration configuration = configuration();

    @Mock
    private MockServerLogger mockServerLogger;

    private ScenarioManager scenarioManager;
    private ScenarioManager previousScenarioManager;

    @Before
    public void setupTestFixture() {
        // Serialise against the other scenario-state template tests: all mutate the
        // process-global CrossProtocolEventBus singleton, so they must not interleave.
        ScenarioStateTemplateTestLock.LOCK.lock();
        openMocks(this);
        when(mockServerLogger.isEnabledForInstance(any(Level.class))).thenReturn(true);
        previousScenarioManager = CrossProtocolEventBus.getInstance().getScenarioManager();
        scenarioManager = new ScenarioManager();
        CrossProtocolEventBus.getInstance().setScenarioManager(scenarioManager);
    }

    @After
    public void restoreScenarioManager() {
        try {
            CrossProtocolEventBus.getInstance().setScenarioManager(previousScenarioManager);
        } finally {
            ScenarioStateTemplateTestLock.LOCK.unlock();
        }
    }

    @Test
    public void shouldCaptureJsonPathFromRequestBodyIntoScenarioState() {
        // given — a POST whose JSON body has userId=42
        HttpRequest request = request()
            .withMethod("POST")
            .withPath("/login")
            .withBody("{\"userId\": 42}");

        // when — a capture rule extracts $.userId into the "user" scenario
        CaptureProcessor.process(
            Collections.singletonList(CaptureRule.capture(jsonPath, "$.userId", "user")),
            request
        );

        // then
        assertThat(scenarioManager.getState("user"), is("42"));
    }

    @Test
    public void shouldDriveLaterResponseTemplateFromCapturedJsonPathValue() {
        // given — GraalVM JS template engine available
        assumeThat("GraalVM Polyglot API available", JavaScriptTemplateEngine.isPolyglotAvailable(), is(true));
        HttpRequest authRequest = request()
            .withMethod("POST")
            .withPath("/login")
            .withBody("{\"userId\": 42}");

        // when — the auth request captures userId into "user"
        CaptureProcessor.process(
            Collections.singletonList(CaptureRule.capture(jsonPath, "$.userId", "user")),
            authRequest
        );
        // and — a later request's response template reads scenario.get('user')
        String template = "return { 'statusCode': 200, 'body': scenario.get('user') };";
        HttpResponse response = new JavaScriptTemplateEngine(mockServerLogger, configuration)
            .executeTemplate(template, request().withPath("/resource"), HttpResponseDTO.class);

        // then — the captured value drives the later response
        assertThat(response.getBodyAsString(), is("42"));
    }

    @Test
    public void shouldCaptureHeaderValueIntoScenarioState() {
        HttpRequest request = request()
            .withPath("/resource")
            .withHeader("X-Tenant", "acme");

        CaptureProcessor.process(
            Collections.singletonList(CaptureRule.capture(header, "X-Tenant", "tenant")),
            request
        );

        assertThat(scenarioManager.getState("tenant"), is("acme"));
    }

    @Test
    public void shouldCaptureQueryStringParameterIntoScenarioState() {
        HttpRequest request = request()
            .withPath("/search")
            .withQueryStringParameter("q", "widgets");

        CaptureProcessor.process(
            Collections.singletonList(CaptureRule.capture(queryStringParameter, "q", "query")),
            request
        );

        assertThat(scenarioManager.getState("query"), is("widgets"));
    }

    @Test
    public void shouldCaptureCookieValueIntoScenarioState() {
        HttpRequest request = request()
            .withPath("/resource")
            .withCookie("session", "abc123");

        CaptureProcessor.process(
            Collections.singletonList(CaptureRule.capture(cookie, "session", "sid")),
            request
        );

        assertThat(scenarioManager.getState("sid"), is("abc123"));
    }

    @Test
    public void shouldCapturePathParameterIntoScenarioState() {
        HttpRequest request = request()
            .withPath("/users/{userId}")
            .withPathParameter("userId", "7");

        CaptureProcessor.process(
            Collections.singletonList(CaptureRule.capture(pathParameter, "userId", "user")),
            request
        );

        assertThat(scenarioManager.getState("user"), is("7"));
    }

    @Test
    public void shouldCaptureXPathFromRequestBodyIntoScenarioState() {
        HttpRequest request = request()
            .withMethod("POST")
            .withPath("/order")
            .withBody("<order><id>99</id></order>");

        CaptureProcessor.process(
            Collections.singletonList(CaptureRule.capture(xpath, "/order/id/text()", "orderId")),
            request
        );

        assertThat(scenarioManager.getState("orderId"), is("99"));
    }

    @Test
    public void shouldApplyMultipleCaptureRulesFromOneRequest() {
        HttpRequest request = request()
            .withMethod("POST")
            .withPath("/login")
            .withHeader("X-Tenant", "acme")
            .withBody("{\"userId\": 42}");

        CaptureProcessor.process(
            Arrays.asList(
                CaptureRule.capture(jsonPath, "$.userId", "user"),
                CaptureRule.capture(header, "X-Tenant", "tenant")
            ),
            request
        );

        assertThat(scenarioManager.getState("user"), is("42"));
        assertThat(scenarioManager.getState("tenant"), is("acme"));
    }

    @Test
    public void shouldNotSetStateWhenCapturedValueIsAbsent() {
        HttpRequest request = request()
            .withPath("/resource");

        CaptureProcessor.process(
            Collections.singletonList(CaptureRule.capture(header, "X-Missing", "missing")),
            request
        );

        // unset scenario reads back the implicit STARTED default, never the missing capture
        assertThat(scenarioManager.getState("missing"), is(ScenarioManager.STARTED));
    }

    @Test
    public void shouldBeNoOpForNullOrEmptyCaptureRules() {
        HttpRequest request = request().withPath("/resource").withHeader("X-Tenant", "acme");

        CaptureProcessor.process(null, request);
        CaptureProcessor.process(Collections.emptyList(), request);

        // nothing captured — getState for an arbitrary name is the unset default
        assertThat(scenarioManager.getState("tenant"), is(ScenarioManager.STARTED));
    }

    @Test
    public void shouldSwallowMalformedExpressionWithoutThrowing() {
        HttpRequest request = request()
            .withMethod("POST")
            .withPath("/login")
            .withBody("{\"userId\": 42}");

        // a malformed JSONPath must not throw and must not set state
        CaptureProcessor.process(
            Collections.singletonList(CaptureRule.capture(jsonPath, "$$$[not valid", "user")),
            request
        );

        assertThat(scenarioManager.getState("user"), is(ScenarioManager.STARTED));
        assertThat(scenarioManager.getAllStates().get("user"), is(nullValue()));
    }
}
