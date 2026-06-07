package org.mockserver.model;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.nullValue;

public class CrossProtocolScenarioTest {

    @Test
    public void shouldBuildWithFluentSetters() {
        // when
        CrossProtocolScenario scenario = CrossProtocolScenario.crossProtocolScenario()
            .withTrigger(CrossProtocolTrigger.HTTP_REQUEST)
            .withScenarioName("TestScenario")
            .withTargetState("TargetState")
            .withMatchPattern("/api/");

        // then
        assertThat(scenario.getTrigger(), is(CrossProtocolTrigger.HTTP_REQUEST));
        assertThat(scenario.getScenarioName(), is("TestScenario"));
        assertThat(scenario.getTargetState(), is("TargetState"));
        assertThat(scenario.getMatchPattern(), is("/api/"));
    }

    @Test
    public void shouldBuildDnsQuery() {
        // when
        CrossProtocolScenario scenario = CrossProtocolScenario.onDnsQuery("api.example.com", "DnsScenario", "DnsObserved");

        // then
        assertThat(scenario.getTrigger(), is(CrossProtocolTrigger.DNS_QUERY));
        assertThat(scenario.getMatchPattern(), is("api.example.com"));
        assertThat(scenario.getScenarioName(), is("DnsScenario"));
        assertThat(scenario.getTargetState(), is("DnsObserved"));
    }

    @Test
    public void shouldBuildWebSocketConnect() {
        // when
        CrossProtocolScenario scenario = CrossProtocolScenario.onWebSocketConnect("WsScenario", "WsConnected");

        // then
        assertThat(scenario.getTrigger(), is(CrossProtocolTrigger.WEBSOCKET_CONNECT));
        assertThat(scenario.getMatchPattern(), nullValue());
        assertThat(scenario.getScenarioName(), is("WsScenario"));
        assertThat(scenario.getTargetState(), is("WsConnected"));
    }

    @Test
    public void shouldBuildGrpcRequest() {
        // when
        CrossProtocolScenario scenario = CrossProtocolScenario.onGrpcRequest("UserService", "GrpcScenario", "GrpcCalled");

        // then
        assertThat(scenario.getTrigger(), is(CrossProtocolTrigger.GRPC_REQUEST));
        assertThat(scenario.getMatchPattern(), is("UserService"));
        assertThat(scenario.getScenarioName(), is("GrpcScenario"));
        assertThat(scenario.getTargetState(), is("GrpcCalled"));
    }

    @Test
    public void shouldBuildHttpPath() {
        // when
        CrossProtocolScenario scenario = CrossProtocolScenario.onHttpPath("/api/users", "HttpScenario", "HttpCalled");

        // then
        assertThat(scenario.getTrigger(), is(CrossProtocolTrigger.HTTP_REQUEST));
        assertThat(scenario.getMatchPattern(), is("/api/users"));
        assertThat(scenario.getScenarioName(), is("HttpScenario"));
        assertThat(scenario.getTargetState(), is("HttpCalled"));
    }

    @Test
    public void shouldSupportEquality() {
        // given
        CrossProtocolScenario scenario1 = CrossProtocolScenario.onDnsQuery("api.example.com", "Scenario", "State");
        CrossProtocolScenario scenario2 = CrossProtocolScenario.onDnsQuery("api.example.com", "Scenario", "State");

        // then
        assertThat(scenario1, is(scenario2));
        assertThat(scenario1.hashCode(), is(scenario2.hashCode()));
    }
}
