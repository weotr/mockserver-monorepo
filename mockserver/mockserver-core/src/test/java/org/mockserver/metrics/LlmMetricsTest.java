package org.mockserver.metrics;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.mock.action.http.LlmCostBudgetMonitor;
import org.mockserver.model.Completion;
import org.mockserver.model.Provider;
import org.mockserver.model.Usage;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Tests that LLM token and cost metrics are correctly incremented by
 * {@link HttpActionHandler#recordLlmUsageMetrics} when the counters are
 * registered, and that the cost-budget monitor records cost from the same path.
 */
public class LlmMetricsTest {

    private boolean originalMetricsEnabled;
    private boolean originalLlmMetricsEnabled;
    private double originalBudget;

    @Before
    public void setUp() {
        originalMetricsEnabled = ConfigurationProperties.metricsEnabled();
        originalLlmMetricsEnabled = ConfigurationProperties.llmMetricsEnabled();
        originalBudget = ConfigurationProperties.llmCostBudgetUsd();
        Metrics.resetAdditionalMetricsForTesting();
        LlmCostBudgetMonitor.getInstance().reset();
    }

    @After
    public void tearDown() {
        ConfigurationProperties.metricsEnabled(originalMetricsEnabled);
        ConfigurationProperties.llmMetricsEnabled(originalLlmMetricsEnabled);
        ConfigurationProperties.llmCostBudgetUsd(originalBudget);
        Metrics.resetAdditionalMetricsForTesting();
        LlmCostBudgetMonitor.getInstance().reset();
    }

    private void enableLlmMetrics() {
        // Reset static state completely before re-registering to avoid residual
        // state from prior tests (the CAS guard, the Prometheus registry, the
        // static counter references — all must be clean).
        Metrics.resetAdditionalMetricsForTesting();
        ConfigurationProperties.metricsEnabled(true);
        ConfigurationProperties.llmMetricsEnabled(true);
        Configuration config = new Configuration()
            .metricsEnabled(true)
            .llmMetricsEnabled(true);
        new Metrics(config);
    }

    @Test
    public void shouldIncrementTokenCounters() {
        enableLlmMetrics();
        assertThat("LLM metrics should be active after enableLlmMetrics()",
            Metrics.isLlmMetricsActive(), is(true));

        // when - record metrics for an Anthropic completion
        Completion completion = new Completion()
            .withUsage(new Usage().withInputTokens(100).withOutputTokens(50));
        // Use incrementLlmTokens directly to isolate from recordLlmUsageMetrics
        Metrics.incrementLlmTokens("ANTHROPIC", "claude-opus-4", 100, 50, null);

        // then - verify the counters via the static getter
        assertThat(Metrics.getLlmInputTokens("ANTHROPIC", "claude-opus-4"), is(100L));
        assertThat(Metrics.getLlmOutputTokens("ANTHROPIC", "claude-opus-4"), is(50L));
    }

    @Test
    public void shouldIncrementCostCounter() {
        enableLlmMetrics();

        // when - record metrics for an Anthropic completion with known pricing
        Completion completion = new Completion()
            .withUsage(new Usage().withInputTokens(1_000_000).withOutputTokens(1_000_000));
        HttpActionHandler.recordLlmUsageMetrics(Provider.ANTHROPIC, "claude-opus-4", completion);

        // then - claude-opus-4 pricing: $15/M in + $75/M out = $90.00
        double cost = Metrics.getLlmCostUsd("ANTHROPIC", "claude-opus-4");
        assertThat(cost, closeTo(90.0, 0.01));
    }

    @Test
    public void shouldAccumulateAcrossMultipleCompletions() {
        enableLlmMetrics();

        Completion c1 = new Completion()
            .withUsage(new Usage().withInputTokens(200).withOutputTokens(100));
        Completion c2 = new Completion()
            .withUsage(new Usage().withInputTokens(300).withOutputTokens(150));

        HttpActionHandler.recordLlmUsageMetrics(Provider.OPENAI, "gpt-4o", c1);
        HttpActionHandler.recordLlmUsageMetrics(Provider.OPENAI, "gpt-4o", c2);

        assertThat(Metrics.getLlmInputTokens("OPENAI", "gpt-4o"), is(500L));
        assertThat(Metrics.getLlmOutputTokens("OPENAI", "gpt-4o"), is(250L));
    }

    @Test
    public void shouldHandleNullUsageGracefully() {
        enableLlmMetrics();

        // when - completion with no usage
        Completion completion = new Completion();
        HttpActionHandler.recordLlmUsageMetrics(Provider.ANTHROPIC, "claude-opus-4", completion);

        // then - no crash, counters at 0
        assertThat(Metrics.getLlmInputTokens("ANTHROPIC", "claude-opus-4"), is(0L));
        assertThat(Metrics.getLlmOutputTokens("ANTHROPIC", "claude-opus-4"), is(0L));
    }

    @Test
    public void shouldHandleNullCompletionGracefully() {
        enableLlmMetrics();

        // when - null completion
        HttpActionHandler.recordLlmUsageMetrics(Provider.ANTHROPIC, "claude-opus-4", null);

        // then - no crash
        assertThat(Metrics.getLlmInputTokens("ANTHROPIC", "claude-opus-4"), is(0L));
    }

    @Test
    public void shouldNotIncrementWhenLlmMetricsDisabled() {
        // given - metricsEnabled but llmMetricsEnabled is false
        ConfigurationProperties.metricsEnabled(true);
        ConfigurationProperties.llmMetricsEnabled(false);
        Configuration config = new Configuration()
            .metricsEnabled(true)
            .llmMetricsEnabled(false);
        new Metrics(config);

        assertThat(Metrics.isLlmMetricsActive(), is(false));

        // when - record metrics
        Completion completion = new Completion()
            .withUsage(new Usage().withInputTokens(100).withOutputTokens(50));
        HttpActionHandler.recordLlmUsageMetrics(Provider.ANTHROPIC, "claude-opus-4", completion);

        // then - counters remain 0 (not registered)
        assertThat(Metrics.getLlmInputTokens("ANTHROPIC", "claude-opus-4"), is(0L));
    }

    @Test
    public void shouldRecordCostOnBudgetMonitor() {
        enableLlmMetrics();
        ConfigurationProperties.llmCostBudgetUsd(0.10);

        // when - record a completion that costs money
        Completion completion = new Completion()
            .withUsage(new Usage().withInputTokens(1_000_000).withOutputTokens(0));
        // claude-opus-4 input: $15/M = $15.00
        HttpActionHandler.recordLlmUsageMetrics(Provider.ANTHROPIC, "claude-opus-4", completion);

        // then - budget monitor has the cost recorded
        assertThat(LlmCostBudgetMonitor.getInstance().getCumulativeCostUsd(), closeTo(15.0, 0.01));
        assertThat(LlmCostBudgetMonitor.getInstance().isBudgetExceeded(), is(true));
    }

    @Test
    public void shouldTrackTotalCostAcrossAllModels() {
        enableLlmMetrics();

        // when - completions from different providers
        Completion c1 = new Completion()
            .withUsage(new Usage().withInputTokens(1_000_000).withOutputTokens(0));
        HttpActionHandler.recordLlmUsageMetrics(Provider.ANTHROPIC, "claude-opus-4", c1);
        // claude-opus-4 input: $15/M = $15.00

        Completion c2 = new Completion()
            .withUsage(new Usage().withInputTokens(1_000_000).withOutputTokens(0));
        HttpActionHandler.recordLlmUsageMetrics(Provider.OPENAI, "gpt-4o", c2);
        // gpt-4o input: $2.5/M = $2.50

        // then - total cost across all providers is aggregated
        double totalCost = Metrics.getLlmCostUsdTotal();
        assertThat(totalCost, closeTo(17.50, 0.01));
    }

    @Test
    public void shouldUseUnknownForNullProviderOrModel() {
        enableLlmMetrics();

        Completion completion = new Completion()
            .withUsage(new Usage().withInputTokens(100).withOutputTokens(50));

        // when - null provider and model
        HttpActionHandler.recordLlmUsageMetrics(null, null, completion);

        // then - recorded under "unknown" labels
        assertThat(Metrics.getLlmInputTokens("unknown", "unknown"), is(100L));
        assertThat(Metrics.getLlmOutputTokens("unknown", "unknown"), is(50L));
    }
}
