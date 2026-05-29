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
 * Runtime client for a local <a href="https://ollama.com">Ollama</a> server.
 * No auth, runs locally, free — the ideal first backend for proving the
 * end-to-end path without cloud keys, token cost, or network flakiness.
 * <p>
 * Uses the {@code /api/chat} endpoint with {@code stream:false} and pins
 * {@code temperature=0} + {@code seed=0} for reproducibility.
 */
public class OllamaLlmClient extends AbstractLlmClient {

    static final String DEFAULT_BASE_URL = "http://localhost:11434";
    static final String DEFAULT_MODEL = "llama3";

    @Override
    public Provider provider() {
        return Provider.OLLAMA;
    }

    @Override
    public HttpRequest buildCompletionRequest(LlmBackend backend, ParsedConversation prompt) {
        String baseUrl = resolveBaseUrl(backend, DEFAULT_BASE_URL);
        ObjectNode body = OBJECT_MAPPER.createObjectNode();
        body.put("model", resolveModel(backend, DEFAULT_MODEL));
        body.put("stream", false);
        ArrayNode messages = body.putArray("messages");
        appendRoleContentMessages(messages, prompt, "system", "user", "assistant", "tool");
        ObjectNode options = body.putObject("options");
        options.put("temperature", 0);
        options.put("seed", 0);
        return postJson(backend, baseUrl, "/api/chat", writeJson(body));
    }

    @Override
    public Completion parseCompletionResponse(HttpResponse response) {
        JsonNode root = readBody(response);
        Completion completion = Completion.completion();
        JsonNode message = root.path("message");
        if (message.hasNonNull("content")) {
            completion.withText(message.path("content").asText());
        }
        if (root.hasNonNull("done_reason")) {
            completion.withStopReason(root.path("done_reason").asText());
        }
        if (root.has("prompt_eval_count") || root.has("eval_count")) {
            Usage usage = Usage.usage();
            if (root.has("prompt_eval_count")) {
                usage.withInputTokens(root.path("prompt_eval_count").asInt());
            }
            if (root.has("eval_count")) {
                usage.withOutputTokens(root.path("eval_count").asInt());
            }
            completion.withUsage(usage);
        }
        return completion;
    }
}
