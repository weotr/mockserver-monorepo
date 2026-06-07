package org.mockserver.metrics;

import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.model.snapshots.CounterSnapshot;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.action.http.ServiceChaosRegistry;
import org.mockserver.model.HttpChaosProfile;
import org.mockserver.model.HttpError;
import org.mockserver.model.HttpResponse;

import java.util.Arrays;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.mock.Expectation.when;
import static org.mockserver.model.HttpChaosProfile.httpChaosProfile;
import static org.mockserver.model.HttpRequest.request;

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
        Metrics.setActiveExpectationsSupplier(null);
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
    public void activeServiceChaosGaugeReflectsLiveRegistryByFaultType() {
        new Metrics(configuration().metricsEnabled(true));
        String metric = "mock_server_active_service_chaos";

        assertThat("no chaos registered", scrapeGaugeValueByLabel(metric, "fault_type", "error"), is(0.0));

        // a.svc injects error + drop; b.svc injects error only
        ServiceChaosRegistry.getInstance().put("a.svc", httpChaosProfile().withErrorStatus(503).withDropConnectionProbability(0.5));
        ServiceChaosRegistry.getInstance().put("b.svc", httpChaosProfile().withErrorStatus(500));
        assertThat("two profiles inject error", scrapeGaugeValueByLabel(metric, "fault_type", "error"), is(2.0));
        assertThat("one profile injects drop", scrapeGaugeValueByLabel(metric, "fault_type", "drop"), is(1.0));
        assertThat("no profile injects latency", scrapeGaugeValueByLabel(metric, "fault_type", "latency"), is(0.0));

        ServiceChaosRegistry.getInstance().remove("a.svc");
        assertThat("error follows removals", scrapeGaugeValueByLabel(metric, "fault_type", "error"), is(1.0));
        assertThat("drop follows removals", scrapeGaugeValueByLabel(metric, "fault_type", "drop"), is(0.0));

        ServiceChaosRegistry.getInstance().reset();
        assertThat("drops to zero when cleared", scrapeGaugeValueByLabel(metric, "fault_type", "error"), is(0.0));
    }

    @Test
    public void getActiveServiceChaosCountByFaultTypeDoesNotThrowWhenDisabled() {
        // safe to call regardless of whether metrics are enabled (reads the registry directly)
        assertThat(Metrics.getActiveServiceChaosCountByFaultType().get("error"), is(0));
    }

    // --- MCP tool call counter tests ---

    @Test
    public void registersMcpToolCallsCounter() {
        new Metrics(configuration().metricsEnabled(true));
        Metrics.incrementMcpToolCall("create_expectation");

        assertThat(scrapeContains("mock_server_mcp_tool_calls"), is(true));
    }

    @Test
    public void mcpToolCallsCounterIncrementsPerToolName() {
        new Metrics(configuration().metricsEnabled(true));
        Metrics.incrementMcpToolCall("create_expectation");
        Metrics.incrementMcpToolCall("create_expectation");
        Metrics.incrementMcpToolCall("verify_request");
        Metrics.incrementMcpToolCall("list_mock_tools");
        Metrics.incrementMcpToolCall("list_mock_tools");
        Metrics.incrementMcpToolCall("list_mock_tools");

        assertThat(scrapeCounterValue("mock_server_mcp_tool_calls", "tool", "create_expectation"), is(2.0));
        assertThat(scrapeCounterValue("mock_server_mcp_tool_calls", "tool", "verify_request"), is(1.0));
        assertThat(scrapeCounterValue("mock_server_mcp_tool_calls", "tool", "list_mock_tools"), is(3.0));
    }

    @Test
    public void getMcpToolCallCountReturnsPerToolCount() {
        new Metrics(configuration().metricsEnabled(true));
        Metrics.incrementMcpToolCall("reset");
        Metrics.incrementMcpToolCall("reset");

        assertThat(Metrics.getMcpToolCallCount("reset"), is(2L));
        assertThat(Metrics.getMcpToolCallCount("nonexistent"), is(0L));
    }

    @Test
    public void incrementMcpToolCallDoesNotThrowWhenDisabled() {
        // safe to call when counter not registered (no-op)
        Metrics.incrementMcpToolCall("create_expectation");
    }

    @Test
    public void incrementMcpToolCallDoesNotThrowWhenToolNameIsNull() {
        new Metrics(configuration().metricsEnabled(true));
        // should not throw
        Metrics.incrementMcpToolCall(null);
    }

    // --- expectations by type gauge tests ---

    @Test
    public void registersExpectationsByTypeGauge() {
        new Metrics(configuration().metricsEnabled(true));

        assertThat(scrapeContains("mock_server_expectations_by_type"), is(true));
    }

    @Test
    public void expectationsByTypeGaugeReflectsActiveExpectations() {
        new Metrics(configuration().metricsEnabled(true));
        String metric = "mock_server_expectations_by_type";

        // no supplier set yet -> empty
        assertThat("no supplier", scrapeGaugeValueByLabel(metric, "action_type", "RESPONSE"), is(0.0));

        // set supplier with a mix of action types
        Metrics.setActiveExpectationsSupplier(() -> Arrays.asList(
            when(request().withPath("/a")).thenRespond(HttpResponse.response().withStatusCode(200)),
            when(request().withPath("/b")).thenRespond(HttpResponse.response().withStatusCode(201)),
            when(request().withPath("/c")).thenError(HttpError.error().withDropConnection(true))
        ));

        assertThat("two RESPONSE expectations",
            scrapeGaugeValueByLabel(metric, "action_type", "RESPONSE"), is(2.0));
        assertThat("one ERROR expectation",
            scrapeGaugeValueByLabel(metric, "action_type", "ERROR"), is(1.0));
        assertThat("no FORWARD expectations",
            scrapeGaugeValueByLabel(metric, "action_type", "FORWARD"), is(0.0));

        // update the supplier (simulates adding/removing expectations)
        Metrics.setActiveExpectationsSupplier(() -> Arrays.asList(
            when(request().withPath("/a")).thenRespond(HttpResponse.response().withStatusCode(200))
        ));

        assertThat("follows updates: one RESPONSE",
            scrapeGaugeValueByLabel(metric, "action_type", "RESPONSE"), is(1.0));
        assertThat("follows updates: zero ERROR",
            scrapeGaugeValueByLabel(metric, "action_type", "ERROR"), is(0.0));
    }

    @Test
    public void expectationsByTypeGaugeHandlesNullAction() {
        new Metrics(configuration().metricsEnabled(true));

        // An expectation with no action set
        Metrics.setActiveExpectationsSupplier(() -> Arrays.asList(
            new Expectation(request().withPath("/no-action"))
        ));

        // should not throw; no action_type labels emitted
        Map<String, Integer> counts = Metrics.getActiveExpectationCountByType();
        assertThat("empty map for null-action expectations", counts.isEmpty(), is(true));
    }

    @Test
    public void getActiveExpectationCountByTypeDoesNotThrowWhenNoSupplier() {
        // safe to call regardless of whether supplier is set
        Map<String, Integer> counts = Metrics.getActiveExpectationCountByType();
        assertThat("empty map when no supplier", counts.isEmpty(), is(true));
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

    private static double scrapeGaugeValueByLabel(String name, String labelName, String labelValue) {
        MetricSnapshots snapshots = PrometheusRegistry.defaultRegistry.scrape();
        for (MetricSnapshot snapshot : snapshots) {
            if (snapshot.getMetadata().getName().equals(name) && snapshot instanceof GaugeSnapshot gaugeSnapshot) {
                for (GaugeSnapshot.GaugeDataPointSnapshot dataPoint : gaugeSnapshot.getDataPoints()) {
                    if (labelValue.equals(dataPoint.getLabels().get(labelName))) {
                        return dataPoint.getValue();
                    }
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
