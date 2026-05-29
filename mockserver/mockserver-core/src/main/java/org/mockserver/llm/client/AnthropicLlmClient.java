package org.mockserver.llm.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.llm.ParsedConversation;
import org.mockserver.llm.ParsedMessage;
import org.mockserver.model.Completion;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Provider;
import org.mockserver.model.Usage;

/**
 * Runtime client for the Anthropic Messages API ({@code POST /v1/messages},
 * {@code x-api-key} + {@code anthropic-version}). System messages are hoisted to
 * the top-level {@code system} parameter as Anthropic requires.
 */
public class AnthropicLlmClient extends AbstractLlmClient {

    static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    static final String DEFAULT_MODEL = "claude-3-5-sonnet-20241022";
    static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int DEFAULT_MAX_TOKENS = 1024;

    @Override
    public Provider provider() {
        return Provider.ANTHROPIC;
    }

    @Override
    public HttpRequest buildCompletionRequest(LlmBackend backend, ParsedConversation prompt) {
        String baseUrl = resolveBaseUrl(backend, DEFAULT_BASE_URL);
        ObjectNode body = OBJECT_MAPPER.createObjectNode();
        body.put("model", resolveModel(backend, DEFAULT_MODEL));
        body.put("max_tokens", DEFAULT_MAX_TOKENS);
        body.put("temperature", 0);

        StringBuilder system = new StringBuilder();
        ArrayNode messages = body.putArray("messages");
        for (ParsedMessage message : prompt.getMessages()) {
            String text = message.getTextContent();
            if (text == null || text.isEmpty()) {
                continue;
            }
            if (message.getRole() == ParsedMessage.Role.SYSTEM) {
                if (system.length() > 0) {
                    system.append("\n");
                }
                system.append(text);
            } else {
                ObjectNode messageNode = messages.addObject();
                messageNode.put("role", message.getRole() == ParsedMessage.Role.ASSISTANT ? "assistant" : "user");
                messageNode.put("content", text);
            }
        }
        if (system.length() > 0) {
            body.put("system", system.toString());
        }

        HttpRequest request = postJson(backend, baseUrl, "/v1/messages", writeJson(body));
        request.withHeader("anthropic-version", ANTHROPIC_VERSION);
        if (backend.hasApiKey()) {
            request.withHeader("x-api-key", backend.apiKey());
        }
        return request;
    }

    @Override
    public Completion parseCompletionResponse(HttpResponse response) {
        JsonNode root = readBody(response);
        Completion completion = Completion.completion();
        StringBuilder text = new StringBuilder();
        for (JsonNode block : root.path("content")) {
            if ("text".equals(block.path("type").asText()) && block.hasNonNull("text")) {
                text.append(block.path("text").asText());
            }
        }
        if (text.length() > 0) {
            completion.withText(text.toString());
        }
        if (root.hasNonNull("stop_reason")) {
            completion.withStopReason(root.path("stop_reason").asText());
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
