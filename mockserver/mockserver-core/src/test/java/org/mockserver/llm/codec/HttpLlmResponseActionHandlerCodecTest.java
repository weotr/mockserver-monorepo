package org.mockserver.llm.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.action.http.HttpLlmResponseActionHandler;
import org.mockserver.model.*;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.Completion.completion;
import static org.mockserver.model.HttpLlmResponse.llmResponse;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.ToolUse.toolUse;

public class HttpLlmResponseActionHandlerCodecTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    public void shouldReturn200ForAnthropicWithCodecRegistered() throws Exception {
        // given
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withModel("claude-sonnet-4-20250514")
            .withCompletion(completion().withText("Hello from codec"));
        HttpRequest request = request().withPath("/v1/messages");

        // when
        HttpResponse response = handler.handle(llmResponse, request);

        // then — should be 200, not 501
        assertThat(response.getStatusCode(), is(200));
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        assertThat(root.get("type").asText(), is("message"));
    }

    @Test
    public void shouldReturn200ForOpenAiWithCodecRegistered() throws Exception {
        // given
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(Provider.OPENAI)
            .withModel("gpt-4o")
            .withCompletion(completion().withText("Hello from codec"));
        HttpRequest request = request().withPath("/v1/chat/completions");

        // when
        HttpResponse response = handler.handle(llmResponse, request);

        // then — should be 200, not 501
        assertThat(response.getStatusCode(), is(200));
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        assertThat(root.get("object").asText(), is("chat.completion"));
    }

    @Test
    public void shouldReturn200ForEveryRegisteredProvider() throws Exception {
        // After M4 every Provider enum value has a registered codec; the codec-missing
        // 400 path remains in the handler for safety but is not reachable through any
        // current production code path. This positive test pins the post-M4 contract:
        // every Provider value resolves to a codec and returns 200.
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        HttpRequest request = request().withPath("/test");

        for (Provider provider : Provider.values()) {
            HttpLlmResponse llmResponse = llmResponse()
                .withProvider(provider)
                .withCompletion(completion().withText("hello"));

            HttpResponse response = handler.handle(llmResponse, request);

            assertThat("expected 200 for provider " + provider,
                response.getStatusCode(), is(200));
        }
    }

    @Test
    public void shouldReturn400ForNullProvider() throws Exception {
        // given
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        HttpLlmResponse llmResponse = llmResponse()
            .withCompletion(completion().withText("test"));
        HttpRequest request = request().withPath("/test");

        // when
        HttpResponse response = handler.handle(llmResponse, request);

        // then
        assertThat(response.getStatusCode(), is(400));
        assertThat(response.getBodyAsString(), containsString("unsupported LLM provider: null"));
    }

    @Test
    public void shouldHandleStreamingFlagByRouting() throws Exception {
        // given
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withModel("claude-sonnet-4-20250514")
            .withCompletion(completion()
                .withText("streaming test")
                .withStreaming(true));
        HttpRequest request = request().withPath("/v1/messages");

        // when — handleStreaming returns SSE events
        List<SseEvent> events = handler.handleStreaming(llmResponse, request);

        // then
        assertThat(events, is(notNullValue()));
        assertThat(events.size(), is(greaterThan(0)));
        assertThat(events.get(0).getEvent(), is("message_start"));
    }

    @Test
    public void shouldHandleOpenAiStreamingRouting() throws Exception {
        // given
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(Provider.OPENAI)
            .withModel("gpt-4o")
            .withCompletion(completion()
                .withText("streaming test")
                .withStreaming(true));
        HttpRequest request = request().withPath("/v1/chat/completions");

        // when
        List<SseEvent> events = handler.handleStreaming(llmResponse, request);

        // then
        assertThat(events, is(notNullValue()));
        assertThat(events.size(), is(greaterThan(0)));
        // Last event should be [DONE]
        assertThat(events.get(events.size() - 1).getData(), is("[DONE]"));
    }

    @Test
    public void shouldHandleEmbeddingPath() throws Exception {
        // given
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(Provider.OPENAI)
            .withEmbedding(EmbeddingResponse.embedding()
                .withDimensions(8)
                .withDeterministicFromInput(true));
        HttpRequest request = request()
            .withPath("/v1/embeddings")
            .withBody("{\"input\":\"test text\",\"model\":\"text-embedding-3-small\"}");

        // when
        HttpResponse response = handler.handle(llmResponse, request);

        // then
        assertThat(response.getStatusCode(), is(200));
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        assertThat(root.get("object").asText(), is("list"));
        assertThat(root.get("data").get(0).get("embedding").size(), is(8));
    }

    @Test
    public void shouldReturn400ForAnthropicEmbedding() throws Exception {
        // given — Anthropic doesn't support embeddings
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withEmbedding(EmbeddingResponse.embedding().withDimensions(8));
        HttpRequest request = request().withPath("/v1/embeddings");

        // when
        HttpResponse response = handler.handle(llmResponse, request);

        // then
        assertThat(response.getStatusCode(), is(400));
        assertThat(response.getBodyAsString(), containsString("Anthropic does not expose an embeddings endpoint"));
    }
}
