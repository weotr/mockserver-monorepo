package org.mockserver.metrics;

import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.core.metrics.Histogram;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Action;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockserver.log.model.LogEntry.LogMessageType.EXCEPTION;

/**
 * @author jamesdbloom
 */
@SuppressWarnings({"SynchronizationOnLocalVariableOrMethodParameter", "FieldMayBeFinal"})
public class Metrics {

    private static final AtomicReference<Boolean> additionalMetricsRegistered = new AtomicReference<>(false);
    private static final Map<Name, Gauge> metrics = new ConcurrentHashMap<>();
    // Request-latency histogram. Null until metrics are enabled, so
    // observeRequestDurationSeconds() is a no-op when metrics are off (the
    // caller on the request hot path pays nothing — see the Part A/C tension).
    private static volatile Histogram requestDurationSeconds;
    // Per-route (method-labeled) histogram, registered only when route labels are enabled.
    private static volatile Histogram requestDurationByMethodSeconds;
    // Counter for slow forwarded requests. Null until metrics are enabled.
    private static volatile Counter slowRequestTotal;
    // Counter for HTTP chaos faults injected (error or latency). Null until metrics are enabled.
    private static volatile Counter httpChaosInjectedTotal;

    private final Boolean metricsEnabled;

    public Metrics(Configuration configuration) {
        metricsEnabled = configuration.metricsEnabled();
        if (metricsEnabled && additionalMetricsRegistered.compareAndSet(false, true)) {
            PrometheusRegistry.defaultRegistry.register(new BuildInfoCollector());
            PrometheusRegistry.defaultRegistry.register(new JvmMetricsCollector());
            Arrays.stream(Name.values()).forEach(Metrics::getOrCreate);
            requestDurationSeconds = Histogram.builder()
                .name("mock_server_request_duration_seconds")
                .help("MockServer request handling duration in seconds")
                .classicOnly()
                .classicUpperBounds(0.0005, 0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10)
                .register();
            slowRequestTotal = Counter.builder()
                .name("mock_server_slow_requests")
                .help("Total number of forwarded requests that exceeded the slow request threshold")
                .register();
            httpChaosInjectedTotal = Counter.builder()
                .name("mock_server_http_chaos_injected")
                .help("Total HTTP chaos faults injected by type")
                .labelNames("fault_type")
                .register();
            if (Boolean.TRUE.equals(configuration.metricsRequestDurationRouteLabels())) {
                requestDurationByMethodSeconds = Histogram.builder()
                    .name("mock_server_request_duration_by_method_seconds")
                    .help("MockServer request handling duration in seconds, labeled by HTTP method")
                    .labelNames("method")
                    .classicOnly()
                    .classicUpperBounds(0.0005, 0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10)
                    .register();
            }
        }
    }

    private static Gauge getOrCreate(Name name) {
        synchronized (name) {
            Gauge gauge = metrics.get(name);
            if (gauge == null) {
                try {
                    gauge = Gauge.builder()
                        .name(name.name().toLowerCase())
                        .help(name.description)
                        .register();
                    metrics.put(name, gauge);
                } catch (Throwable throwable) {
                    new MockServerLogger().logEvent(
                        new LogEntry()
                            .setType(EXCEPTION)
                            .setMessageFormat("exception:{} creating metric:{}")
                            .setArguments(throwable.getMessage(), name.name())
                            .setThrowable(throwable)
                    );
                }
            }
            return gauge;
        }
    }

    /**
     * Reset the one-shot registration guard and null all lazily-registered
     * metrics so that a subsequent {@code new Metrics(configuration)} call
     * re-registers them.  Also clears the default Prometheus registry.
     * <p>
     * Public for cross-package test access (e.g. chaos injection tests);
     * intended for test use only to guarantee deterministic test ordering.
     */
    public static void resetAdditionalMetricsForTesting() {
        additionalMetricsRegistered.set(false);
        requestDurationSeconds = null;
        requestDurationByMethodSeconds = null;
        slowRequestTotal = null;
        httpChaosInjectedTotal = null;
        metrics.clear();
        PrometheusRegistry.defaultRegistry.clear();
    }

    public static void clear() {
        metrics.forEach((name, gauge) -> gauge.set(0));
    }

    public static void clear(Name name) {
        getOrCreate(name).set(0);
    }

    public void set(Name name, Integer value) {
        if (metricsEnabled) {
            getOrCreate(name).set(value);
        }
    }

    public static Integer get(Name name) {
        return (int) getOrCreate(name).get();
    }

    /**
     * Record a request-handling duration (seconds) in the latency histogram.
     * No-op unless metrics are enabled (the histogram is null until then), so a
     * caller on the request hot path pays nothing when metrics are off.
     */
    public static void observeRequestDurationSeconds(double seconds) {
        Histogram histogram = requestDurationSeconds;
        if (histogram != null) {
            histogram.observe(seconds);
        }
    }

    /**
     * Record a request-handling duration (seconds) in the per-method labeled histogram.
     * No-op unless route labels are enabled.
     *
     * @param seconds  duration in seconds
     * @param method   the HTTP method (e.g. "GET", "POST")
     */
    public static void observeRequestDurationByMethodSeconds(double seconds, String method) {
        Histogram histogram = requestDurationByMethodSeconds;
        if (histogram != null && method != null) {
            histogram.labelValues(method.toUpperCase()).observe(seconds);
        }
    }

    /**
     * Return the current slow-request count, or 0 if metrics are disabled.
     * Used by {@link OtelMetricsExporter} to mirror the Prometheus counter via OTLP.
     */
    public static long getSlowRequestCount() {
        Counter counter = slowRequestTotal;
        return counter != null ? (long) counter.get() : 0L;
    }

