package org.mockserver.slo;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.time.TimeService;

import java.time.Instant;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * Behavioural tests for {@link SloEvaluator}: comparator handling, AND-ing of
 * objective results, the {@code minimumSampleCount} guard, INCONCLUSIVE for empty
 * windows, and both window types (EXPLICIT verbatim; LOOKBACK resolved against the
 * controllable {@link TimeService} clock).
 *
 * <p>State-mutating: flips static SLO properties, freezes the global clock, and
 * uses the {@link SloSampleStore} singleton, so it must run in the sequential
 * Surefire phase.
 */
public class SloEvaluatorTest {

    private final SloSampleStore store = SloSampleStore.getInstance();
    private final SloEvaluator evaluator = new SloEvaluator();

    @Before
    public void setUp() {
        store.reset();
        ConfigurationProperties.sloTrackingEnabled(true);
        ConfigurationProperties.sloWindowMaxSamples(50_000);
        ConfigurationProperties.sloWindowRetentionMillis(600_000L);
    }

    @After
    public void tearDown() {
        store.reset();
        TimeService.reset();
        ConfigurationProperties.sloTrackingEnabled(false);
    }

    private SloCriteria explicitCriteria(SloObjective... objectives) {
        return new SloCriteria()
            .withName("test")
            .withWindow(SloWindow.explicit(0L, 10_000L))
            .withObjectives(objectives);
    }

    @Test
    public void shouldPassWhenLatencyBelowThreshold() {
        store.record(1000L, 100L, false, Scope.FORWARD, "a.svc");
        store.record(1000L, 120L, false, Scope.FORWARD, "a.svc");
        SloVerdict verdict = evaluator.evaluate(explicitCriteria(
            new SloObjective().withSli(SloObjective.Sli.LATENCY_P95).withComparator(SloObjective.Comparator.LESS_THAN).withThreshold(200)
        ));
        assertThat(verdict.getResult(), is(SloVerdict.Result.PASS));
        assertThat(verdict.getObjectiveResults().get(0).getObservedValue(), is(120.0));
    }

    @Test
    public void shouldFailWhenLatencyAboveThreshold() {
        store.record(1000L, 300L, false, Scope.FORWARD, "a.svc");
        SloVerdict verdict = evaluator.evaluate(explicitCriteria(
            new SloObjective().withSli(SloObjective.Sli.LATENCY_P99).withComparator(SloObjective.Comparator.LESS_THAN).withThreshold(200)
        ));
        assertThat(verdict.getResult(), is(SloVerdict.Result.FAIL));
        assertThat(verdict.getObjectiveResults().get(0).getResult(), is(SloVerdict.Result.FAIL));
    }

    @Test
    public void shouldEvaluateAllComparators() {
        store.record(1000L, 100L, false, Scope.FORWARD, "a.svc");
        assertThat(evaluate(SloObjective.Comparator.LESS_THAN, 100).getResult(), is(SloVerdict.Result.FAIL));
        assertThat(evaluate(SloObjective.Comparator.LESS_THAN_OR_EQUAL, 100).getResult(), is(SloVerdict.Result.PASS));
        assertThat(evaluate(SloObjective.Comparator.GREATER_THAN, 100).getResult(), is(SloVerdict.Result.FAIL));
        assertThat(evaluate(SloObjective.Comparator.GREATER_THAN_OR_EQUAL, 100).getResult(), is(SloVerdict.Result.PASS));
    }

    private SloVerdict evaluate(SloObjective.Comparator comparator, double threshold) {
        return evaluator.evaluate(explicitCriteria(
            new SloObjective().withSli(SloObjective.Sli.LATENCY_P50).withComparator(comparator).withThreshold(threshold)
        ));
    }

    @Test
    public void shouldFailWhenAnyObjectiveFails() {
        store.record(1000L, 100L, false, Scope.FORWARD, "a.svc");
        store.record(1000L, 100L, true, Scope.FORWARD, "a.svc"); // 50% error rate
        SloVerdict verdict = evaluator.evaluate(explicitCriteria(
            new SloObjective().withSli(SloObjective.Sli.LATENCY_P95).withComparator(SloObjective.Comparator.LESS_THAN).withThreshold(1000),
            new SloObjective().withSli(SloObjective.Sli.ERROR_RATE).withComparator(SloObjective.Comparator.LESS_THAN).withThreshold(0.1)
        ));
        assertThat(verdict.getResult(), is(SloVerdict.Result.FAIL));
    }

    @Test
    public void shouldPassWhenAllObjectivesPass() {
        store.record(1000L, 50L, false, Scope.FORWARD, "a.svc");
        store.record(1000L, 60L, false, Scope.FORWARD, "a.svc");
        SloVerdict verdict = evaluator.evaluate(explicitCriteria(
            new SloObjective().withSli(SloObjective.Sli.LATENCY_P95).withComparator(SloObjective.Comparator.LESS_THAN).withThreshold(1000),
            new SloObjective().withSli(SloObjective.Sli.ERROR_RATE).withComparator(SloObjective.Comparator.LESS_THAN_OR_EQUAL).withThreshold(0.0)
        ));
        assertThat(verdict.getResult(), is(SloVerdict.Result.PASS));
    }

