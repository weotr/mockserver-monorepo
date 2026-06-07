package org.mockserver.telemetry;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import org.mockserver.configuration.ConfigurationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * Optional exporter that publishes explicit OpenTelemetry spans via OTLP.
 * Off unless {@code mockserver.otelTracesEnabled} is set. Self-configures
 * the OTel trace SDK (OTLP HTTP/protobuf, JDK sender) and installs the
 * tracer into both {@link GenAiSpans} and {@link RequestSpans}. Fail-soft:
 * a startup failure logs one line and leaves span emission disabled.
 */
public class GenAiSpanExporter {

    private static final Logger LOGGER = LoggerFactory.getLogger(GenAiSpanExporter.class);

    private final SdkTracerProvider tracerProvider;

    private GenAiSpanExporter(SdkTracerProvider tracerProvider) {
        this.tracerProvider = tracerProvider;
    }

    public static GenAiSpanExporter startIfEnabled() {
        if (!ConfigurationProperties.otelTracesEnabled()) {
            return null;
        }
        try {
            String endpoint = OtelEndpoints.traces(ConfigurationProperties.otelEndpoint());
            OtlpHttpSpanExporter spanExporter = endpoint != null
                ? OtlpHttpSpanExporter.builder().setEndpoint(endpoint).build()
                : OtlpHttpSpanExporter.builder().build();
            GenAiSpanExporter exporter = startWithProcessor(BatchSpanProcessor.builder(spanExporter).build());
            LOGGER.info("OpenTelemetry GenAI span export enabled (endpoint {})",
                endpoint == null ? "default" : endpoint);
            return exporter;
        } catch (Exception e) {
            LOGGER.warn("failed to start OpenTelemetry GenAI span export ({}); continuing without it", e.getMessage());
            return null;
        }
    }

    /**
     * Build a tracer provider with the given span processor and install its
     * tracer into both {@link GenAiSpans} and {@link RequestSpans}. Visible
     * for testing (a test can pass a processor over an in-memory span exporter).
     */
    public static GenAiSpanExporter startWithProcessor(SpanProcessor processor) {
        SdkTracerProvider provider = SdkTracerProvider.builder()
            .setResource(Resource.getDefault().merge(
                Resource.create(Attributes.of(stringKey("service.name"), "mockserver"))))
            .addSpanProcessor(processor)
            .build();
        GenAiSpans.setTracer(provider.get("org.mockserver"));
        RequestSpans.setTracer(provider.get("org.mockserver"));
        return new GenAiSpanExporter(provider);
    }

    public void stop() {
        GenAiSpans.setTracer(null);
        RequestSpans.setTracer(null);
        try {
            tracerProvider.shutdown().join(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            LOGGER.debug("error shutting down OpenTelemetry GenAI span export: {}", e.getMessage());
        }
    }
}