    /**
     * Increment the slow request counter. No-op unless metrics are enabled.
     */
    public static void incrementSlowRequestTotal() {
        Counter counter = slowRequestTotal;
        if (counter != null) {
            counter.inc();
        }
    }

    /**
     * Increment the HTTP chaos injected counter for the given fault type.
     * No-op when metrics are disabled (counter not registered) or faultType is null.
     *
     * @param faultType one of "drop", "error", or "latency"
     */
    public static void incrementHttpChaosInjected(String faultType) {
        Counter counter = httpChaosInjectedTotal;
        if (counter != null && faultType != null) {
            counter.labelValues(faultType).inc();
        }
    }

    public void increment(Name name) {
        if (metricsEnabled) {
            getOrCreate(name).inc();
        }
    }

    public void increment(Action.Type type) {
        if (metricsEnabled) {
            increment(Name.valueOf(type.name() + "_ACTIONS_COUNT"));
        }
    }

    public void decrement(Name name) {
        if (metricsEnabled) {
            getOrCreate(name).dec();
        }
    }

    public void decrement(Action.Type type) {
        if (metricsEnabled) {
            decrement(Name.valueOf(type.name() + "_ACTIONS_COUNT"));
        }
    }

    public static void clearRequestAndExpectationMetrics() {
        clear(Name.REQUESTS_RECEIVED_COUNT);
        clear(Name.EXPECTATIONS_NOT_MATCHED_COUNT);
        clear(Name.RESPONSE_EXPECTATIONS_MATCHED_COUNT);
    }

    public static void clearActionMetrics() {
        clear(Name.FORWARD_ACTIONS_COUNT);
        clear(Name.FORWARD_TEMPLATE_ACTIONS_COUNT);
        clear(Name.FORWARD_CLASS_CALLBACK_ACTIONS_COUNT);
        clear(Name.FORWARD_OBJECT_CALLBACK_ACTIONS_COUNT);
        clear(Name.FORWARD_REPLACE_ACTIONS_COUNT);
        clear(Name.RESPONSE_ACTIONS_COUNT);
        clear(Name.RESPONSE_TEMPLATE_ACTIONS_COUNT);
        clear(Name.RESPONSE_CLASS_CALLBACK_ACTIONS_COUNT);
        clear(Name.RESPONSE_OBJECT_CALLBACK_ACTIONS_COUNT);
        clear(Name.SSE_RESPONSE_ACTIONS_COUNT);
        clear(Name.LLM_RESPONSE_ACTIONS_COUNT);
        clear(Name.LLM_CHAOS_INJECTED_COUNT);
        clear(Name.WEBSOCKET_RESPONSE_ACTIONS_COUNT);
        clear(Name.GRPC_STREAM_RESPONSE_ACTIONS_COUNT);
        clear(Name.BINARY_RESPONSE_ACTIONS_COUNT);
        clear(Name.DNS_RESPONSE_ACTIONS_COUNT);
        clear(Name.ERROR_ACTIONS_COUNT);
    }

    public static void clearWebSocketMetrics() {
        clear(Name.WEBSOCKET_CALLBACK_CLIENTS_COUNT);
        clear(Name.WEBSOCKET_CALLBACK_RESPONSE_HANDLERS_COUNT);
        clear(Name.WEBSOCKET_CALLBACK_FORWARD_HANDLERS_COUNT);
    }

    public enum Name {
        REQUESTS_RECEIVED_COUNT("Expectation not matched count"),
        EXPECTATIONS_NOT_MATCHED_COUNT("Expectation not matched count"),
        RESPONSE_EXPECTATIONS_MATCHED_COUNT("Response expectation matched count"),
        FORWARD_EXPECTATIONS_MATCHED_COUNT("Forward expectation matched count"),
        FORWARD_ACTIONS_COUNT("Action forward count"),
        FORWARD_TEMPLATE_ACTIONS_COUNT("Action forward template count"),
        FORWARD_CLASS_CALLBACK_ACTIONS_COUNT("Action forward class callback count"),
        FORWARD_OBJECT_CALLBACK_ACTIONS_COUNT("Action forward object callback count"),
        FORWARD_REPLACE_ACTIONS_COUNT("Action forward replace count"),
        RESPONSE_ACTIONS_COUNT("Action response count"),
        RESPONSE_TEMPLATE_ACTIONS_COUNT("Action response template count"),
        RESPONSE_CLASS_CALLBACK_ACTIONS_COUNT("Action response class callback count"),
        RESPONSE_OBJECT_CALLBACK_ACTIONS_COUNT("Action response object callback count"),
        SSE_RESPONSE_ACTIONS_COUNT("Action SSE response count"),
        LLM_RESPONSE_ACTIONS_COUNT("Action LLM response count"),
        LLM_CHAOS_INJECTED_COUNT("Action LLM chaos injected count"),
        WEBSOCKET_RESPONSE_ACTIONS_COUNT("Action WebSocket response count"),
        GRPC_STREAM_RESPONSE_ACTIONS_COUNT("Action gRPC stream response count"),
        BINARY_RESPONSE_ACTIONS_COUNT("Action binary response count"),
        DNS_RESPONSE_ACTIONS_COUNT("Action DNS response count"),
        ERROR_ACTIONS_COUNT("Action error count"),
        WEBSOCKET_CALLBACK_CLIENTS_COUNT("Websocket callback client count"),
        WEBSOCKET_CALLBACK_RESPONSE_HANDLERS_COUNT("Websocket callback response handler count"),
        WEBSOCKET_CALLBACK_FORWARD_HANDLERS_COUNT("Websocket callback forward handler count");

        public final String description;

        Name(String description) {
            this.description = description;
        }
    }
}
