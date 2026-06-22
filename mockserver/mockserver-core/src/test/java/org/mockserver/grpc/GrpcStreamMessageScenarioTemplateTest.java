package org.mockserver.grpc;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.CrossProtocolEventBus;
import org.mockserver.mock.ScenarioManager;
import org.mockserver.model.GrpcStreamMessage;
import org.mockserver.model.HttpTemplate;
import org.mockserver.templates.engine.ScenarioStateTemplateTestLock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * WS2.6: scenario-state transition driven by a matched inbound gRPC bidi message.
 * <p>
 * A bidi response template can transition scenario state via the {@code scenario} helper
 * ({@code $scenario.set(...)}) exactly as the HTTP response-template path does, because the
 * renderer reuses the same template engines. Verifies that state written by a bidi template is
 * observable through the live {@link ScenarioManager} (and therefore to subsequent matchers).
 * <p>
 * <b>Global-state test:</b> mutates the process-global {@link CrossProtocolEventBus} singleton,
 * so it is listed in both the sequential Surefire phase and excluded from the parallel phase in
 * {@code mockserver-core/pom.xml}, and serialises against the sibling scenario tests via
 * {@link ScenarioStateTemplateTestLock}.
 */
public class GrpcStreamMessageScenarioTemplateTest {

    private final GrpcStreamMessageTemplateRenderer renderer =
        new GrpcStreamMessageTemplateRenderer(new MockServerLogger(GrpcStreamMessageScenarioTemplateTest.class), configuration());

    private ScenarioManager scenarioManager;
    private ScenarioManager previousScenarioManager;

    @Before
    public void setupTestFixture() {
        ScenarioStateTemplateTestLock.LOCK.lock();
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
    public void shouldTransitionScenarioStateOnInboundMatch() {
        // given — a bidi response template that transitions scenario state on the matched message
        GrpcStreamMessage message = GrpcStreamMessage
            .grpcStreamMessage("{\"greeting\": \"Hi $jsonPath.find(\"$.name\")$scenario.set('chat','GREETED')\"}")
            .withTemplateType(HttpTemplate.TemplateType.VELOCITY);

        // when
        String rendered = renderer.render(message, "{\"name\": \"Alice\"}");

        // then — response echoes the inbound field; set() emits nothing inline
        assertThat(rendered, is("{\"greeting\": \"Hi Alice\"}"));
        // and the scenario transition is observable through the live manager (and to matchers)
        assertThat(scenarioManager.getState("chat"), is("GREETED"));
        assertThat(scenarioManager.matchesState("chat", "GREETED"), is(true));
    }

    @Test
    public void shouldReadScenarioStateWrittenByEarlierMatch() {
        // given — a previous match (or matcher) transitioned the scenario
        scenarioManager.setState("chat", "GREETED");
        GrpcStreamMessage message = GrpcStreamMessage
            .grpcStreamMessage("{\"state\": \"$scenario.get('chat')\"}")
            .withTemplateType(HttpTemplate.TemplateType.VELOCITY);

        // when
        String rendered = renderer.render(message, "{\"name\": \"Bob\"}");

        // then
        assertThat(rendered, is("{\"state\": \"GREETED\"}"));
    }
}
