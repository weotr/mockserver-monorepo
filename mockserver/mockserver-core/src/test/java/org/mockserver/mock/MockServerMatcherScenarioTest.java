package org.mockserver.mock;

import org.junit.Before;
import org.junit.Test;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.Times;
import org.mockserver.model.HttpRequest;
import org.mockserver.scheduler.Scheduler;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.Is.is;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.mock.listeners.MockServerMatcherNotifier.Cause.API;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class MockServerMatcherScenarioTest {

    private RequestMatchers requestMatchers;

    @Before
    public void prepareTestFixture() {
        Scheduler scheduler = mock(Scheduler.class);
        WebSocketClientRegistry webSocketClientRegistry = mock(WebSocketClientRegistry.class);
        requestMatchers = new RequestMatchers(configuration(), new MockServerLogger(), scheduler, webSocketClientRegistry);
    }

    @Test
    public void shouldMatchExpectationWithoutScenario() {
        // when
        Expectation expectation = new Expectation(request().withPath("somePath"))
            .thenRespond(response().withBody("someBody"));
        requestMatchers.add(expectation, API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withPath("somePath")), is(expectation));
    }

    @Test
    public void shouldMatchExpectationInStartedState() {
        // when
        Expectation expectation = new Expectation(request().withPath("somePath"))
            .withScenarioName("myScenario")
            .withScenarioState("Started")
            .thenRespond(response().withBody("someBody"));
        requestMatchers.add(expectation, API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withPath("somePath")), is(expectation));
    }

    @Test
    public void shouldNotMatchExpectationInWrongState() {
        // when
        Expectation expectation = new Expectation(request().withPath("somePath"))
            .withScenarioName("myScenario")
            .withScenarioState("Step1")
            .thenRespond(response().withBody("someBody"));
        requestMatchers.add(expectation, API);

        // then - scenario is in "Started" state, not "Step1"
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withPath("somePath")), nullValue());
    }

    @Test
    public void shouldTransitionScenarioStateOnMatch() {
        // given
        Expectation step1 = new Expectation(request().withPath("somePath"))
            .withScenarioName("myScenario")
            .withScenarioState("Started")
            .withNewScenarioState("Step1")
            .thenRespond(response().withBody("response1"));
        requestMatchers.add(step1, API);

        Expectation step2 = new Expectation(request().withPath("somePath"))
            .withScenarioName("myScenario")
            .withScenarioState("Step1")
            .thenRespond(response().withBody("response2"));
        requestMatchers.add(step2, API);

        // when - first request matches step1 and transitions to Step1
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withPath("somePath")), is(step1));

        // then - second request matches step2 because scenario is now in "Step1"
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withPath("somePath")), is(step2));
    }

    @Test
    public void shouldSupportMultiStepScenario() {
        // given
        Expectation step1 = new Expectation(request().withPath("somePath"))
            .withScenarioName("checkout")
            .withScenarioState("Started")
            .withNewScenarioState("ItemAdded")
            .thenRespond(response().withBody("item added"));
        requestMatchers.add(step1, API);

        Expectation step2 = new Expectation(request().withPath("somePath"))
            .withScenarioName("checkout")
            .withScenarioState("ItemAdded")
            .withNewScenarioState("PaymentProcessed")
            .thenRespond(response().withBody("payment processed"));
        requestMatchers.add(step2, API);

        Expectation step3 = new Expectation(request().withPath("somePath"))
            .withScenarioName("checkout")
            .withScenarioState("PaymentProcessed")
            .thenRespond(response().withBody("order complete"));
        requestMatchers.add(step3, API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withPath("somePath")), is(step1));
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withPath("somePath")), is(step2));
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withPath("somePath")), is(step3));
    }

    @Test
    public void shouldSupportIndependentScenarios() {
        // given
        Expectation scenarioAStep1 = new Expectation(request().withPath("pathA"))
            .withScenarioName("scenarioA")
            .withScenarioState("Started")
            .withNewScenarioState("A_Step1")
            .thenRespond(response().withBody("A response 1"));
        requestMatchers.add(scenarioAStep1, API);

        Expectation scenarioAStep2 = new Expectation(request().withPath("pathA"))
            .withScenarioName("scenarioA")
            .withScenarioState("A_Step1")
            .thenRespond(response().withBody("A response 2"));
        requestMatchers.add(scenarioAStep2, API);

        Expectation scenarioBStep1 = new Expectation(request().withPath("pathB"))
            .withScenarioName("scenarioB")
            .withScenarioState("Started")
            .withNewScenarioState("B_Step1")
            .thenRespond(response().withBody("B response 1"));
        requestMatchers.add(scenarioBStep1, API);

        Expectation scenarioBStep2 = new Expectation(request().withPath("pathB"))
            .withScenarioName("scenarioB")
            .withScenarioState("B_Step1")
            .thenRespond(response().withBody("B response 2"));
        requestMatchers.add(scenarioBStep2, API);

        // then - interleave requests to different scenarios
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withPath("pathA")), is(scenarioAStep1));
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withPath("pathB")), is(scenarioBStep1));
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withPath("pathA")), is(scenarioAStep2));
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withPath("pathB")), is(scenarioBStep2));
    }

    @Test
    public void shouldResetScenarioState() {
        // given
        Expectation step1 = new Expectation(request().withPath("somePath"))
            .withScenarioName("myScenario")
            .withScenarioState("Started")
            .withNewScenarioState("Step1")
            .thenRespond(response().withBody("response1"));
        requestMatchers.add(step1, API);

        Expectation step2 = new Expectation(request().withPath("somePath"))
            .withScenarioName("myScenario")
            .withScenarioState("Step1")
            .thenRespond(response().withBody("response2"));
        requestMatchers.add(step2, API);

        // transition to Step1
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withPath("somePath")), is(step1));

        // when - reset
        requestMatchers.reset(API);

        // then - re-add expectations and verify scenario is back to Started
        requestMatchers.add(step1, API);
        requestMatchers.add(step2, API);
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withPath("somePath")), is(step1));
    }

    @Test
    public void shouldMatchWithScenarioNameButNoRequiredState() {
        // when - expectation has scenarioName but no scenarioState (no state check needed)
        Expectation expectation = new Expectation(request().withPath("somePath"))
            .withScenarioName("myScenario")
            .withNewScenarioState("Step1")
            .thenRespond(response().withBody("someBody"));
        requestMatchers.add(expectation, API);

        // then - should match regardless of scenario state
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withPath("somePath")), is(expectation));
    }

    @Test
    public void shouldTransitionStateEvenWithoutRequiredState() {
        // given - step1 transitions without requiring a state, but uses Times.exactly(1) to consume itself
        Expectation step1 = new Expectation(request().withPath("somePath"), Times.exactly(1), org.mockserver.matchers.TimeToLive.unlimited(), 0)
            .withScenarioName("myScenario")
            .withNewScenarioState("Step1")
            .thenRespond(response().withBody("response1"));
        requestMatchers.add(step1, API);

        Expectation step2 = new Expectation(request().withPath("somePath"))
            .withScenarioName("myScenario")
            .withScenarioState("Step1")
            .thenRespond(response().withBody("response2"));
        requestMatchers.add(step2, API);

        // when - first request matches step1 (no state required) and transitions to Step1
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withPath("somePath")), is(step1));

        // then - second request matches step2 because scenario is now in "Step1" and step1 is consumed
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withPath("somePath")), is(step2));
    }

    @Test
    public void shouldWorkWithTimesAndScenario() {
        // given
        Expectation step1 = new Expectation(request().withPath("somePath"), Times.exactly(1), org.mockserver.matchers.TimeToLive.unlimited(), 0)
            .withScenarioName("myScenario")
            .withScenarioState("Started")
            .withNewScenarioState("Step1")
            .thenRespond(response().withBody("response1"));
        requestMatchers.add(step1, API);

        Expectation step2 = new Expectation(request().withPath("somePath"), Times.unlimited(), org.mockserver.matchers.TimeToLive.unlimited(), 0)
            .withScenarioName("myScenario")
            .withScenarioState("Step1")
            .thenRespond(response().withBody("response2"));
        requestMatchers.add(step2, API);

        // when
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withPath("somePath")), is(step1));

        // then - step1 is consumed (times=1), step2 matches
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withPath("somePath")), is(step2));
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withPath("somePath")), is(step2));
    }

    @Test
    public void shouldExposeScenarioManager() {
        assertThat(requestMatchers.getScenarioManager().getState("anything"), is(ScenarioManager.STARTED));
    }

    @Test
    public void shouldNotAdvanceScenarioStateWhenSkippedByPercentage() {
        // given - a scenario step that would transition Started -> Step1, but whose
        // percentage gate is 0 so it is NEVER served. The scenario must NOT advance:
        // a skipped expectation must not produce a side-effecting state transition
        // (consume-then-skip regression guard).
        Expectation step1 = new Expectation(request().withPath("somePath"))
            .withScenarioName("myScenario")
            .withScenarioState("Started")
            .withNewScenarioState("Step1")
            .withPercentage(0)
            .thenRespond(response().withBody("response1"));
        requestMatchers.add(step1, API);

        // when - the request is evaluated against step1 but skipped by the 0% gate
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withPath("somePath")), is(nullValue()));

        // then - the scenario is STILL in "Started" (it did not advance to "Step1")
        assertThat(requestMatchers.getScenarioManager().getState("myScenario"), is(ScenarioManager.STARTED));
    }

    @Test
    public void shouldAdvanceScenarioExactlyOncePerServedExpectationWithTimes() {
        // Progression sanity test (NOT a consume-then-skip distinguishing guard —
        // an exhausted Times matcher goes inactive and never reaches the scenario
        // gate, so the percentage test above is the guard for the reachable
        // skip-after-gate path). This verifies the moved commit-point transition
        // still advances correctly: a Times.exactly(1) step serves once and
        // transitions Started -> Step1, and after it is exhausted the next request
        // is served by the Step1 step — the scenario advanced exactly once per
        // served expectation.
        Expectation step1 = new Expectation(request().withPath("somePath"), Times.exactly(1), org.mockserver.matchers.TimeToLive.unlimited(), 0)
            .withScenarioName("myScenario")
            .withScenarioState("Started")
            .withNewScenarioState("Step1")
            .thenRespond(response().withBody("response1"));
        requestMatchers.add(step1, API);

        Expectation step2 = new Expectation(request().withPath("somePath"))
            .withScenarioName("myScenario")
            .withScenarioState("Step1")
            .withNewScenarioState("Step2")
            .thenRespond(response().withBody("response2"));
        requestMatchers.add(step2, API);

        // first request serves step1, consumes its single Times, advances to Step1
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withPath("somePath")), is(step1));
        assertThat(requestMatchers.getScenarioManager().getState("myScenario"), is("Step1"));

        // second request: step1 is now exhausted (inactive), step2 matches "Step1"
        // and advances to "Step2"
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withPath("somePath")), is(step2));
        assertThat(requestMatchers.getScenarioManager().getState("myScenario"), is("Step2"));
    }
}
