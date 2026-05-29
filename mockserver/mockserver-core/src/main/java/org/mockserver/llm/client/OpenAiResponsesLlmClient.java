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
 * Runtime client for the OpenAI Responses API ({@code POST /v1/responses},
 * {@code Authorization: Bearer}). Sends the conversation as the {@code input}
 * message array and parses the {@code output} content (preferring the
 * {@code output_text} convenience field when present).
 */
public class OpenAiResponsesLlmClient extends AbstractLlmClient {

    static final String DEFAULT_BASE_URL = "https://api.openai.com";
    static final String DEFAULT_MODEL = "gpt-4o-mini";

    @Override
    public Provider provider() {
        return Provider.OPENAI_RESPONSES;
    }

    @Override
    public HttpRequest buildCompletionRequest(LlmBackend backend, ParsedConversation prompt) {
        String baseUrl = resolveBaseUrl(backend, DEFAULT_BASE_URL);
        ObjectNode body = OBJECT_MAPPER.createObjectNode();
        body.put("model", resolveModel(backend, DEFAULT_MODEL));
        body.put("temperature", 0);
        ArrayNode input = body.putArray("input");
        appendRoleContentMessages(input, prompt, "system", "user", "assistant", "tool");
        HttpRequest request = postJson(backend, baseUrl, "/v1/responses", writeJson(body));
        if (backend.hasApiKey()) {
            request.withHeader("Authorization", "Bearer " + backend.apiKey());
        }
        return request;
    }

    @Override
    public Completion parseCompletionResponse(HttpResponse response) {
        JsonNode root = readBody(response);
        Completion completion = Completion.completion();
        if (root.hasNonNull("output_text")) {
            completion.withText(root.path("output_text").asText());
        } else {
            StringBuilder text = new StringBuilder();
            for (JsonNode item : root.path("output")) {
                for (JsonNode content : item.path("content")) {
                    if ("output_text".equals(content.path("type").asText()) && content.hasNonNull("text")) {
                        text.append(content.path("text").asText());
                    }
                }
            }
            if (text.length() > 0) {
                completion.withText(text.toString());
            }
        }
        if (root.hasNonNull("status")) {
            completion.withStopReason(root.path("status").asText());
        }
        JsonNode usageNode = root.path("usage");
        if (usageNode.isObject()) {
            Usage usage = Usage.usage();
            if (usageNode.has("input_tokens")) {
                usage.withInputTokens(usageNode.path("input_tokens").asInt());
            }
            if (usageNode.has("output_tokens")) {
                usage.withOutputTokens(usageNode.path("output_tokens").asInt());
            }
            completion.withUsage(usage);
        }
        return completion;
    }
}
