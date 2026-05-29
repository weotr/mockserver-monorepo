package org.mockserver.llm.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.llm.ParsedConversation;
import org.mockserver.model.Completion;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Provider;
import org.mockserver.model.Usage;

/**
 * Runtime client for the OpenAI Chat Completions API
 * ({@code POST /v1/chat/completions}, {@code Authorization: Bearer}).
 */
public class OpenAiLlmClient extends AbstractLlmClient {

    static final String DEFAULT_BASE_URL = "https://api.openai.com";
    static final String DEFAULT_MODEL = "gpt-4o-mini";

    @Override
    public Provider provider() {
        return Provider.OPENAI;
    }

    @Override
    public HttpRequest buildCompletionRequest(LlmBackend backend, ParsedConversation prompt) {
        String baseUrl = resolveBaseUrl(backend, DEFAULT_BASE_URL);
        ObjectNode body = OBJECT_MAPPER.createObjectNode();
        body.put("model", resolveModel(backend, DEFAULT_MODEL));
        body.put("temperature", 0);
        body.put("seed", 0);
        ArrayNode messages = body.putArray("messages");
        appendRoleContentMessages(messages, prompt, "system", "user", "assistant", "tool");
        HttpRequest request = postJson(backend, baseUrl, "/v1/chat/completions", writeJson(body));
        if (backend.hasApiKey()) {
            request.withHeader("Authorization", "Bearer " + backend.apiKey());
        }
        return request;
    }

    @Override
    public Completion parseCompletionResponse(HttpResponse response) {
        JsonNode root = readBody(response);
        Completion completion = Completion.completion();
        JsonNode choice = root.path("choices").path(0);
        JsonNode message = choice.path("message");
        if (message.hasNonNull("content")) {
            completion.withText(message.path("content").asText());
        }
        if (choice.hasNonNull("finish_reason")) {
            completion.withStopReason(choice.path("finish_reason").asText());
        }
        JsonNode usageNode = root.path("usage");
        if (usageNode.isObject()) {
            Usage usage = Usage.usage();
            if (usageNode.has("prompt_tokens")) {
                usage.withInputTokens(usageNode.path("prompt_tokens").asInt());
            }
            if (usageNode.has("completion_tokens")) {
                usage.withOutputTokens(usageNode.path("completion_tokens").asInt());
            }
            completion.withUsage(usage);
        }
        return completion;
    }
}
