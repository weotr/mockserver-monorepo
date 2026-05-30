package org.mockserver.metrics;

import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import org.junit.Test;
import org.mockserver.configuration.Configuration;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;

public class OtelMetricsExporterTest {

    @Test
    public void exportsExplicitMockServerMetricsAsObservableGauges() {
        // given — a known value in an explicitly-defined MockServer metric
        Metrics.clear(Metrics.Name.REQUESTS_RECEIVED_COUNT);
        Metrics enabled = new Metrics(configuration().metricsEnabled(true));
        enabled.increment(Metrics.Name.REQUESTS_RECEIVED_COUNT);
        int expected = Metrics.get(Metrics.Name.REQUESTS_RECEIVED_COUNT);

        InMemoryMetricReader reader = InMemoryMetricReader.create();
        OtelMetricsExporter exporter = OtelMetricsExporter.startWithReader(reader);
        try {
            // when — OTel collects (triggers the observable-gauge callbacks)
            Collection<MetricData> collected = reader.collectAllMetrics();

            // then — there is a gauge per Metrics.Name, and the requests gauge reads
            // the same value the Prometheus metric holds
            assertThat(collected.size(), greaterThanOrEqualTo(Metrics.Name.values().length));
            MetricData requests = collected.stream()
                .filter(m -> m.getName().equals("requests_received_count"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("requests_received_count gauge not exported"));
            long value = requests.getLongGaugeData().getPoints().iterator().next().getValue();
            assertThat((int) value, is(expected));
        } finally {
            exporter.stop();
        }
    }

    @Test
    public void exportsJvmAndSlowRequestMetrics() {
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        OtelMetricsExporter exporter = OtelMetricsExporter.startWithReader(reader);
        try {
            Collection<MetricData> collected = reader.collectAllMetrics();
            Set<String> names = collected.stream().map(MetricData::getName).collect(Collectors.toSet());

            // JVM memory metrics
            assertThat(names, hasItem("jvm_memory_used_bytes"));
            assertThat(names, hasItem("jvm_memory_committed_bytes"));
            assertThat(names, hasItem("jvm_memory_max_bytes"));

            // JVM thread metrics
            assertThat(names, hasItem("jvm_threads_current"));
            assertThat(names, hasItem("jvm_threads_daemon"));

            // JVM GC metrics
            assertThat(names, hasItem("jvm_gc_collection_count"));
            assertThat(names, hasItem("jvm_gc_collection_seconds_sum"));

            // Slow-request counter mirror
            assertThat(names, hasItem("mock_server_slow_requests_total"));

            // JVM memory values must be positive (heap is always allocated)
            MetricData heapUsed = collected.stream()
                .filter(m -> m.getName().equals("jvm_memory_used_bytes"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("jvm_memory_used_bytes not exported"));
            long heapUsedValue = heapUsed.getLongGaugeData().getPoints().stream()
                .filter(p -> "heap".equals(p.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("area"))))  // "nonheap" is the other label value
                .mapToLong(io.opentelemetry.sdk.metrics.data.LongPointData::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError("heap area point not found in jvm_memory_used_bytes"));
            assertThat(heapUsedValue > 0, is(true));
        } finally {
            exporter.stop();
        }
    }

    @Test
    public void disabledByDefaultReturnsNull() {
        // off unless configured — startIfEnabled reads mockserver.otelMetricsEnabled (default false)
        assertThat(OtelMetricsExporter.startIfEnabled() == null, is(true));
    }
}
