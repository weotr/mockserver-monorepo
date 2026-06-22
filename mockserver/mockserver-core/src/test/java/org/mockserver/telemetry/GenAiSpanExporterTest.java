package org.mockserver.telemetry;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.model.Provider;

import java.util.List;

import static io.opentelemetry.api.common.AttributeKey.stringKey;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.model.Completion.completion;
import static org.mockserver.model.Usage.usage;

/**
 * Behavioural tests for {@link GenAiSpanExporter} — the span-wiring hub that installs an
 * OpenTelemetry tracer into the process-wide {@link GenAiSpans} and {@link RequestSpans} holders
 * and tears it down on {@code stop()}.
 *
 * <p>{@link GenAiSpanExporter#startWithProcessor} is the supported test seam (it accepts a
 * {@link io.opentelemetry.sdk.trace.SpanProcessor} over an in-memory exporter), so these assert
 * OBSERVABLE behaviour: after start both span holders report {@code isEnabled()} and a completion
 * recorded through the public {@link GenAiSpans#recordCompletion} API actually reaches the in-memory
 * exporter; after {@code stop()} both holders are disabled again.
 *
 * <p>The exporter mutates the process-wide static tracer in {@link GenAiSpans}/{@link RequestSpans},
 * so this is a global-state-mutating test (sequential phase in the surefire split). Each test
 * restores the tracers to {@code null} in {@code tearDown}.
 */
public class GenAiSpanExporterTest {

    private GenAiSpanExporter exporter;

    @After
    public void tearDown() {
        // restore the process-wide tracers regardless of what the test left installed
        if (exporter != null) {
            exporter.stop();
            exporter = null;
        }
        GenAiSpans.setTracer(null);
        RequestSpans.setTracer(null);
        ConfigurationProperties.otelTracesEnabled(false);
    }

    @Test
    public void startInstallsTracerIntoBothSpanHolders() {
        InMemorySpanExporter spanExporter = InMemorySpanExporter.create();

        exporter = GenAiSpanExporter.startWithProcessor(SimpleSpanProcessor.create(spanExporter));

        assertThat(exporter, is(notNullValue()));
        assertThat("GenAi span recording must be enabled after start", GenAiSpans.isEnabled(), is(true));
        assertThat("request span recording must be enabled after start", RequestSpans.isEnabled(), is(true));
    }

    @Test
    public void completionRecordedThroughInstalledTracerReachesExporter() {
        InMemorySpanExporter spanExporter = InMemorySpanExporter.create();
        exporter = GenAiSpanExporter.startWithProcessor(SimpleSpanProcessor.create(spanExporter));

        // use the PUBLIC no-tracer overload so the span flows through the installed process-wide tracer
        GenAiSpans.recordCompletion(Provider.ANTHROPIC, "claude-3-5-sonnet",
            completion().withText("hi").withUsage(usage().withInputTokens(3).withOutputTokens(1)));

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat("the wired tracer must export the recorded completion span", spans.size(), is(1));
        assertThat(spans.get(0).getName(), is("chat claude-3-5-sonnet"));
        assertThat(spans.get(0).getAttributes().get(stringKey("gen_ai.system")), is("anthropic"));
    }

    @Test
    public void requestRecordedThroughInstalledTracerReachesExporter() {
        InMemorySpanExporter spanExporter = InMemorySpanExporter.create();
        exporter = GenAiSpanExporter.startWithProcessor(SimpleSpanProcessor.create(spanExporter));

        RequestSpans.recordRequest("GET", "/health", 200, "exp-1", 5L, null);

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat("the wired tracer must export the recorded request span",
            spans.size(), is(greaterThanOrEqualTo(1)));
    }

    @Test
    public void resourceCarriesMockserverServiceName() {
        InMemorySpanExporter spanExporter = InMemorySpanExporter.create();
        exporter = GenAiSpanExporter.startWithProcessor(SimpleSpanProcessor.create(spanExporter));

        GenAiSpans.recordCompletion(Provider.OPENAI, "gpt-4o", completion().withText("x"));

        SpanData span = spanExporter.getFinishedSpanItems().get(0);
        assertThat("the exporter's resource must identify the service as mockserver",
            span.getResource().getAttribute(stringKey("service.name")), is("mockserver"));
    }

    @Test
    public void stopDisablesBothSpanHolders() {
        InMemorySpanExporter spanExporter = InMemorySpanExporter.create();
        GenAiSpanExporter started = GenAiSpanExporter.startWithProcessor(SimpleSpanProcessor.create(spanExporter));

        started.stop();
        // tearDown's exporter.stop() must not run on an already-stopped instance
        exporter = null;

        assertThat("GenAi span recording must be disabled after stop", GenAiSpans.isEnabled(), is(false));
        assertThat("request span recording must be disabled after stop", RequestSpans.isEnabled(), is(false));
    }

    @Test
    public void afterStopRecordedCompletionIsANoOp() {
        InMemorySpanExporter spanExporter = InMemorySpanExporter.create();
        GenAiSpanExporter started = GenAiSpanExporter.startWithProcessor(SimpleSpanProcessor.create(spanExporter));
        started.stop();
        exporter = null;

        // no tracer installed -> recordCompletion is a no-op and nothing is exported
        GenAiSpans.recordCompletion(Provider.ANTHROPIC, "claude-3-5-sonnet", completion().withText("hi"));

        assertThat(spanExporter.getFinishedSpanItems().size(), is(0));
    }

    @Test
    public void startIfEnabledReturnsNullWhenTracesDisabled() {
        // the OTel flag is off (default / restored in tearDown): no exporter, no network access
        ConfigurationProperties.otelTracesEnabled(false);

        GenAiSpanExporter result = GenAiSpanExporter.startIfEnabled();

        assertThat("disabled OTel must not start an exporter", result, is(nullValue()));
        assertThat("the span holders must stay disabled when the flag is off", GenAiSpans.isEnabled(), is(false));
    }
}
