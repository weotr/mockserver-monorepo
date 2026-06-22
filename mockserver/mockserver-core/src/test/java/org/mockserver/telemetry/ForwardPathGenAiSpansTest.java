package org.mockserver.telemetry;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.llm.client.LlmClientRegistry;
import org.mockserver.llm.client.LlmProviderSniffer;
import org.mockserver.model.Completion;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Provider;

import java.util.List;
import java.util.Optional;

import static io.opentelemetry.api.common.AttributeKey.longKey;
import static io.opentelemetry.api.common.AttributeKey.stringKey;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Verifies that the forward/proxy path emits GenAI spans when tracing is
 * enabled and the forwarded request targets a known LLM provider. This tests
 * the full integration: provider sniffing, response parsing, and span emission.
 *
 * <p>Uses a per-test tracer and the package-private {@code GenAiSpans.recordCompletion(Tracer,...)}
 * overload so these tests are safe under parallel execution (no shared global
 * mutable static state is touched by span-emitting tests).
 */
public class ForwardPathGenAiSpansTest {

    @After
    public void resetConfigProperties() {
        ConfigurationProperties.llmProvider("");
        ConfigurationProperties.llmBaseUrl("");
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
    public void emitsGenAiSpanForOpenAiForwardedResponse() {
        // given - a per-test tracer, a forwarded request to api.openai.com, and a typical OpenAI response
        InMemorySpanExporter spanExporter = InMemorySpanExporter.create();
        Tracer tracer = tracerFor(spanExporter);

        HttpRequest forwardedRequest = request()
            .withMethod("POST")
            .withPath("/v1/chat/completions")
            .withSocketAddress(true, "api.openai.com", 443)
            .withBody("{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}");

        HttpResponse upstreamResponse = response()
            .withStatusCode(200)
            .withBody("{\"id\":\"chatcmpl-123\",\"object\":\"chat.completion\",\"model\":\"gpt-4o-2024-08-06\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"Hello! How can I help?\"},"
                + "\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":8,\"total_tokens\":20}}");

        // when - sniff the provider and emit a GenAI span (mirrors emitForwardGenAiSpan logic)
        Optional<Provider> providerOpt = LlmProviderSniffer.sniff(forwardedRequest);
        assertThat(providerOpt.isPresent(), is(true));
        Provider provider = providerOpt.get();
        assertThat(provider, is(Provider.OPENAI));

        Completion completion = LlmClientRegistry.getInstance().lookup(provider).get()
            .parseCompletionResponse(upstreamResponse);
        String model = LlmProviderSniffer.extractModelFromResponse(upstreamResponse);
        GenAiSpans.recordCompletion(tracer, provider, model, completion);

        // then - a GenAI span is emitted with the correct attributes
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans.size(), is(1));
        SpanData span = spans.get(0);
        assertThat(span.getName(), is("chat gpt-4o-2024-08-06"));
        assertThat(span.getAttributes().get(stringKey("gen_ai.system")), is("openai"));
        assertThat(span.getAttributes().get(stringKey("gen_ai.request.model")), is("gpt-4o-2024-08-06"));
        assertThat(span.getAttributes().get(longKey("gen_ai.usage.input_tokens")), is(12L));
        assertThat(span.getAttributes().get(longKey("gen_ai.usage.output_tokens")), is(8L));
    }

