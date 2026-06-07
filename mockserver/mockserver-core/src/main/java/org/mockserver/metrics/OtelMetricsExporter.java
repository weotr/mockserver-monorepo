package org.mockserver.metrics;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import org.mockserver.configuration.ConfigurationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.time.Duration;
import java.util.List;

import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * Optional exporter that publishes MockServer's <em>explicitly-defined</em>
 * metrics (the same {@link Metrics.Name} gauges exposed for Prometheus) via
 * OpenTelemetry OTLP. Off unless {@code mockserver.otelMetricsEnabled} is set.
 * <p>
 * Deliberately metrics-only: it registers one OTel observable gauge per
 * {@link Metrics.Name} whose callback reads the current {@link Metrics#get}
 * value at each collection. No spans, no auto-instrumentation. The OTel SDK is
 * self-configured here (MockServer usually runs standalone), using the OTLP
 * HTTP/protobuf exporter with the JDK HttpClient sender.
 */
public class OtelMetricsExporter {

    private static final Logger LOGGER = LoggerFactory.getLogger(OtelMetricsExporter.class);

    private final SdkMeterProvider meterProvider;

    private OtelMetricsExporter(SdkMeterProvider meterProvider) {
        this.meterProvider = meterProvider;
    }

    /**
     * Start the exporter if enabled in configuration, returning the running
     * instance, or {@code null} if disabled or startup failed (fail-soft —
     * telemetry must never prevent the server from running).
     */
    public static OtelMetricsExporter startIfEnabled() {
        if (!ConfigurationProperties.otelMetricsEnabled()) {
            return null;
        }
        try {
            String endpoint = org.mockserver.telemetry.OtelEndpoints.metrics(ConfigurationProperties.otelEndpoint());
            OtlpHttpMetricExporter otlpExporter = endpoint != null
                ? OtlpHttpMetricExporter.builder().setEndpoint(endpoint).build()
                : OtlpHttpMetricExporter.builder().build();
            MetricReader reader = PeriodicMetricReader.builder(otlpExporter)
                .setInterval(Duration.ofSeconds(ConfigurationProperties.otelMetricsExportIntervalSeconds()))
                .build();
            OtelMetricsExporter exporter = startWithReader(reader);
            LOGGER.info("OpenTelemetry metrics export enabled (endpoint {}, interval {}s)",
                endpoint == null || endpoint.isEmpty() ? "default" : endpoint,
                ConfigurationProperties.otelMetricsExportIntervalSeconds());
            return exporter;
        } catch (Exception e) {
            LOGGER.warn("failed to start OpenTelemetry metrics export ({}); continuing without it", e.getMessage());
            return null;
        }
    }

    /**
     * Build a meter provider with the given reader and register observable
     * instruments:
     * <ul>
     *   <li>One gauge per {@link Metrics.Name} (existing behaviour).</li>
     *   <li>JVM memory, thread, and GC gauges (same data as
     *       {@link JvmMetricsCollector} on the Prometheus side).</li>
     *   <li>A gauge mirroring the slow-request counter so OTLP-only consumers
     *       can observe it without a Prometheus scrape.</li>
     *   <li>A gauge mirroring the per-fault-type chaos-injection counter.</li>
     *   <li>A gauge mirroring the active service-scoped chaos count.</li>
     *   <li>A histogram for request-handling duration (seconds), fed from the
     *       same observation path as the Prometheus histogram.</li>
     * </ul>
     * Visible for testing (a test can pass an in-memory reader instead of the
     * OTLP periodic reader).
     */
    public static OtelMetricsExporter startWithReader(MetricReader reader) {
        SdkMeterProvider provider = SdkMeterProvider.builder()
            .setResource(Resource.getDefault().merge(
                Resource.create(Attributes.of(stringKey("service.name"), "mockserver"))))
            .registerMetricReader(reader)
            .build();
        Meter meter = provider.get("org.mockserver");
        for (Metrics.Name name : Metrics.Name.values()) {
            meter.gaugeBuilder(name.name().toLowerCase())
                .setDescription(name.description)
                .ofLongs()
                .buildWithCallback(measurement -> measurement.record(Metrics.get(name)));
        }
        registerJvmMetrics(meter);
        registerSlowRequestCounter(meter);
        registerChaosCounter(meter);
        registerActiveServiceChaosGauge(meter);
        registerRequestDurationHistogram(meter);
        return new OtelMetricsExporter(provider);
    }

    private static void registerJvmMetrics(Meter meter) {
        // area label values match JvmMetricsCollector ("heap" / "nonheap" — no hyphen)
        Attributes heapAttr = Attributes.of(AttributeKey.stringKey("area"), "heap");
        Attributes nonHeapAttr = Attributes.of(AttributeKey.stringKey("area"), "nonheap");

        meter.gaugeBuilder("jvm_memory_used_bytes")
            .setDescription("JVM memory used bytes, labeled by area (heap/nonheap)")
            .setUnit("By")
            .ofLongs()
            .buildWithCallback(m -> {
                MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
                m.record(mem.getHeapMemoryUsage().getUsed(), heapAttr);
                m.record(mem.getNonHeapMemoryUsage().getUsed(), nonHeapAttr);
            });
        meter.gaugeBuilder("jvm_memory_committed_bytes")
            .setDescription("JVM memory committed bytes, labeled by area (heap/nonheap)")
            .setUnit("By")
            .ofLongs()
            .buildWithCallback(m -> {
                MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
                m.record(mem.getHeapMemoryUsage().getCommitted(), heapAttr);
                m.record(mem.getNonHeapMemoryUsage().getCommitted(), nonHeapAttr);
            });
        meter.gaugeBuilder("jvm_memory_max_bytes")
            .setDescription("JVM memory max bytes, labeled by area (-1 if undefined)")
            .setUnit("By")
            .ofLongs()
            .buildWithCallback(m -> {
                MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
                m.record(mem.getHeapMemoryUsage().getMax(), heapAttr);
                m.record(mem.getNonHeapMemoryUsage().getMax(), nonHeapAttr);
            });

        meter.gaugeBuilder("jvm_threads_current")
            .setDescription("Current JVM thread count")
            .ofLongs()
            .buildWithCallback(m ->
                m.record(ManagementFactory.getThreadMXBean().getThreadCount()));
        meter.gaugeBuilder("jvm_threads_daemon")
            .setDescription("Daemon JVM thread count")
            .ofLongs()
            .buildWithCallback(m ->
                m.record(ManagementFactory.getThreadMXBean().getDaemonThreadCount()));

        // Per-collector breakdown (richer than the Prometheus-side aggregate).
        // Guard the -1 sentinel: JDK spec allows -1 when a collector exposes no stats.
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        meter.gaugeBuilder("jvm_gc_collection_count")
            .setDescription("Total GC collection count, labeled by collector name")
            .ofLongs()
            .buildWithCallback(m -> {
                for (GarbageCollectorMXBean gc : gcBeans) {
                    long count = gc.getCollectionCount();
                    if (count >= 0) {
                        m.record(count, Attributes.of(AttributeKey.stringKey("gc"), gc.getName()));
                    }
                }
            });
        meter.gaugeBuilder("jvm_gc_collection_seconds_sum")
            .setDescription("Total GC collection time in seconds, labeled by collector name")
            .setUnit("s")
            .buildWithCallback(m -> {
                for (GarbageCollectorMXBean gc : gcBeans) {
                    long ms = gc.getCollectionTime();
                    if (ms >= 0) {
                        m.record(ms / 1000.0, Attributes.of(AttributeKey.stringKey("gc"), gc.getName()));
                    }
                }
            });
    }

    private static void registerSlowRequestCounter(Meter meter) {
        meter.gaugeBuilder("mock_server_slow_requests_total")
            .setDescription("Total forwarded requests that exceeded the slow-request threshold (mirrors Prometheus counter)")
            .ofLongs()
            .buildWithCallback(m -> m.record(Metrics.getSlowRequestCount()));
    }

    private static void registerChaosCounter(Meter meter) {
        meter.gaugeBuilder("mock_server_http_chaos_injected_total")
            .setDescription("Total HTTP chaos faults injected by type (mirrors Prometheus counter)")
            .ofLongs()
            .buildWithCallback(m -> {
                for (String faultType : new String[]{"drop", "error", "latency"}) {
                    m.record(Metrics.getHttpChaosInjectedCount(faultType),
                        Attributes.of(AttributeKey.stringKey("fault_type"), faultType));
                }
            });
    }

    private static void registerActiveServiceChaosGauge(Meter meter) {
        meter.gaugeBuilder("mock_server_active_service_chaos")
            .setDescription("Number of active service-scoped chaos profiles configured with each fault type (mirrors Prometheus gauge)")
            .ofLongs()
            .buildWithCallback(m ->
                Metrics.getActiveServiceChaosCountByFaultType().forEach((faultType, count) ->
                    m.record(count, Attributes.of(AttributeKey.stringKey("fault_type"), faultType))));
    }

    private static void registerRequestDurationHistogram(Meter meter) {
        io.opentelemetry.api.metrics.DoubleHistogram requestDuration = meter.histogramBuilder("mock_server_request_duration_seconds")
            .setDescription("MockServer request handling duration in seconds")
            .setUnit("s")
            .build();
        Metrics.registerOtelRequestDurationHistogram(requestDuration);
    }

    /**
     * Stop exporting and release resources. Safe to call once.
     */
    public void stop() {
        Metrics.registerOtelRequestDurationHistogram(null);
        try {
            meterProvider.shutdown().join(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            LOGGER.debug("error shutting down OpenTelemetry metrics export: {}", e.getMessage());
        }
    }
}
