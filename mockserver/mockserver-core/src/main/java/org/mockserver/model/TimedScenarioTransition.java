package org.mockserver.model;

/**
 * Represents a timed auto-transition for a scenario state machine.
 * After {@code transitionAfterMs} milliseconds, the scenario advances
 * from {@code currentState} to {@code nextState}.
 */
public class TimedScenarioTransition extends ObjectWithReflectiveEqualsHashCodeToString {

    private String scenarioName;
    private String currentState;
    private String nextState;
    private Long transitionAfterMs;

    public static TimedScenarioTransition timedTransition() {
        return new TimedScenarioTransition();
    }

    public String getScenarioName() {
        return scenarioName;
    }

    public TimedScenarioTransition withScenarioName(String scenarioName) {
        this.scenarioName = scenarioName;
        return this;
    }

    public String getCurrentState() {
        return currentState;
    }

    public TimedScenarioTransition withCurrentState(String currentState) {
        this.currentState = currentState;
        return this;
    }

    public String getNextState() {
        return nextState;
    }

    public TimedScenarioTransition withNextState(String nextState) {
        this.nextState = nextState;
        return this;
    }

    public Long getTransitionAfterMs() {
        return transitionAfterMs;
    }

    public TimedScenarioTransition withTransitionAfterMs(Long transitionAfterMs) {
        this.transitionAfterMs = transitionAfterMs;
        return this;
    }
}
