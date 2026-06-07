package org.mockserver.mock;

import org.junit.Before;
import org.junit.Test;
import org.mockserver.model.CrossProtocolScenario;
import org.mockserver.model.CrossProtocolTrigger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.nullValue;

public class CrossProtocolEventBusTest {

    private CrossProtocolEventBus bus;
    private ScenarioManager scenarioManager;

    @Before
    public void setUp() {
        bus = new CrossProtocolEventBus();
        scenarioManager = new ScenarioManager();
        bus.setScenarioManager(scenarioManager);
    }

    @Test
    public void shouldFireEventToMatchingRegisteredScenario() {
        // given
        CrossProtocolScenario scenario = CrossProtocolScenario.onWebSocketConnect("MyScenario", "WsConnected");
        bus.register(scenario);

        // when
        bus.fire(CrossProtocolTrigger.WEBSOCKET_CONNECT, "/ws");

        // then
        assertThat(scenarioManager.getState("MyScenario"), is("WsConnected"));
    }

    @Test
    public void shouldNotFireWhenTriggerTypeDoesNotMatch() {
        // given
        CrossProtocolScenario scenario = CrossProtocolScenario.onWebSocketConnect("MyScenario", "WsConnected");
        bus.register(scenario);

        // when - fire a different trigger type
        bus.fire(CrossProtocolTrigger.HTTP_REQUEST, "/test");

        // then - state should remain at default
        assertThat(scenarioManager.getState("MyScenario"), is(ScenarioManager.STARTED));
    }

    @Test
    public void shouldMatchPatternWhenIdentifierContainsPattern() {
        // given
        CrossProtocolScenario scenario = CrossProtocolScenario.onHttpPath("/api/", "ApiScenario", "ApiCalled");
        bus.register(scenario);

        // when
        bus.fire(CrossProtocolTrigger.HTTP_REQUEST, "/api/users");

        // then
        assertThat(scenarioManager.getState("ApiScenario"), is("ApiCalled"));
    }

    @Test
    public void shouldNotMatchPatternWhenIdentifierDoesNotContainPattern() {
        // given
        CrossProtocolScenario scenario = CrossProtocolScenario.onHttpPath("/api/", "ApiScenario", "ApiCalled");
        bus.register(scenario);

        // when
        bus.fire(CrossProtocolTrigger.HTTP_REQUEST, "/other/path");

        // then
        assertThat(scenarioManager.getState("ApiScenario"), is(ScenarioManager.STARTED));
    }

    @Test
    public void shouldMatchWhenNoPatternIsSet() {
        // given - scenario with no match pattern
        CrossProtocolScenario scenario = CrossProtocolScenario.crossProtocolScenario()
            .withTrigger(CrossProtocolTrigger.HTTP_REQUEST)
            .withScenarioName("AllRequests")
            .withTargetState("Observed");
        bus.register(scenario);

        // when
        bus.fire(CrossProtocolTrigger.HTTP_REQUEST, "/anything");

        // then
        assertThat(scenarioManager.getState("AllRequests"), is("Observed"));
    }

    @Test
    public void shouldNotMatchWhenIdentifierIsNullButPatternIsSet() {
        // given
        CrossProtocolScenario scenario = CrossProtocolScenario.onHttpPath("/api/", "ApiScenario", "ApiCalled");
        bus.register(scenario);

        // when
        bus.fire(CrossProtocolTrigger.HTTP_REQUEST, null);

        // then
        assertThat(scenarioManager.getState("ApiScenario"), is(ScenarioManager.STARTED));
    }

    @Test
    public void shouldResetClearAllListeners() {
        // given
        CrossProtocolScenario scenario = CrossProtocolScenario.onWebSocketConnect("MyScenario", "WsConnected");
        bus.register(scenario);

        // when
        bus.reset();
        bus.fire(CrossProtocolTrigger.WEBSOCKET_CONNECT, "/ws");

        // then - state unchanged because listener was cleared
        assertThat(scenarioManager.getState("MyScenario"), is(ScenarioManager.STARTED));
    }

    @Test
    public void shouldUnregisterScenario() {
        // given
        CrossProtocolScenario scenario = CrossProtocolScenario.onWebSocketConnect("MyScenario", "WsConnected");
        bus.register(scenario);

        // when
        bus.unregister(scenario);
        bus.fire(CrossProtocolTrigger.WEBSOCKET_CONNECT, "/ws");

        // then
        assertThat(scenarioManager.getState("MyScenario"), is(ScenarioManager.STARTED));
    }

    @Test
    public void shouldFireMultipleListenersForSameTrigger() {
        // given
        CrossProtocolScenario scenario1 = CrossProtocolScenario.onHttpPath("/api/", "Scenario1", "State1");
        CrossProtocolScenario scenario2 = CrossProtocolScenario.onHttpPath("/api/", "Scenario2", "State2");
        bus.register(scenario1);
        bus.register(scenario2);

        // when
        bus.fire(CrossProtocolTrigger.HTTP_REQUEST, "/api/test");

        // then
        assertThat(scenarioManager.getState("Scenario1"), is("State1"));
        assertThat(scenarioManager.getState("Scenario2"), is("State2"));
    }

    @Test
    public void shouldHandleDnsQueryTrigger() {
        // given
        CrossProtocolScenario scenario = CrossProtocolScenario.onDnsQuery("api.example.com", "DnsScenario", "DnsObserved");
        bus.register(scenario);

        // when
        bus.fire(CrossProtocolTrigger.DNS_QUERY, "api.example.com");

        // then
        assertThat(scenarioManager.getState("DnsScenario"), is("DnsObserved"));
    }

    @Test
    public void shouldHandleGrpcRequestTrigger() {
        // given
        CrossProtocolScenario scenario = CrossProtocolScenario.onGrpcRequest("UserService", "GrpcScenario", "GrpcCalled");
        bus.register(scenario);

        // when
        bus.fire(CrossProtocolTrigger.GRPC_REQUEST, "/com.example.UserService/GetUser");

        // then
        assertThat(scenarioManager.getState("GrpcScenario"), is("GrpcCalled"));
    }

    @Test
    public void shouldNotFireWhenScenarioManagerNotSet() {
        // given
        CrossProtocolEventBus busWithoutManager = new CrossProtocolEventBus();
        CrossProtocolScenario scenario = CrossProtocolScenario.onWebSocketConnect("MyScenario", "WsConnected");
        busWithoutManager.register(scenario);

        // when - should not throw
        busWithoutManager.fire(CrossProtocolTrigger.WEBSOCKET_CONNECT, "/ws");

        // then - no exception thrown
    }

    @Test
    public void shouldIgnoreNullScenarioOnRegister() {
        // when - should not throw
        bus.register(null);

        // then - fire should work without error
        bus.fire(CrossProtocolTrigger.HTTP_REQUEST, "/test");
    }

    @Test
    public void shouldIgnoreNullScenarioOnUnregister() {
        // when - should not throw
        bus.unregister(null);
    }
}
