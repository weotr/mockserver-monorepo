package org.mockserver.mock.action.http;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.metrics.Metrics;
import org.mockserver.model.HttpChaosProfile;

import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpChaosProfile.httpChaosProfile;

public class ChaosAutoHaltMonitorTest {

    private boolean originalEnabled;
    private long originalThreshold;
    private long originalWindow;

    @Before
    public void saveOriginals() {
        originalEnabled = ConfigurationProperties.chaosAutoHaltEnabled();
        originalThreshold = ConfigurationProperties.chaosAutoHaltErrorThreshold();
        originalWindow = ConfigurationProperties.chaosAutoHaltWindowMillis();
        // Reset shared state
        Metrics.resetAdditionalMetricsForTesting();
        ChaosAutoHaltMonitor.getInstance().reset();
        ServiceChaosRegistry.getInstance().reset();
    }

    @After
    public void restoreOriginals() {
        ConfigurationProperties.chaosAutoHaltEnabled(originalEnabled);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(originalThreshold);
        ConfigurationProperties.chaosAutoHaltWindowMillis(originalWindow);
        Metrics.resetAdditionalMetricsForTesting();
        ChaosAutoHaltMonitor.getInstance().reset();
        ServiceChaosRegistry.getInstance().reset();
    }

    @Test
    public void shouldHaltChaosWhenThresholdExceeded() {
        // given - a local monitor with a controllable clock
        AtomicLong now = new AtomicLong(1000L);
        ChaosAutoHaltMonitor monitor = new ChaosAutoHaltMonitor(now::get);

        ConfigurationProperties.chaosAutoHaltEnabled(true);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(3);
        ConfigurationProperties.chaosAutoHaltWindowMillis(10_000L);

        // and - active chaos in the registry
        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));
        assertThat(ServiceChaosRegistry.getInstance().entries().isEmpty(), is(false));

        // when - record errors below threshold
        monitor.recordError();
        monitor.recordError();
        assertThat("chaos still active below threshold",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(false));
        assertThat(monitor.getHaltCount(), is(0L));

        // when - record error that crosses the threshold
        monitor.recordError();

        // then - chaos is halted
        assertThat("chaos halted after threshold exceeded",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(true));
        assertThat(monitor.getHaltCount(), is(1L));
    }

    @Test
    public void shouldNotHaltWhenFeatureDisabled() {
        AtomicLong now = new AtomicLong(1000L);
        ChaosAutoHaltMonitor monitor = new ChaosAutoHaltMonitor(now::get);

        ConfigurationProperties.chaosAutoHaltEnabled(false);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(1);
        ConfigurationProperties.chaosAutoHaltWindowMillis(10_000L);

        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));

        // when - record many errors (feature is disabled)
        for (int i = 0; i < 100; i++) {
            monitor.recordError();
        }

        // then - chaos is NOT halted
        assertThat("chaos not halted when feature disabled",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(false));
        assertThat(monitor.getHaltCount(), is(0L));
    }

    @Test
    public void shouldEvictOldErrorsOutsideWindow() {
        AtomicLong now = new AtomicLong(1000L);
        ChaosAutoHaltMonitor monitor = new ChaosAutoHaltMonitor(now::get);

        ConfigurationProperties.chaosAutoHaltEnabled(true);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(5);
        ConfigurationProperties.chaosAutoHaltWindowMillis(10_000L);

        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));

        // record 4 errors at time=1000
        for (int i = 0; i < 4; i++) {
            monitor.recordError();
        }

        // advance clock past the window so old errors expire
        now.set(12_000L);

        // record 1 more error — only 1 error in window, well below threshold of 5
        monitor.recordError();

        assertThat("old errors evicted, below threshold",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(false));
        assertThat(monitor.currentWindowSize(), is(1));
        assertThat(monitor.getHaltCount(), is(0L));
    }

    @Test
    public void shouldNotHaltWhenNoChaosIsActive() {
        AtomicLong now = new AtomicLong(1000L);
        ChaosAutoHaltMonitor monitor = new ChaosAutoHaltMonitor(now::get);

        ConfigurationProperties.chaosAutoHaltEnabled(true);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(2);
        ConfigurationProperties.chaosAutoHaltWindowMillis(10_000L);

        // no chaos registered
        assertThat(ServiceChaosRegistry.getInstance().entries().isEmpty(), is(true));

        // when - record errors exceeding threshold with no chaos active
        monitor.recordError();
        monitor.recordError();

        // then - halt count stays at 0 (nothing to halt)
        assertThat(monitor.getHaltCount(), is(0L));
    }

    @Test
    public void shouldClearWindowAfterHalt() {
        AtomicLong now = new AtomicLong(1000L);
        ChaosAutoHaltMonitor monitor = new ChaosAutoHaltMonitor(now::get);

        ConfigurationProperties.chaosAutoHaltEnabled(true);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(2);
        ConfigurationProperties.chaosAutoHaltWindowMillis(10_000L);

        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));

        // trigger halt
        monitor.recordError();
        monitor.recordError();
        assertThat(monitor.getHaltCount(), is(1L));

        // re-register chaos
        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));

        // one more error should NOT immediately re-trigger (window was cleared)
        monitor.recordError();
        assertThat("single error after halt does not re-trigger",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(false));
        assertThat(monitor.getHaltCount(), is(1L));
    }

    @Test
    public void resetClearsMonitorState() {
        AtomicLong now = new AtomicLong(1000L);
        ChaosAutoHaltMonitor monitor = new ChaosAutoHaltMonitor(now::get);

        ConfigurationProperties.chaosAutoHaltEnabled(true);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(2);
        ConfigurationProperties.chaosAutoHaltWindowMillis(10_000L);

        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));
        monitor.recordError();
        monitor.recordError();
        assertThat(monitor.getHaltCount(), is(1L));

        monitor.reset();
        assertThat(monitor.getHaltCount(), is(0L));
        assertThat(monitor.currentWindowSize(), is(0));
    }

    @Test
    public void singletonInstanceRecordErrorIsNoOpWhenDisabled() {
        // Verify the default instance does nothing when feature is off
        ConfigurationProperties.chaosAutoHaltEnabled(false);
        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));

        ChaosAutoHaltMonitor.getInstance().recordError();

        assertThat("singleton no-op when disabled",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(false));
    }
}
