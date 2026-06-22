package org.mockserver.templates.engine.javascript;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.CrossProtocolEventBus;
import org.mockserver.mock.ScenarioManager;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.serialization.model.HttpResponseDTO;
import org.mockserver.templates.engine.ScenarioStateTemplateTestLock;
import org.slf4j.event.Level;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assume.assumeThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

/**
 * WS2.1: scenario state accessible from JavaScript response templates.
 * The {@code scenario} helper is injected as a host object, so templates call
 * {@code scenario.set('flow','step2')} / {@code scenario.get('flow')} directly.
 * Verifies template-written state is observable through the live
 * {@link ScenarioManager} and via a matcher-style {@code matchesState} check.
 *
 * @author jamesdbloom
 */
public class JavaScriptScenarioStateTemplateTest {

    private static final Configuration configuration = configuration();

    @Mock
    private MockServerLogger mockServerLogger;

    private ScenarioManager scenarioManager;
    private ScenarioManager previousScenarioManager;

    @Before
    public void setupTestFixture() {
        // Serialise against VelocityScenarioStateTemplateTest: both mutate the
        // process-global CrossProtocolEventBus singleton, so they must not
        // interleave (held until @After). See ScenarioStateTemplateTestLock.
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

    private static void graalJsAvailable() {
        assumeThat("GraalVM Polyglot API available", JavaScriptTemplateEngine.isPolyglotAvailable(), is(true));
    }

    @Test
    public void shouldDriveLaterResponseFromStateSetByEarlierRequest() {
        // given — the WS2.1 pattern: one request captures/sets state, a later request reads it
        graalJsAvailable();
        JavaScriptTemplateEngine engine = new JavaScriptTemplateEngine(mockServerLogger, configuration);
        String firstTemplate = "scenario.set('flow', 'step2');" + NEW_LINE +
            "return { 'statusCode': 200, 'body': 'ok' };";
        String secondTemplate = "return { 'statusCode': 200, 'body': scenario.get('flow') };";
        HttpRequest request = request().withPath("/somePath");

        // when — first request writes the state
        engine.executeTemplate(firstTemplate, request, HttpResponseDTO.class);
        // and — a later request reads the state to drive its response
        HttpResponse secondResponse = engine.executeTemplate(secondTemplate, request, HttpResponseDTO.class);

        // then
        assertThat(secondResponse.getBodyAsString(), is("step2"));
    }

    @Test
    public void shouldMakeTemplateWrittenStateObservableThroughScenarioManager() {
        // given
        graalJsAvailable();
        String template = "scenario.set('checkout', 'PAID');" + NEW_LINE +
            "return { 'statusCode': 200, 'body': 'ok' };";
        HttpRequest request = request().withPath("/pay");

        // when
        new JavaScriptTemplateEngine(mockServerLogger, configuration)
            .executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(scenarioManager.getState("checkout"), is("PAID"));
        assertThat(scenarioManager.matchesState("checkout", "PAID"), is(true));
    }

    @Test
    public void shouldSeeMatcherWrittenStateFromTemplate() {
        // given — state written outside the template (e.g. by a matcher transition)
        graalJsAvailable();
        scenarioManager.setState("flow", "step3");
        String template = "return { 'statusCode': 200, 'body': scenario.get('flow') };";
        HttpRequest request = request().withPath("/somePath");

        // when
        HttpResponse actualHttpResponse = new JavaScriptTemplateEngine(mockServerLogger, configuration)
            .executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse.getBodyAsString(), is("step3"));
    }

    @Test
    public void shouldEvaluateScenarioMatchesInTemplate() {
        // given
        graalJsAvailable();
        scenarioManager.setState("flow", "step2");
        String template = "return { 'statusCode': 200, 'body': scenario.matches('flow','step2') ? 'yes' : 'no' };";
        HttpRequest request = request().withPath("/somePath");

        // when
        HttpResponse actualHttpResponse = new JavaScriptTemplateEngine(mockServerLogger, configuration)
            .executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse.getBodyAsString(), is("yes"));
    }
}
