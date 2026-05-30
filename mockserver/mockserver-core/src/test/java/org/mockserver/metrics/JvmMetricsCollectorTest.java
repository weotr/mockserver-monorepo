package org.mockserver.metrics;

import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.core.Is.is;

public class JvmMetricsCollectorTest {

    @Test
    public void exposesHeapThreadsAndGcGauges() {
        MetricSnapshots snapshots = new JvmMetricsCollector().collect();

        GaugeSnapshot used = gauge(snapshots, "jvm_memory_used_bytes");
        assertThat(used, notNullValue());
        // one data point per area: heap + nonheap
        assertThat(used.getDataPoints().size(), is(2));
        double heapUsed = used.getDataPoints().stream()
            .filter(point -> "heap".equals(point.getLabels().get("area")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no heap data point"))
            .getValue();
        assertThat(heapUsed, greaterThan(0.0));

        assertThat(gauge(snapshots, "jvm_threads_current").getDataPoints().get(0).getValue(), greaterThan(0.0));
        assertThat(gauge(snapshots, "jvm_gc_collection_count"), notNullValue());
        assertThat(gauge(snapshots, "jvm_gc_collection_seconds_sum"), notNullValue());
    }

    @Test
    public void listsItsPrometheusNames() {
        assertThat(new JvmMetricsCollector().getPrometheusNames(), hasItems(
            "jvm_memory_used_bytes", "jvm_threads_current", "jvm_gc_collection_count"));
    }

    private static GaugeSnapshot gauge(MetricSnapshots snapshots, String name) {
        for (MetricSnapshot snapshot : snapshots) {
            if (snapshot.getMetadata().getName().equals(name)) {
                return (GaugeSnapshot) snapshot;
            }
        }
        return null;
    }
}
