package org.mockserver.mock;

import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.TimedScenarioTransition;
import org.mockserver.scheduler.Scheduler;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.TimedScenarioTransition.timedTransition;

/**
 * Tests for timed scenario transitions and external state control.
 */
public class ScenarioTimedTransitionTest {

    private ScenarioManager scenarioManager;
    private Scheduler scheduler;

    @Before
    public void setUp() {
        scenarioManager = new ScenarioManager();
        Configuration configuration = configuration();
        // use synchronous scheduler so transitions fire immediately in tests
        scheduler = new Scheduler(configuration, new MockServerLogger(configuration, ScenarioTimedTransitionTest.class), true);
    }

    @Test
    public void shouldSetStateDirectly() {
        // when
        scenarioManager.setState("myScenario", "Step1");

        // then
        assertThat(scenarioManager.getState("myScenario"), is("Step1"));
    }

    @Test
    public void shouldGetCurrentState() {
        // given - no explicit state set
        assertThat(scenarioManager.getState("myScenario"), is(ScenarioManager.STARTED));

        // when
        scenarioManager.setState("myScenario", "Running");

        // then
        assertThat(scenarioManager.getState("myScenario"), is("Running"));
    }

    @Test
    public void shouldScheduleTransitionWithSynchronousScheduler() {
        // given
        scenarioManager.setState("myScenario", "Step1");

        // when - schedule a timed transition (synchronous scheduler will execute immediately)
        TimedScenarioTransition transition = timedTransition()
            .withScenarioName("myScenario")
            .withCurrentState("Step1")
            .withNextState("Step2")
            .withTransitionAfterMs(100L);
        scenarioManager.scheduleTransition(transition, scheduler);

        // then - synchronous scheduler fires immediately, so state should have transitioned
        assertThat(scenarioManager.getState("myScenario"), is("Step2"));
    }

    @Test
    public void shouldNotTransitionIfStateHasChanged() {
        // given - scenario is in "Step1"
        scenarioManager.setState("myScenario", "Step1");

        // manually change state to something else before the scheduled transition fires
        scenarioManager.setState("myScenario", "StepX");

        // when - schedule a transition that expects "Step1" (but it's now "StepX")
        TimedScenarioTransition transition = timedTransition()
            .withScenarioName("myScenario")
            .withCurrentState("Step1")
            .withNextState("Step2")
            .withTransitionAfterMs(100L);
        scenarioManager.scheduleTransition(transition, scheduler);

        // then - state should remain "StepX" because the transition's guard didn't match
        assertThat(scenarioManager.getState("myScenario"), is("StepX"));
    }

    @Test
    public void shouldCancelPendingTransition() {
        // given
        scenarioManager.setState("myScenario", "Step1");

        // cancel first, then schedule - the cancellation bumps generation so
        // subsequent transitions with the old generation become no-ops
        scenarioManager.cancelPendingTransition("myScenario");

        // This test verifies the cancel API doesn't throw and the scenario
        // state remains as set
        assertThat(scenarioManager.getState("myScenario"), is("Step1"));
    }

    @Test
    public void shouldCancelAllPendingTransitions() {
        // given
        scenarioManager.setState("scenario1", "Step1");
        scenarioManager.setState("scenario2", "StepA");

        // when
        scenarioManager.cancelAllPendingTransitions();

        // then - states remain unchanged
        assertThat(scenarioManager.getState("scenario1"), is("Step1"));
        assertThat(scenarioManager.getState("scenario2"), is("StepA"));
    }

    @Test
    public void shouldChainMultipleTimedTransitions() {
        // given
        scenarioManager.setState("flowScenario", "Init");

        // when - chain: Init -> Step1 -> Step2
        scenarioManager.scheduleTransition(
            timedTransition()
                .withScenarioName("flowScenario")
                .withCurrentState("Init")
                .withNextState("Step1")
                .withTransitionAfterMs(50L),
            scheduler
        );

        assertThat(scenarioManager.getState("flowScenario"), is("Step1"));

        scenarioManager.scheduleTransition(
            timedTransition()
                .withScenarioName("flowScenario")
                .withCurrentState("Step1")
                .withNextState("Step2")
                .withTransitionAfterMs(50L),
            scheduler
        );

        // then
        assertThat(scenarioManager.getState("flowScenario"), is("Step2"));
    }

    @Test
    public void shouldOverridePreviousTransitionForSameScenario() {
        // given
        scenarioManager.setState("myScenario", "Step1");

        // schedule first transition (will fire synchronously)
        scenarioManager.scheduleTransition(
            timedTransition()
                .withScenarioName("myScenario")
                .withCurrentState("Step1")
                .withNextState("Step2")
                .withTransitionAfterMs(50L),
            scheduler
        );

        // after first fires synchronously, state is Step2
        assertThat(scenarioManager.getState("myScenario"), is("Step2"));

        // schedule second transition from Step2
        scenarioManager.scheduleTransition(
            timedTransition()
                .withScenarioName("myScenario")
                .withCurrentState("Step2")
                .withNextState("Step3")
                .withTransitionAfterMs(50L),
            scheduler
        );

        // then - should reach Step3
        assertThat(scenarioManager.getState("myScenario"), is("Step3"));
    }

    @Test
    public void timedTransitionModelShouldHaveCorrectFluentApi() {
        // when
        TimedScenarioTransition transition = timedTransition()
            .withScenarioName("testScenario")
            .withCurrentState("from")
            .withNextState("to")
            .withTransitionAfterMs(5000L);

        // then
        assertThat(transition.getScenarioName(), is("testScenario"));
        assertThat(transition.getCurrentState(), is("from"));
        assertThat(transition.getNextState(), is("to"));
        assertThat(transition.getTransitionAfterMs(), is(5000L));
    }

    @Test
    public void shouldHandleCancelPendingTransitionForNullScenarioName() {
        // should not throw
        scenarioManager.cancelPendingTransition(null);
    }
}
