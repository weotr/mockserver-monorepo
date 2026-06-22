package org.mockserver.metrics;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.ConfigurationProperties;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * Behavioural tests for the opt-in per-expectation Prometheus match counter
 * ({@code mock_server_expectation_matched}, labeled by stable expectation id).
 *
 * <p>The counter is gated behind the {@code perExpectationMetricsEnabled}
 * configuration property and is OFF by default. These tests mutate the static
 * {@link Metrics} / default Prometheus registry state (and, for the
 * default-off cases, the global {@link ConfigurationProperties} state), so this
 * class is registered in the sequential Surefire phase (mockserver-core pom).</p>
 */
public class PerExpectationMetricsTest {

    private boolean originalConfigured;
    private boolean originalValue;

    @Before
    public void setUp() {
        // Capture the global property so the default-off assertions are deterministic
        // regardless of any ambient configuration, and restore it in tearDown.
        originalConfigured = System.getProperty("mockserver.perExpectationMetrics") != null;
        originalValue = ConfigurationProperties.perExpectationMetricsEnabled();
        ConfigurationProperties.perExpectationMetricsEnabled(false);
        Metrics.resetAdditionalMetricsForTesting();
    }

    @After
    public void tearDown() {
        if (originalConfigured) {
            ConfigurationProperties.perExpectationMetricsEnabled(originalValue);
        } else {
            System.clearProperty("mockserver.perExpectationMetrics");
        }
        Metrics.resetAdditionalMetricsForTesting();
    }

    private void enablePerExpectationMetrics() {
        Metrics.resetAdditionalMetricsForTesting();
        new Metrics(configuration().metricsEnabled(true).perExpectationMetricsEnabled(true));
    }

    @Test
    public void propertyOnRegistersTheCounter() {
        enablePerExpectationMetrics();

        assertThat("per-expectation counter should be registered when property is on",
            Metrics.isPerExpectationMetricsActive(), is(true));
    }

    @Test
    public void propertyOnMatchingExpectationIncrementsItsPerIdCounter() {
        enablePerExpectationMetrics();

        Metrics.incrementExpectationMatched("expectation-a");
        Metrics.incrementExpectationMatched("expectation-a");

        assertThat(Metrics.getExpectationMatchedCount("expectation-a"), is(2L));
    }

    @Test
    public void propertyOnDifferentExpectationsGetSeparateSeries() {
        enablePerExpectationMetrics();

        Metrics.incrementExpectationMatched("expectation-a");
        Metrics.incrementExpectationMatched("expectation-b");
        Metrics.incrementExpectationMatched("expectation-b");

        // each id is an independent time series
        assertThat(Metrics.getExpectationMatchedCount("expectation-a"), is(1L));
        assertThat(Metrics.getExpectationMatchedCount("expectation-b"), is(2L));
        // an unseen id reads as 0, not an error
        assertThat(Metrics.getExpectationMatchedCount("expectation-c"), is(0L));
    }

    @Test
    public void propertyOffByDefaultRegistersNoSeries() {
        // given - property explicitly off (the default), metrics otherwise enabled
        new Metrics(configuration().metricsEnabled(true).perExpectationMetricsEnabled(false));

        assertThat("per-expectation counter must not be registered when property is off",
            Metrics.isPerExpectationMetricsActive(), is(false));

        // when - increments are attempted anyway
        Metrics.incrementExpectationMatched("expectation-a");

        // then - no series, no crash, reads back as 0
        assertThat(Metrics.getExpectationMatchedCount("expectation-a"), is(0L));
    }

    @Test
    public void propertyUnsetRegistersNoSeries() {
        // given - property entirely unset (the out-of-the-box default)
        assertThat(ConfigurationProperties.perExpectationMetricsEnabled(), is(false));

        // a Configuration that does not override the flag falls back to the global default (off)
        new Metrics(configuration().metricsEnabled(true));

        assertThat(Metrics.isPerExpectationMetricsActive(), is(false));
    }

    @Test
    public void incrementIsNoOpForNullId() {
        enablePerExpectationMetrics();

        // null id must not throw and must record nothing
        Metrics.incrementExpectationMatched(null);

        assertThat(Metrics.getExpectationMatchedCount(null), is(0L));
    }
}