    @Test
    public void shouldComputeErrorRate() {
        store.record(1000L, 10L, false, Scope.FORWARD, "a.svc");
        store.record(1000L, 10L, false, Scope.FORWARD, "a.svc");
        store.record(1000L, 10L, false, Scope.FORWARD, "a.svc");
        store.record(1000L, 10L, true, Scope.FORWARD, "a.svc"); // 1/4 = 0.25
        SloVerdict verdict = evaluator.evaluate(explicitCriteria(
            new SloObjective().withSli(SloObjective.Sli.ERROR_RATE).withComparator(SloObjective.Comparator.LESS_THAN).withThreshold(0.3)
        ));
        assertThat(verdict.getObjectiveResults().get(0).getObservedValue(), is(0.25));
        assertThat(verdict.getResult(), is(SloVerdict.Result.PASS));
    }

    @Test
    public void shouldBeInconclusiveWhenNoSamplesForLatency() {
        SloVerdict verdict = evaluator.evaluate(explicitCriteria(
            new SloObjective().withSli(SloObjective.Sli.LATENCY_P95).withComparator(SloObjective.Comparator.LESS_THAN).withThreshold(100)
        ));
        assertThat(verdict.getResult(), is(SloVerdict.Result.INCONCLUSIVE));
        assertThat(verdict.getObjectiveResults().get(0).getObservedValue(), is(nullValue()));
        assertThat(verdict.getObjectiveResults().get(0).getResult(), is(SloVerdict.Result.INCONCLUSIVE));
    }

    @Test
    public void shouldBeInconclusiveWhenNoRequestsForErrorRate() {
        SloVerdict verdict = evaluator.evaluate(explicitCriteria(
            new SloObjective().withSli(SloObjective.Sli.ERROR_RATE).withComparator(SloObjective.Comparator.LESS_THAN).withThreshold(0.1)
        ));
        assertThat(verdict.getResult(), is(SloVerdict.Result.INCONCLUSIVE));
    }

    @Test
    public void shouldBeInconclusiveWhenBelowMinimumSampleCount() {
        store.record(1000L, 50L, false, Scope.FORWARD, "a.svc");
        store.record(1000L, 50L, false, Scope.FORWARD, "a.svc");
        SloCriteria criteria = explicitCriteria(
            new SloObjective().withSli(SloObjective.Sli.LATENCY_P95).withComparator(SloObjective.Comparator.LESS_THAN).withThreshold(1000)
        ).withMinimumSampleCount(10);
        SloVerdict verdict = evaluator.evaluate(criteria);
        // objective itself passes but window has too few samples to draw a verdict
        assertThat(verdict.getResult(), is(SloVerdict.Result.INCONCLUSIVE));
        assertThat(verdict.getSampleCount(), is(2L));
    }

    @Test
    public void shouldPassWhenAtMinimumSampleCount() {
        store.record(1000L, 50L, false, Scope.FORWARD, "a.svc");
        store.record(1000L, 50L, false, Scope.FORWARD, "a.svc");
        SloCriteria criteria = explicitCriteria(
            new SloObjective().withSli(SloObjective.Sli.LATENCY_P95).withComparator(SloObjective.Comparator.LESS_THAN).withThreshold(1000)
        ).withMinimumSampleCount(2);
        assertThat(evaluator.evaluate(criteria).getResult(), is(SloVerdict.Result.PASS));
    }

    @Test
    public void shouldResolveLookbackWindowAgainstControllableClock() {
        long now = 1_000_000L;
        TimeService.freeze(Instant.ofEpochMilli(now));
        // inside the 5s lookback window
        store.record(now - 1000, 100L, false, Scope.FORWARD, "a.svc");
        // before the lookback window opened
        store.record(now - 60_000, 9999L, false, Scope.FORWARD, "a.svc");

        SloCriteria criteria = new SloCriteria()
            .withName("lookback")
            .withWindow(SloWindow.lookback(5000))
            .withObjectives(
                new SloObjective().withSli(SloObjective.Sli.LATENCY_P95).withComparator(SloObjective.Comparator.LESS_THAN).withThreshold(500)
            );

        SloVerdict verdict = evaluator.evaluate(criteria);
        assertThat(verdict.getWindowFromEpochMillis(), is(now - 5000));
        assertThat(verdict.getWindowToEpochMillis(), is(now));
        // only the in-window 100ms sample counts; the 9999ms one is excluded
        assertThat(verdict.getObjectiveResults().get(0).getObservedValue(), is(100.0));
        assertThat(verdict.getResult(), is(SloVerdict.Result.PASS));
    }

    @Test
    public void shouldUseExplicitWindowVerbatim() {
        store.record(500L, 100L, false, Scope.FORWARD, "a.svc");   // before window
        store.record(1500L, 200L, false, Scope.FORWARD, "a.svc");  // in window
        store.record(2500L, 300L, false, Scope.FORWARD, "a.svc");  // after window
        SloCriteria criteria = new SloCriteria()
            .withName("explicit")
            .withWindow(SloWindow.explicit(1000L, 2000L))
            .withObjectives(
                new SloObjective().withSli(SloObjective.Sli.LATENCY_P95).withComparator(SloObjective.Comparator.LESS_THAN).withThreshold(1000)
            );
        SloVerdict verdict = evaluator.evaluate(criteria);
        assertThat(verdict.getWindowFromEpochMillis(), is(1000L));
        assertThat(verdict.getWindowToEpochMillis(), is(2000L));
        assertThat(verdict.getObjectiveResults().get(0).getObservedValue(), is(200.0));
    }
}
