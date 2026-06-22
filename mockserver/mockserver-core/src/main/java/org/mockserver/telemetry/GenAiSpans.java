package org.mockserver.telemetry;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import org.mockserver.model.Completion;
import org.mockserver.model.Provider;
import org.mockserver.model.Usage;

import java.util.Collections;

/**
 * Static emit point for explicit GenAI semantic-convention spans describing the
 * LLM completions MockServer serves. A process-wide holder (like the metrics
 * gauges) so the action handler can record a span without threading a tracer
 * through its constructors.
 * <p>
 * No-op unless {@link GenAiSpanExporter} has installed a tracer (i.e. unless
 * {@code mockserver.otelTracesEnabled} is set). These are spans MockServer codes
 * deliberately — there is no auto-instrumentation. Fail-soft: any error while
 * recording is swallowed so telemetry never affects responses.
 */
public final class GenAiSpans {

    private static volatile Tracer tracer;

    private GenAiSpans() {
    }

    static void setTracer(Tracer newTracer) {
        tracer = newTracer;
    }

    /** True if span emission is currently active. */
    public static boolean isEnabled() {
        return tracer != null;
    }

    /**
     * Emit one GenAI span for a served completion, following the OpenTelemetry
     * GenAI semantic conventions ({@code gen_ai.*}). No-op when disabled.
     */
    public static void recordCompletion(Provider provider, String model, Completion completion) {
        Tracer current = tracer;
        if (current == null) {
            return;
        }
        recordCompletion(current, provider, model, completion);
    }

    /**
     * Record a GenAI completion span using the given tracer. Package-private to
     * allow tests to call this with a per-test tracer, avoiding the shared
     * process-wide static and the cross-contamination that causes when test
     * classes run in parallel.
     */
    static void recordCompletion(Tracer explicitTracer, Provider provider, String model, Completion completion) {
        try {
            String resolvedModel = model != null && !model.isEmpty() ? model : "unknown";
            Span span = explicitTracer.spanBuilder("chat " + resolvedModel)
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();
            try {
                span.setAttribute("gen_ai.operation.name", "chat");
                if (provider != null) {
                    span.setAttribute("gen_ai.system", genAiSystem(provider));
                }
                span.setAttribute("gen_ai.request.model", resolvedModel);
                if (completion != null) {
                    if (completion.getStopReason() != null) {
                        // semconv: gen_ai.response.finish_reasons is a string[]
                        span.setAttribute(AttributeKey.stringArrayKey("gen_ai.response.finish_reasons"),
                            Collections.singletonList(completion.getStopReason()));
                    }
                    Usage usage = completion.getUsage();
                    if (usage != null) {
                        if (usage.getInputTokens() != null) {
                            span.setAttribute("gen_ai.usage.input_tokens", usage.getInputTokens().longValue());
                        }
                        if (usage.getOutputTokens() != null) {
                            span.setAttribute("gen_ai.usage.output_tokens", usage.getOutputTokens().longValue());
                        }
                        // Cached-input and reasoning token counts have no GenAI semconv
                        // attribute yet — emit under the mockserver namespace (like the
                        // tool-call count) so cost dashboards can split cached/reasoning
                        // spend. Omitted entirely when the provider didn't report them.
                        if (usage.getCachedInputTokens() != null) {
                            span.setAttribute("mockserver.gen_ai.usage.cached_input_tokens", usage.getCachedInputTokens().longValue());
                        }
                        if (usage.getCacheCreationTokens() != null) {
                            span.setAttribute("mockserver.gen_ai.usage.cache_creation_tokens", usage.getCacheCreationTokens().longValue());
                        }
                        if (usage.getReasoningTokens() != null) {
                            span.setAttribute("mockserver.gen_ai.usage.reasoning_tokens", usage.getReasoningTokens().longValue());
                        }
                    }
                    if (completion.getToolCalls() != null && !completion.getToolCalls().isEmpty()) {
                        // non-standard extension (no semconv attribute for a count) — namespaced
                        span.setAttribute("mockserver.gen_ai.tool_call_count", (long) completion.getToolCalls().size());
                    }
                }
            } finally {
                span.end();
            }
        } catch (Exception e) {
            // fail-soft: telemetry must never affect the served response
        }
    }

    /**
     * Map a {@link Provider} to its OpenTelemetry GenAI {@code gen_ai.system}
     * registry value (the raw enum name is not conformant for several providers).
     */
    static String genAiSystem(Provider provider) {
        if (provider == null) {
            return "unknown";
        }
        switch (provider) {
            case OPENAI:
            case OPENAI_RESPONSES:
                return "openai";
            case ANTHROPIC:
                return "anthropic";
            case AZURE_OPENAI:
                return "az.ai.openai";
            case BEDROCK:
                return "aws.bedrock";
            case GEMINI:
                return "gcp.gemini";
            case OLLAMA:
                return "ollama";
            default:
                return provider.name().toLowerCase();
        }
    }
}
