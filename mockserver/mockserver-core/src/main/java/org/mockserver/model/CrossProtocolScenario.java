package org.mockserver.model;

/**
 * Describes a cross-protocol scenario correlation: when a protocol event
 * matching {@link #trigger} (and optionally {@link #matchPattern}) is
 * observed, the named scenario is advanced to {@link #targetState}.
 * <p>
 * This allows multi-protocol test flows such as "when a DNS query for
 * api.example.com is seen, advance to the 'DnsObserved' state so HTTP
 * expectations gated on that state become active."
 */
public class CrossProtocolScenario extends ObjectWithReflectiveEqualsHashCodeToString {

    private CrossProtocolTrigger trigger;
    private String scenarioName;
    private String targetState;
    private String matchPattern;

    public static CrossProtocolScenario crossProtocolScenario() {
        return new CrossProtocolScenario();
    }

    // --- getters and fluent setters ---

    public CrossProtocolTrigger getTrigger() {
        return trigger;
    }

    public CrossProtocolScenario withTrigger(CrossProtocolTrigger trigger) {
        this.trigger = trigger;
        return this;
    }

    public String getScenarioName() {
        return scenarioName;
    }

    public CrossProtocolScenario withScenarioName(String scenarioName) {
        this.scenarioName = scenarioName;
        return this;
    }

    public String getTargetState() {
        return targetState;
    }

    public CrossProtocolScenario withTargetState(String targetState) {
        this.targetState = targetState;
        return this;
    }

    public String getMatchPattern() {
        return matchPattern;
    }

    public CrossProtocolScenario withMatchPattern(String matchPattern) {
        this.matchPattern = matchPattern;
        return this;
    }

    // --- convenience builders ---

    public static CrossProtocolScenario onDnsQuery(String queryName, String scenarioName, String targetState) {
        return crossProtocolScenario()
            .withTrigger(CrossProtocolTrigger.DNS_QUERY)
            .withMatchPattern(queryName)
            .withScenarioName(scenarioName)
            .withTargetState(targetState);
    }

    public static CrossProtocolScenario onWebSocketConnect(String scenarioName, String targetState) {
        return crossProtocolScenario()
            .withTrigger(CrossProtocolTrigger.WEBSOCKET_CONNECT)
            .withScenarioName(scenarioName)
            .withTargetState(targetState);
    }

    public static CrossProtocolScenario onGrpcRequest(String serviceName, String scenarioName, String targetState) {
        return crossProtocolScenario()
            .withTrigger(CrossProtocolTrigger.GRPC_REQUEST)
            .withMatchPattern(serviceName)
            .withScenarioName(scenarioName)
            .withTargetState(targetState);
    }

    public static CrossProtocolScenario onHttpPath(String pathPattern, String scenarioName, String targetState) {
        return crossProtocolScenario()
            .withTrigger(CrossProtocolTrigger.HTTP_REQUEST)
            .withMatchPattern(pathPattern)
            .withScenarioName(scenarioName)
            .withTargetState(targetState);
    }
}
