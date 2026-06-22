package org.mockserver.telemetry;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.After;
import org.junit.Test;

import java.util.List;

import static io.opentelemetry.api.common.AttributeKey.longKey;
import static io.opentelemetry.api.common.AttributeKey.stringKey;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.Is.is;

/**
 * Tests request span recording. Span-emitting tests use a per-test tracer
 * (via the package-private overload) so they are safe under parallel execution.
 * Lifecycle tests (isEnabled / no-op when disabled) that briefly touch the
 * global static are isolated with @After cleanup.
 */
public class RequestSpansTest {

    @After
    public void resetTracer() {
        // clean up in case any lifecycle test set the global static
        GenAiSpans.setTracer(null);
        RequestSpans.setTracer(null);
    }

    /**
     * Create a per-test tracer backed by the given in-memory exporter.
     * Each test gets its own SdkTracerProvider so spans never leak between tests.
     */
    private static Tracer tracerFor(InMemorySpanExporter exporter) {
        SdkTracerProvider provider = SdkTracerProvider.builder()
            .setResource(Resource.create(Attributes.empty()))
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build();
        return provider.get("org.mockserver.test");
    }

    @Test
    public void emitsServerSpanWithHttpAttributes() {
        InMemorySpanExporter spanExporter = InMemorySpanExporter.create();
        Tracer tracer = tracerFor(spanExporter);

        RequestSpans.recordRequest(tracer, "GET", "/api/users", 200, "exp-123", 42, null);

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans.size(), is(1));
        SpanData span = spans.get(0);
        assertThat(span.getName(), is("GET /api/users"));
        assertThat(span.getKind(), is(SpanKind.SERVER));
        assertThat(span.getAttributes().get(stringKey("http.request.method")), is("GET"));
        assertThat(span.getAttributes().get(stringKey("http.route")), is("/api/users"));
        assertThat(span.getAttributes().get(longKey("http.response.status_code")), is(200L));
        assertThat(span.getAttributes().get(stringKey("mockserver.expectation_id")), is("exp-123"));
        assertThat(span.getAttributes().get(longKey("mockserver.response_time_ms")), is(42L));
    }

    @Test
    public void emitsServerAddressAndPortForForwardPath() {
        InMemorySpanExporter spanExporter = InMemorySpanExporter.create();
        Tracer tracer = tracerFor(spanExporter);

        RequestSpans.recordRequest(tracer, "GET", "/api/users", 200, "exp-123", 42, null, "api.upstream.com", 8443);

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans.size(), is(1));
        SpanData span = spans.get(0);
        assertThat(span.getAttributes().get(stringKey("server.address")), is("api.upstream.com"));
        assertThat(span.getAttributes().get(longKey("server.port")), is(8443L));
    }

    @Test
    public void omitsServerAddressAndPortOnMockedPath() {
        InMemorySpanExporter spanExporter = InMemorySpanExporter.create();
        Tracer tracer = tracerFor(spanExporter);

        // mocked path: no upstream address/port
        RequestSpans.recordRequest(tracer, "GET", "/api/users", 200, "exp-123", 42, null, null, null);

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans.size(), is(1));
        SpanData span = spans.get(0);
        assertThat(span.getAttributes().get(stringKey("server.address")), is(nullValue()));
        assertThat(span.getAttributes().get(longKey("server.port")), is(nullValue()));
    }

    @Test
    public void emitsSpanWithRemoteParentContext() {
        InMemorySpanExporter spanExporter = InMemorySpanExporter.create();
        Tracer tracer = tracerFor(spanExporter);

        W3CTraceContext parentCtx = new W3CTraceContext(
            "00",
            "4bf92f3577b34da6a3ce929d0e0e4736",
            "00f067aa0ba902b7",
            "01",
            null
        );
        RequestSpans.recordRequest(tracer, "POST", "/api/orders", 201, "exp-456", 100, parentCtx);

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans.size(), is(1));
        SpanData span = spans.get(0);
        assertThat(span.getName(), is("POST /api/orders"));
        // Verify the span inherits the remote parent's trace ID
        assertThat(span.getTraceId(), is("4bf92f3577b34da6a3ce929d0e0e4736"));
        // The span's parent span ID should be the remote parent's span ID
        assertThat(span.getParentSpanId(), is("00f067aa0ba902b7"));
        // Verify sampled flag is propagated
        assertThat(span.getSpanContext().getTraceFlags(), is(TraceFlags.getSampled()));
    }

    @Test
    public void respectsUnsampledRemoteParentDecision() {
        InMemorySpanExporter spanExporter = InMemorySpanExporter.create();
        Tracer tracer = tracerFor(spanExporter);

        W3CTraceContext parentCtx = new W3CTraceContext(
            "00",
            "aaaabbbbccccddddeeee111122223333",
            "1234567890abcdef",
            "00",  // unsampled
            null
        );
        RequestSpans.recordRequest(tracer, "GET", "/health", 200, null, 1, parentCtx);

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        // The default SdkTracerProvider sampler is parentBased(alwaysOn), which
        // respects the remote parent's unsampled flag and drops the child span.
        assertThat(spans.size(), is(0));
    }

    @Test
    public void omitsOptionalAttributesWhenNull() {
        InMemorySpanExporter spanExporter = InMemorySpanExporter.create();
        Tracer tracer = tracerFor(spanExporter);

        RequestSpans.recordRequest(tracer, "DELETE", "/api/items/1", null, null, 0, null);

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans.size(), is(1));
        SpanData span = spans.get(0);
        assertThat(span.getName(), is("DELETE /api/items/1"));
        assertThat(span.getAttributes().get(stringKey("http.request.method")), is("DELETE"));
        assertThat(span.getAttributes().get(stringKey("http.route")), is("/api/items/1"));
        // Null status code, null expectation id, 0 response time -> attributes not set
        assertThat(span.getAttributes().get(longKey("http.response.status_code")), is(nullValue()));
        assertThat(span.getAttributes().get(stringKey("mockserver.expectation_id")), is(nullValue()));
        assertThat(span.getAttributes().get(longKey("mockserver.response_time_ms")), is(nullValue()));
    }

    @Test
    public void usesDefaultsWhenMethodAndPathAreNull() {
        InMemorySpanExporter spanExporter = InMemorySpanExporter.create();
        Tracer tracer = tracerFor(spanExporter);

        RequestSpans.recordRequest(tracer, null, null, 200, null, 0, null);

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans.size(), is(1));
        SpanData span = spans.get(0);
        // null method falls back to "HTTP", null path falls back to "/"
        assertThat(span.getName(), is("HTTP /"));
        assertThat(span.getAttributes().get(stringKey("http.request.method")), is("HTTP"));
    }

    @Test
    public void recordRequestIsNoOpAndSafeWhenDisabled() {
        // with no exporter installed the tracer is null; recording must not throw
        RequestSpans.recordRequest("GET", "/test", 200, "some-id", 10, null);
    }

    @Test
    public void isEnabledReturnsFalseWhenNoTracer() {
        assertThat(RequestSpans.isEnabled(), is(false));
    }

    @Test
    public void isEnabledReturnsTrueWhenTracerInstalled() {
        InMemorySpanExporter spanExporter = InMemorySpanExporter.create();
        GenAiSpanExporter exporter = GenAiSpanExporter.startWithProcessor(SimpleSpanProcessor.create(spanExporter));
        try {
            assertThat(RequestSpans.isEnabled(), is(true));
        } finally {
            exporter.stop();
        }
    }

    @Test
    public void isEnabledReturnsFalseAfterStop() {
        InMemorySpanExporter spanExporter = InMemorySpanExporter.create();
        GenAiSpanExporter exporter = GenAiSpanExporter.startWithProcessor(SimpleSpanProcessor.create(spanExporter));
        exporter.stop();
        assertThat(RequestSpans.isEnabled(), is(false));
    }

    @Test
    public void ignoresInvalidParentContext() {
        InMemorySpanExporter spanExporter = InMemorySpanExporter.create();
        Tracer tracer = tracerFor(spanExporter);

        // Invalid context (traceId too short)
        W3CTraceContext invalidCtx = new W3CTraceContext("00", "short", "alsoshort", "01", null);
        RequestSpans.recordRequest(tracer, "GET", "/test", 200, null, 0, invalidCtx);

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans.size(), is(1));
        SpanData span = spans.get(0);
        // Span should still be created, just without a remote parent
        assertThat(span.getName(), is("GET /test"));
        // Parent span ID should be empty (no remote parent wired)
        assertThat(span.getParentSpanId(), is("0000000000000000"));
    }
}
