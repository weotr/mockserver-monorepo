package org.mockserver.templates.engine.velocity;

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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

/**
 * WS2.1: scenario state accessible from Velocity response templates.
 * Verifies that a template can write scenario state via {@code $scenario.set}
 * and read it back via {@code $scenario.get}, that the state written is
 * observable through the live {@link ScenarioManager}, and that a matcher state
 * check ({@code matchesState}) sees template-written state.
 *
 * @author jamesdbloom
 */
public class VelocityScenarioStateTemplateTest {

    private static final Configuration configuration = configuration();

    @Mock
    private MockServerLogger mockServerLogger;

    private ScenarioManager scenarioManager;
    private ScenarioManager previousScenarioManager;

    @Before
    public void setupTestFixture() {
        // Serialise against JavaScriptScenarioStateTemplateTest: both mutate the
        // process-global CrossProtocolEventBus singleton, so they must not
        // interleave (held until @After). See ScenarioStateTemplateTestLock.
        ScenarioStateTemplateTestLock.LOCK.lock();
        openMocks(this);
        when(mockServerLogger.isEnabledForInstance(any(Level.class))).thenReturn(true);
        // wire a fresh live ScenarioManager into the singleton bridge the
        // template helper resolves, preserving any previously-wired instance
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
    public void shouldReadUnsetScenarioStateAsStarted() {
        // given
        String template = "{" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': \"$scenario.get('flow')\"" + NEW_LINE +
            "}";
        HttpRequest request = request().withPath("/somePath");

        // when
        HttpResponse actualHttpResponse = new VelocityTemplateEngine(mockServerLogger, configuration)
            .executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse.getBodyAsString(), is("Started"));
    }

    @Test
    public void shouldSetAndGetScenarioStateWithinTemplate() {
        // given
        String template = "{" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': \"$scenario.set('flow','step2')$scenario.get('flow')\"" + NEW_LINE +
            "}";
        HttpRequest request = request().withPath("/somePath");

        // when
        HttpResponse actualHttpResponse = new VelocityTemplateEngine(mockServerLogger, configuration)
            .executeTemplate(template, request, HttpResponseDTO.class);

        // then — set() emits nothing inline, get() returns the new state
        assertThat(actualHttpResponse.getBodyAsString(), is("step2"));
    }

    @Test
    public void shouldMakeTemplateWrittenStateObservableThroughScenarioManager() {
        // given
        String template = "{" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': \"ok$scenario.set('checkout','PAID')\"" + NEW_LINE +
            "}";
        HttpRequest request = request().withPath("/pay");

        // when
        new VelocityTemplateEngine(mockServerLogger, configuration)
            .executeTemplate(template, request, HttpResponseDTO.class);

        // then — state written by the template is visible to the live manager
        assertThat(scenarioManager.getState("checkout"), is("PAID"));
        // and a matcher-style state check sees it
        assertThat(scenarioManager.matchesState("checkout", "PAID"), is(true));
    }

    @Test
    public void shouldSeeMatcherWrittenStateFromTemplate() {
        // given — matcher (or another request) transitioned the scenario earlier
        scenarioManager.setState("flow", "step3");
        String template = "{" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': \"$scenario.get('flow')\"" + NEW_LINE +
            "}";
        HttpRequest request = request().withPath("/somePath");

        // when
        HttpResponse actualHttpResponse = new VelocityTemplateEngine(mockServerLogger, configuration)
            .executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse.getBodyAsString(), is("step3"));
    }

    @Test
    public void shouldEvaluateScenarioMatchesInTemplate() {
        // given
        scenarioManager.setState("flow", "step2");
        String template = "{" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': \"#if($scenario.matches('flow','step2'))yes#{else}no#end\"" + NEW_LINE +
            "}";
        HttpRequest request = request().withPath("/somePath");

        // when
        HttpResponse actualHttpResponse = new VelocityTemplateEngine(mockServerLogger, configuration)
            .executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse.getBodyAsString(), is("yes"));
    }
}
