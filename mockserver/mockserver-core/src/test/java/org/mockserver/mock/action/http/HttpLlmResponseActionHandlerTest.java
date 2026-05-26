package org.mockserver.mock.action.http;

import org.junit.After;
import org.junit.Test;
import org.mockserver.llm.ParsedConversation;
import org.mockserver.llm.ProviderCodec;
import org.mockserver.llm.ProviderCodecRegistry;
import org.mockserver.llm.codec.AnthropicCodec;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.*;
import org.slf4j.event.Level;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.core.Is.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockserver.model.Completion.completion;
import static org.mockserver.model.HttpLlmResponse.llmResponse;
import static org.mockserver.model.HttpRequest.request;

public class HttpLlmResponseActionHandlerTest {

    @After
    public void restoreAnthropicCodec() {
        // Ensure the real AnthropicCodec is always restored after tests that
        // substitute a throwing codec, so other tests in the same JVM are not
        // affected.
        ProviderCodecRegistry.getInstance().register(new AnthropicCodec());
    }

    @Test
    public void shouldReturn200ForRegisteredCodec() {
        // given — ANTHROPIC codec is registered
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withModel("claude-sonnet-4-20250514")
            .withCompletion(completion().withText("Hello"));
        HttpRequest request = request().withPath("/v1/messages");

        // when
        HttpResponse response = handler.handle(llmResponse, request);

        // then — registered codecs return 200
        assertThat(response.getStatusCode(), is(200));
    }

    @Test
    public void shouldReturn200ForAllRegisteredProviders() {
        // After M4 every Provider enum value has a registered codec, so the
        // handler must return 200 for all of them. The "unregistered provider"
        // safety-net path is still reachable in code (e.g., during a future
        // milestone that adds a new Provider value without a codec) but cannot
        // be exercised from production paths today.
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        HttpRequest request = request().withPath("/test");

        for (Provider provider : Provider.values()) {
            HttpLlmResponse llmResponse = llmResponse()
                .withProvider(provider)
                .withCompletion(completion().withText("test"));

            HttpResponse response = handler.handle(llmResponse, request);

            assertThat("expected 200 for registered provider " + provider,
                response.getStatusCode(), is(200));
        }
    }

    @Test
    public void shouldReturn400WhenProviderIsNull() {
        // given
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        HttpLlmResponse llmResponse = llmResponse()
            .withCompletion(completion().withText("Hello"));
        HttpRequest request = request().withPath("/test");

        // when
        HttpResponse response = handler.handle(llmResponse, request);

        // then
        assertThat(response.getStatusCode(), is(400));
        assertThat(response.getBodyAsString(), containsString("null"));
    }

    // --- Gap 1: Codec internal failure -> 502 ---

    @Test
    public void shouldReturn502WhenCodecEncodeThrowsRuntimeException() {
        // given — register a deliberately-failing codec for ANTHROPIC
        ProviderCodec throwingCodec = new ProviderCodec() {
            @Override
            public Provider provider() {
                return Provider.ANTHROPIC;
            }

            @Override
            public String apiVersion() {
                return "test";
            }

            @Override
            public HttpResponse encode(Completion completion, String model) {
                throw new RuntimeException("boom");
            }

            @Override
            public ParsedConversation decode(HttpRequest request) {
                return ParsedConversation.empty();
            }
        };
        ProviderCodecRegistry.getInstance().register(throwingCodec);

        MockServerLogger mockLogger = mock(MockServerLogger.class);
        when(mockLogger.isEnabledForInstance(any(Level.class))).thenReturn(true);

        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(mockLogger);
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withModel("claude-sonnet-4-20250514")
            .withCompletion(completion().withText("Hello"));
        HttpRequest request = request().withPath("/v1/messages");

        // when
        HttpResponse response = handler.handle(llmResponse, request);

        // then — 502 with correct error body
        assertThat(response.getStatusCode(), is(502));
        assertThat(response.getBodyAsString(), containsString("\"error\":\"llm codec encode failed\""));
        assertThat(response.getBodyAsString(), containsString("\"provider\":\"ANTHROPIC\""));
        // Provider name is escaped via Provider.name(), not the exception message
        assertThat("exception message must not leak into the body",
            response.getBodyAsString().contains("boom"), is(false));

        // Verify WARN log was recorded
        verify(mockLogger).logEvent(argThat(logEntry ->
            logEntry.getLogLevel() == Level.WARN
                && logEntry.getMessageFormat().contains("llm codec encode failed")
        ));
    }

    @Test
    public void shouldReturn502WhenCodecEncodeEmbeddingThrowsRuntimeException() {
        // given — register a codec that throws from encodeEmbedding
        ProviderCodec throwingCodec = new ProviderCodec() {
            @Override
            public Provider provider() {
                return Provider.ANTHROPIC;
            }

            @Override
            public String apiVersion() {
                return "test";
            }

            @Override
            public HttpResponse encodeEmbedding(EmbeddingResponse embedding, String input) {
                throw new RuntimeException("embedding boom");
            }

            @Override
            public ParsedConversation decode(HttpRequest request) {
                return ParsedConversation.empty();
            }
        };
        ProviderCodecRegistry.getInstance().register(throwingCodec);

        MockServerLogger mockLogger = mock(MockServerLogger.class);
        when(mockLogger.isEnabledForInstance(any(Level.class))).thenReturn(true);

        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(mockLogger);
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withEmbedding(EmbeddingResponse.embedding().withDimensions(8));
        HttpRequest request = request().withPath("/v1/embeddings");

        // when
        HttpResponse response = handler.handle(llmResponse, request);

        // then — 502 with correct error body
        assertThat(response.getStatusCode(), is(502));
        assertThat(response.getBodyAsString(), containsString("\"error\":\"llm codec encode failed\""));
        assertThat(response.getBodyAsString(), containsString("\"provider\":\"ANTHROPIC\""));
        assertThat("exception message must not leak into the body",
            response.getBodyAsString().contains("embedding boom"), is(false));
    }

    @Test
    public void shouldReturn400WhenNoCompletionOrEmbeddingConfigured() {
        // given
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(Provider.ANTHROPIC);
        HttpRequest request = request().withPath("/test");

        // when
        HttpResponse response = handler.handle(llmResponse, request);

        // then
        assertThat(response.getStatusCode(), is(400));
        assertThat(response.getBodyAsString(), containsString("must have either a completion or embedding configured"));
    }

    @Test
    public void shouldReturn501WhenStreamingReachesNonStreamingPath() {
        // given — streaming completion sent to handle() (not handleStreaming())
        MockServerLogger mockLogger = mock(MockServerLogger.class);
        when(mockLogger.isEnabledForInstance(any(Level.class))).thenReturn(true);

        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(mockLogger);
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withCompletion(completion().withText("stream").withStreaming(true));
        HttpRequest request = request().withPath("/v1/messages");

        // when
        HttpResponse response = handler.handle(llmResponse, request);

        // then
        assertThat(response.getStatusCode(), is(501));
        assertThat(response.getBodyAsString(), containsString("streaming LLM responses must be dispatched through the SSE handler"));
    }
}
