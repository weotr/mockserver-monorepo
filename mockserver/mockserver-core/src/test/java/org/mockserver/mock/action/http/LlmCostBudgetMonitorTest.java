package org.mockserver.mock.action.http;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.model.HttpResponse;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class LlmCostBudgetMonitorTest {

    private double originalBudget;

    @Before
    public void saveOriginals() {
        originalBudget = ConfigurationProperties.llmCostBudgetUsd();
        LlmCostBudgetMonitor.getInstance().reset();
    }

    @After
    public void restoreOriginals() {
        ConfigurationProperties.llmCostBudgetUsd(originalBudget);
        LlmCostBudgetMonitor.getInstance().reset();
    }

    @Test
    public void shouldNotTripWhenBudgetIsNotConfigured() {
        // given - no budget configured (default negative)
        ConfigurationProperties.llmCostBudgetUsd(-1.0);

        // when - record some cost
        LlmCostBudgetMonitor monitor = LlmCostBudgetMonitor.getInstance();
        monitor.recordCost(100.0);

        // then - budget is not exceeded (disabled)
        assertThat(monitor.isBudgetExceeded(), is(false));
        assertThat(monitor.checkBudgetOrNull(), is(nullValue()));
        assertThat(monitor.getTripCount(), is(0L));
    }

    @Test
    public void shouldNotTripWhenCostIsBelowBudget() {
        // given - budget of $1.00
        ConfigurationProperties.llmCostBudgetUsd(1.0);

        // when - record cost below budget
        LlmCostBudgetMonitor monitor = LlmCostBudgetMonitor.getInstance();
        monitor.recordCost(0.50);

        // then - budget not exceeded
        assertThat(monitor.isBudgetExceeded(), is(false));
        assertThat(monitor.checkBudgetOrNull(), is(nullValue()));
    }

    @Test
    public void shouldTripWhenCostExceedsBudget() {
        // given - budget of $1.00
        ConfigurationProperties.llmCostBudgetUsd(1.0);

        LlmCostBudgetMonitor monitor = LlmCostBudgetMonitor.getInstance();

        // when - record cost that exceeds budget
        monitor.recordCost(0.60);
        monitor.recordCost(0.50); // total $1.10 > $1.00

        // then - budget is exceeded
        assertThat(monitor.isBudgetExceeded(), is(true));
        HttpResponse errorResponse = monitor.checkBudgetOrNull();
        assertThat(errorResponse, is(notNullValue()));
        assertThat(errorResponse.getStatusCode(), is(429));
        assertThat(errorResponse.getBodyAsString(), containsString("cost_budget_exceeded"));
        assertThat(monitor.getTripCount(), is(1L));
    }

    @Test
    public void shouldTripAtExactBudget() {
        // given - budget of exactly $0.50
        ConfigurationProperties.llmCostBudgetUsd(0.50);

        LlmCostBudgetMonitor monitor = LlmCostBudgetMonitor.getInstance();
        monitor.recordCost(0.50); // total == budget

        // then - budget is exceeded (>= threshold)
        assertThat(monitor.isBudgetExceeded(), is(true));
    }

    @Test
    public void shouldResetCumulativeCost() {
        // given - budget of $1.00 and cost exceeds it
        ConfigurationProperties.llmCostBudgetUsd(1.0);
        LlmCostBudgetMonitor monitor = LlmCostBudgetMonitor.getInstance();
        monitor.recordCost(2.0);
        assertThat(monitor.isBudgetExceeded(), is(true));

        // when - reset
        monitor.reset();

        // then - budget is no longer exceeded
        assertThat(monitor.isBudgetExceeded(), is(false));
        assertThat(monitor.getCumulativeCostUsd(), closeTo(0.0, 0.001));
        assertThat(monitor.getTripCount(), is(0L));
    }

    @Test
    public void shouldIgnoreNullAndNegativeCosts() {
        ConfigurationProperties.llmCostBudgetUsd(0.01);
        LlmCostBudgetMonitor monitor = LlmCostBudgetMonitor.getInstance();

        // when - record null and negative costs
        monitor.recordCost(null);
        monitor.recordCost(-5.0);
        monitor.recordCost(0.0);

        // then - cumulative cost is still zero
        assertThat(monitor.getCumulativeCostUsd(), closeTo(0.0, 0.001));
        assertThat(monitor.isBudgetExceeded(), is(false));
    }

    @Test
    public void shouldFailOpenOnMalformedBudget() {
        // given - a malformed budget string (parsed as -1.0 by the property reader)
        // The ConfigurationProperties returns -1.0 for unparseable values
        ConfigurationProperties.llmCostBudgetUsd(-1.0);

        LlmCostBudgetMonitor monitor = LlmCostBudgetMonitor.getInstance();
        monitor.recordCost(1000.0);

        // then - never blocks (fail-open)
        assertThat(monitor.isBudgetExceeded(), is(false));
    }

    @Test
    public void shouldAccumulateCostAcrossMultipleRecords() {
        ConfigurationProperties.llmCostBudgetUsd(1.0);
        LlmCostBudgetMonitor monitor = LlmCostBudgetMonitor.getInstance();

        // when - record small increments
        for (int i = 0; i < 10; i++) {
            monitor.recordCost(0.05); // total $0.50
        }
        assertThat(monitor.isBudgetExceeded(), is(false));

        // when - record more to exceed
        for (int i = 0; i < 11; i++) {
            monitor.recordCost(0.05); // total $1.05
        }

        // then
        assertThat(monitor.isBudgetExceeded(), is(true));
        assertThat(monitor.getCumulativeCostUsd(), closeTo(1.05, 0.001));
    }
}
