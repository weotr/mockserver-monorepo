package org.mockserver.telemetry;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.After;
import org.junit.Test;
import org.mockserver.model.Provider;

import java.util.Collections;
import java.util.List;

import static io.opentelemetry.api.common.AttributeKey.longKey;
import static io.opentelemetry.api.common.AttributeKey.stringArrayKey;
import static io.opentelemetry.api.common.AttributeKey.stringKey;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.model.Completion.completion;
import static org.mockserver.model.ToolUse.toolUse;
import static org.mockserver.model.Usage.usage;

public class GenAiSpansTest {

    @After
    public void resetTracer() {
        // guarantee isolation regardless of test order or shared JVM state;
        // startWithProcessor now installs both tracers so reset both
        GenAiSpans.setTracer(null);
        RequestSpans.setTracer(null);
    }

    @Test
    public void emitsGenAiSpanWithSemanticConventionAttributes() {
        InMemorySpanExporter spanExporter = InMemorySpanExporter.create();
        GenAiSpanExporter exporter = GenAiSpanExporter.startWithProcessor(SimpleSpanProcessor.create(spanExporter));
        try {
            GenAiSpans.recordCompletion(Provider.ANTHROPIC, "claude-3-5-sonnet",
                completion()
                    .withText("hi")
                    .withStopReason("end_turn")
                    .withUsage(usage().withInputTokens(10).withOutputTokens(2))
                    .withToolCalls(toolUse("search")));

            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            assertThat(spans.size(), is(1));
            SpanData span = spans.get(0);
            assertThat(span.getName(), is("chat claude-3-5-sonnet"));
            assertThat(span.getAttributes().get(stringKey("gen_ai.operation.name")), is("chat"));
            assertThat(span.getAttributes().get(stringKey("gen_ai.system")), is("anthropic"));
            assertThat(span.getAttributes().get(stringKey("gen_ai.request.model")), is("claude-3-5-sonnet"));
            assertThat(span.getAttributes().get(stringArrayKey("gen_ai.response.finish_reasons")), is(Collections.singletonList("end_turn")));
            assertThat(span.getAttributes().get(longKey("gen_ai.usage.input_tokens")), is(10L));
            assertThat(span.getAttributes().get(longKey("gen_ai.usage.output_tokens")), is(2L));
            assertThat(span.getAttributes().get(longKey("mockserver.gen_ai.tool_call_count")), is(1L));
        } finally {
            exporter.stop();
        }
    }

    @Test
    public void mapsProvidersToGenAiSemconvSystemValues() {
        assertThat(GenAiSpans.genAiSystem(Provider.OPENAI), is("openai"));
        assertThat(GenAiSpans.genAiSystem(Provider.OPENAI_RESPONSES), is("openai"));
        assertThat(GenAiSpans.genAiSystem(Provider.ANTHROPIC), is("anthropic"));
        assertThat(GenAiSpans.genAiSystem(Provider.AZURE_OPENAI), is("az.ai.openai"));
        assertThat(GenAiSpans.genAiSystem(Provider.BEDROCK), is("aws.bedrock"));
        assertThat(GenAiSpans.genAiSystem(Provider.GEMINI), is("gcp.gemini"));
        assertThat(GenAiSpans.genAiSystem(Provider.OLLAMA), is("ollama"));
        assertThat(GenAiSpans.genAiSystem(null), is("unknown"));
    }

    @Test
    public void recordCompletionIsNoOpAndSafeWhenDisabled() {
        // with no exporter installed the tracer is null; recording must not throw
        GenAiSpans.recordCompletion(Provider.OPENAI, "gpt-4o", completion().withText("x"));
    }
}