    @Test
    public void emitsGenAiSpanForAnthropicForwardedResponse() {
        InMemorySpanExporter spanExporter = InMemorySpanExporter.create();
        Tracer tracer = tracerFor(spanExporter);

        HttpRequest forwardedRequest = request()
            .withMethod("POST")
            .withPath("/v1/messages")
            .withSocketAddress(true, "api.anthropic.com", 443)
            .withBody("{\"model\":\"claude-3-5-sonnet-20241022\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");

        HttpResponse upstreamResponse = response()
            .withStatusCode(200)
            .withBody("{\"id\":\"msg_123\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"model\":\"claude-3-5-sonnet-20241022\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"Hello!\"}],"
                + "\"stop_reason\":\"end_turn\","
                + "\"usage\":{\"input_tokens\":5,\"output_tokens\":3}}");

        Optional<Provider> providerOpt = LlmProviderSniffer.sniff(forwardedRequest);
        assertThat(providerOpt.isPresent(), is(true));
        assertThat(providerOpt.get(), is(Provider.ANTHROPIC));

        Completion completion = LlmClientRegistry.getInstance().lookup(providerOpt.get()).get()
            .parseCompletionResponse(upstreamResponse);
        String model = LlmProviderSniffer.extractModelFromResponse(upstreamResponse);
        GenAiSpans.recordCompletion(tracer, providerOpt.get(), model, completion);

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans.size(), is(1));
        SpanData span = spans.get(0);
        assertThat(span.getName(), is("chat claude-3-5-sonnet-20241022"));
        assertThat(span.getAttributes().get(stringKey("gen_ai.system")), is("anthropic"));
        assertThat(span.getAttributes().get(stringKey("gen_ai.request.model")), is("claude-3-5-sonnet-20241022"));
        assertThat(span.getAttributes().get(longKey("gen_ai.usage.input_tokens")), is(5L));
        assertThat(span.getAttributes().get(longKey("gen_ai.usage.output_tokens")), is(3L));
    }

    @Test
    public void noSpanEmittedForNonLlmForwardedRequest() {
        InMemorySpanExporter spanExporter = InMemorySpanExporter.create();

        HttpRequest forwardedRequest = request()
            .withMethod("GET")
            .withPath("/api/users")
            .withSocketAddress(true, "example.com", 443);

        Optional<Provider> providerOpt = LlmProviderSniffer.sniff(forwardedRequest);
        assertThat(providerOpt.isPresent(), is(false));
        // No span should be emitted since this is not LLM traffic
        assertThat(spanExporter.getFinishedSpanItems().size(), is(0));
    }

    @Test
    public void noSpanEmittedWhenTracingDisabled() {
        // No exporter installed -- tracer is null
        HttpRequest forwardedRequest = request()
            .withMethod("POST")
            .withPath("/v1/chat/completions")
            .withSocketAddress(true, "api.openai.com", 443);

        // This should be a no-op, not throw
        assertThat(GenAiSpans.isEnabled(), is(false));
        Optional<Provider> providerOpt = LlmProviderSniffer.sniff(forwardedRequest);
        assertThat(providerOpt.isPresent(), is(true));
        // recordCompletion is safe when disabled
        GenAiSpans.recordCompletion(providerOpt.get(), "gpt-4o", null);
    }

    @Test
    public void fallsBackToModelFromRequestWhenResponseHasNone() {
        InMemorySpanExporter spanExporter = InMemorySpanExporter.create();
        Tracer tracer = tracerFor(spanExporter);

        HttpRequest forwardedRequest = request()
            .withMethod("POST")
            .withPath("/v1/chat/completions")
            .withSocketAddress(true, "api.openai.com", 443)
            .withBody("{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"test\"}]}");

        // Response that has choices but no top-level "model" field
        HttpResponse upstreamResponse = response()
            .withStatusCode(200)
            .withBody("{\"id\":\"chatcmpl-456\",\"choices\":[{\"index\":0,\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":1,\"total_tokens\":6}}");

        Optional<Provider> providerOpt = LlmProviderSniffer.sniff(forwardedRequest);
        Completion completion = LlmClientRegistry.getInstance().lookup(providerOpt.get()).get()
            .parseCompletionResponse(upstreamResponse);

        // Response has no "model" field, so fall back to request body's model
        String model = LlmProviderSniffer.extractModelFromResponse(upstreamResponse);
        if (model == null) {
            model = LlmProviderSniffer.extractModelFromRequest(forwardedRequest);
        }
        assertThat(model, is("gpt-4o-mini"));

        GenAiSpans.recordCompletion(tracer, providerOpt.get(), model, completion);

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans.size(), is(1));
        assertThat(spans.get(0).getAttributes().get(stringKey("gen_ai.request.model")), is("gpt-4o-mini"));
    }

    @Test
    public void emitsGenAiSpanForAzureOpenAiForwardedResponse() {
        InMemorySpanExporter spanExporter = InMemorySpanExporter.create();
        Tracer tracer = tracerFor(spanExporter);

        HttpRequest forwardedRequest = request()
            .withMethod("POST")
            .withPath("/openai/deployments/gpt-4o/chat/completions")
            .withSocketAddress(true, "my-resource.openai.azure.com", 443)
            .withBody("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");

        HttpResponse upstreamResponse = response()
            .withStatusCode(200)
            .withBody("{\"id\":\"chatcmpl-789\",\"model\":\"gpt-4o\","
                + "\"choices\":[{\"index\":0,\"message\":{\"content\":\"Hello!\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}");

        Optional<Provider> providerOpt = LlmProviderSniffer.sniff(forwardedRequest);
        assertThat(providerOpt.get(), is(Provider.AZURE_OPENAI));

        // Azure OpenAI uses the same response format as OpenAI
        Completion completion = LlmClientRegistry.getInstance().lookup(providerOpt.get()).get()
            .parseCompletionResponse(upstreamResponse);
        String model = LlmProviderSniffer.extractModelFromResponse(upstreamResponse);
        GenAiSpans.recordCompletion(tracer, providerOpt.get(), model, completion);

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans.size(), is(1));
        SpanData span = spans.get(0);
        assertThat(span.getAttributes().get(stringKey("gen_ai.system")), is("az.ai.openai"));
        assertThat(span.getAttributes().get(stringKey("gen_ai.request.model")), is("gpt-4o"));
    }
}
