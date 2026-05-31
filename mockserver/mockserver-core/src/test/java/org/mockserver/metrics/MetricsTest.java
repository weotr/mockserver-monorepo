package org.mockserver.metrics;

import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.model.snapshots.CounterSnapshot;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.mock.action.http.ServiceChaosRegistry;
import org.mockserver.model.HttpChaosProfile;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpChaosProfile.httpChaosProfile;

public class MetricsTest {

    @Before
    public void resetStaticState() {
        // Reset the process-static one-shot guard and clear the default
        // registry so each test starts with a clean slate — prevents
        // order-dependent failures caused by the CAS guard in Metrics.
        Metrics.resetAdditionalMetricsForTesting();
    }

    @After
    public void clearServiceChaos() {
        ServiceChaosRegistry.getInstance().reset();
    }

    @Test
    public void registersAndRecordsRequestDurationHistogram() {
        new Metrics(configuration().metricsEnabled(true));
        Metrics.observeRequestDurationSeconds(0.05);

        assertThat(scrapeContains("mock_server_request_duration_seconds"), is(true));
    }

    @Test
    public void observeRequestDurationDoesNotThrow() {
        // safe to call regardless of registration state (no-op when absent)
        Metrics.observeRequestDurationSeconds(0.01);
    }

    @Test
    public void registersSlowRequestCounter() {
        new Metrics(configuration().metricsEnabled(true));
        Metrics.incrementSlowRequestTotal();

        assertThat(scrapeContains("mock_server_slow_requests"), is(true));
    }

    @Test
    public void incrementSlowRequestTotalDoesNotThrowWhenDisabled() {
        // safe to call when counter not registered (no-op)
        Metrics.incrementSlowRequestTotal();
    }

    @Test
    public void registersMethodLabeledHistogramWhenEnabled() {
        new Metrics(configuration().metricsEnabled(true).metricsRequestDurationRouteLabels(true));
        Metrics.observeRequestDurationByMethodSeconds(0.05, "GET");

        assertThat(scrapeContains("mock_server_request_duration_by_method_seconds"), is(true));
    }

    @Test
    public void observeRequestDurationByMethodDoesNotThrowWhenDisabled() {
        // safe to call when labeled histogram not registered (no-op)
        Metrics.observeRequestDurationByMethodSeconds(0.01, "POST");
    }

    @Test
    public void registersHttpChaosInjectedCounter() {
        new Metrics(configuration().metricsEnabled(true));
        Metrics.incrementHttpChaosInjected("error");

        assertThat(scrapeContains("mock_server_http_chaos_injected"), is(true));
    }

    @Test
    public void httpChaosInjectedCounterIncrementsPerFaultType() {
        new Metrics(configuration().metricsEnabled(true));
        Metrics.incrementHttpChaosInjected("error");
        Metrics.incrementHttpChaosInjected("error");
        Metrics.incrementHttpChaosInjected("latency");

        assertThat(scrapeCounterValue("mock_server_http_chaos_injected", "fault_type", "error"), is(2.0));
        assertThat(scrapeCounterValue("mock_server_http_chaos_injected", "fault_type", "latency"), is(1.0));
    }

    @Test
    public void incrementHttpChaosInjectedDoesNotThrowWhenDisabled() {
        // safe to call when counter not registered (no-op)
        Metrics.incrementHttpChaosInjected("error");
    }

    @Test
    public void incrementHttpChaosInjectedDoesNotThrowWhenFaultTypeIsNull() {
        new Metrics(configuration().metricsEnabled(true));
        // should not throw
        Metrics.incrementHttpChaosInjected(null);
    }

    @Test
    public void registersActiveServiceChaosGauge() {
        new Metrics(configuration().metricsEnabled(true));

        assertThat(scrapeContains("mock_server_active_service_chaos"), is(true));
    }

    @Test
    public void activeServiceChaosGaugeReflectsLiveRegistry() {
        new Metrics(configuration().metricsEnabled(true));
        HttpChaosProfile profile = httpChaosProfile().withErrorStatus(503);

        assertThat("no chaos registered", scrapeGaugeValue("mock_server_active_service_chaos"), is(0.0));

        ServiceChaosRegistry.getInstance().put("upstream-a.svc", profile);
        ServiceChaosRegistry.getInstance().put("upstream-b.svc", profile);
        assertThat("scrape reads the live registry", scrapeGaugeValue("mock_server_active_service_chaos"), is(2.0));

        ServiceChaosRegistry.getInstance().remove("upstream-a.svc");
        assertThat("gauge follows removals", scrapeGaugeValue("mock_server_active_service_chaos"), is(1.0));

        ServiceChaosRegistry.getInstance().reset();
        assertThat("gauge drops to zero when cleared", scrapeGaugeValue("mock_server_active_service_chaos"), is(0.0));
    }

    @Test
    public void getActiveServiceChaosCountDoesNotThrowWhenDisabled() {
        // safe to call regardless of whether metrics are enabled (reads the registry directly)
        assertThat(Metrics.getActiveServiceChaosCount(), is(0L));
    }

    private static boolean scrapeContains(String name) {
        MetricSnapshots snapshots = PrometheusRegistry.defaultRegistry.scrape();
        for (MetricSnapshot snapshot : snapshots) {
            if (snapshot.getMetadata().getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static double scrapeGaugeValue(String name) {
        MetricSnapshots snapshots = PrometheusRegistry.defaultRegistry.scrape();
        for (MetricSnapshot snapshot : snapshots) {
            if (snapshot.getMetadata().getName().equals(name) && snapshot instanceof GaugeSnapshot gaugeSnapshot) {
                for (GaugeSnapshot.GaugeDataPointSnapshot dataPoint : gaugeSnapshot.getDataPoints()) {
                    return dataPoint.getValue();
                }
            }
        }
        return 0.0;
    }

    private static double scrapeCounterValue(String name, String labelName, String labelValue) {
        MetricSnapshots snapshots = PrometheusRegistry.defaultRegistry.scrape();
        for (MetricSnapshot snapshot : snapshots) {
            if (snapshot.getMetadata().getName().equals(name) && snapshot instanceof CounterSnapshot counterSnapshot) {
                for (CounterSnapshot.CounterDataPointSnapshot dataPoint : counterSnapshot.getDataPoints()) {
                    if (labelValue.equals(dataPoint.getLabels().get(labelName))) {
                        return dataPoint.getValue();
                    }
                }
            }
        }
        return 0.0;
    }
}
