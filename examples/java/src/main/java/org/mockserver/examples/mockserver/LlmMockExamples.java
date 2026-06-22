package org.mockserver.examples.mockserver;

import org.mockserver.client.MockServerClient;
import org.mockserver.model.Provider;

import static org.mockserver.client.LlmMockBuilder.llmMock;
import static org.mockserver.model.Completion.completion;
import static org.mockserver.model.EmbeddingResponse.embedding;
import static org.mockserver.model.Usage.usage;

/**
 * Examples of mocking Large Language Model (LLM) provider responses.
 * <p>
 * MockServer can stand in for an LLM provider's HTTP API (OpenAI, Anthropic,
 * etc.) so application and agent code can be tested deterministically, without
 * calling a real model. Use {@link org.mockserver.client.LlmMockBuilder#llmMock}
 * to register a mock keyed on the provider's request path, choose the
 * {@link Provider} and model, then describe the response with either a chat
 * {@code completion()} or an {@code embedding()}.
 *
 * @author jamesdbloom
 */
public class LlmMockExamples {

    public void mockOpenAiChatCompletion() {
        new MockServerClient("localhost", 1080)
            // mock an OpenAI-style chat completion at the /v1/chat/completions path
            .upsert(
                llmMock("/v1/chat/completions")
                    .withProvider(Provider.OPENAI)
                    .withModel("gpt-4o")
                    .respondingWith(
                        completion()
                            .withText("The capital of France is Paris.")
                            .withStopReason("stop")
                            .withUsage(
                                usage()
                                    .withInputTokens(42)
                                    .withOutputTokens(8)
                            )
                    )
                    .build()
            );
    }

    public void mockOpenAiEmbeddingsResponse() {
        new MockServerClient("localhost", 1080)
            // mock an OpenAI-style embeddings response at the /v1/embeddings path,
            // returning a 1536-dimension vector derived deterministically from the input
            .upsert(
                llmMock("/v1/embeddings")
                    .withProvider(Provider.OPENAI)
                    .withModel("text-embedding-3-small")
                    .respondingWith(
                        embedding()
                            .withDimensions(1536)
                            .withDeterministicFromInput(true)
                    )
                    .build()
            );
    }
}
