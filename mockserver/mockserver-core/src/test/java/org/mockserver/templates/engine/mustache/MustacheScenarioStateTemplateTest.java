package org.mockserver.templates.engine.mustache;

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
 * Scenario state read by name from a Mustache response template. jmustache cannot invoke a helper
 * method with an argument the way Velocity ({@code $scenario.get('x')}) and JavaScript
 * ({@code scenario.get('x')}) can, so scenario state is exposed as a section lambda whose section
 * body is the key name: {@code {{#scenario.get}}name{{/scenario.get}}}. This mirrors
 * {@link org.mockserver.templates.engine.velocity.VelocityScenarioStateTemplateTest}.
 *
 * @author jamesdbloom
 */
public class MustacheScenarioStateTemplateTest {

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
    public void shouldReadUnsetScenarioStateAsStarted() {
        // given
        String template = "{" + NEW_LINE +
            "    \"statusCode\": 200," + NEW_LINE +
            "    \"body\": \"{{#scenario.get}}flow{{/scenario.get}}\"" + NEW_LINE +
            "}";
        HttpRequest request = request().withPath("/somePath");

        // when
        HttpResponse actualHttpResponse = new MustacheTemplateEngine(mockServerLogger, configuration)
            .executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse.getBodyAsString(), is("Started"));
    }

    @Test
    public void shouldReadScenarioStateSetExternally() {
        // given — a matcher (or another request) transitioned the scenario earlier
        scenarioManager.setState("flow", "step3");
        String template = "{" + NEW_LINE +
            "    \"statusCode\": 200," + NEW_LINE +
            "    \"body\": \"{{#scenario.get}}flow{{/scenario.get}}\"" + NEW_LINE +
            "}";
        HttpRequest request = request().withPath("/somePath");

        // when
        HttpResponse actualHttpResponse = new MustacheTemplateEngine(mockServerLogger, configuration)
            .executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse.getBodyAsString(), is("step3"));
    }

    @Test
    public void shouldReadCapturedValueByNameIntoResponseBody() {
        // given — a capture rule (or earlier request) stored the order id in scenario state
        scenarioManager.setState("orderId", "ORDER-123");
        String template = "{" + NEW_LINE +
            "    \"statusCode\": 200," + NEW_LINE +
            "    \"body\": \"orderId={{#scenario.get}}orderId{{/scenario.get}}\"" + NEW_LINE +
            "}";
        HttpRequest request = request().withPath("/order/step3");

        // when
        HttpResponse actualHttpResponse = new MustacheTemplateEngine(mockServerLogger, configuration)
            .executeTemplate(template, request, HttpResponseDTO.class);

        // then — the captured value is reproduced in the response body
        assertThat(actualHttpResponse.getBodyAsString(), is("orderId=ORDER-123"));
    }
}
