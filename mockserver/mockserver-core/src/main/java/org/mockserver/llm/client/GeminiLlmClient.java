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
 * Runtime client for the Google Gemini {@code generateContent} API. The API key
 * is passed as a {@code ?key=} query parameter; assistant turns use the
 * {@code model} role and system text is hoisted to {@code systemInstruction}.
 * <p>
 * Security note: Gemini's API-key auth mandates the key in the query string, so
 * unlike header-based credentials it can appear in HTTP access/proxy logs. This
 * is a property of the provider's API design, not a MockServer choice; in
 * high-security environments front the call with a gateway that injects the key
 * after ingress.
 */
public class GeminiLlmClient extends AbstractLlmClient {

    static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";
    static final String DEFAULT_MODEL = "gemini-1.5-flash";

    @Override
    public Provider provider() {
        return Provider.GEMINI;
    }

    @Override
    public HttpRequest buildCompletionRequest(LlmBackend backend, ParsedConversation prompt) {
        String baseUrl = resolveBaseUrl(backend, DEFAULT_BASE_URL);
        String model = resolveModel(backend, DEFAULT_MODEL);
        ObjectNode body = OBJECT_MAPPER.createObjectNode();

        StringBuilder system = new StringBuilder();
        ArrayNode contents = body.putArray("contents");
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
                ObjectNode content = contents.addObject();
                content.put("role", message.getRole() == ParsedMessage.Role.ASSISTANT ? "model" : "user");
                content.putArray("parts").addObject().put("text", text);
            }
        }
        if (system.length() > 0) {
            body.putObject("systemInstruction").putArray("parts").addObject().put("text", system.toString());
        }
        body.putObject("generationConfig").put("temperature", 0);

        HttpRequest request = postJson(backend, baseUrl, "/v1beta/models/" + model + ":generateContent", writeJson(body));
        if (backend.hasApiKey()) {
            request.withQueryStringParameter("key", backend.apiKey());
        }
        return request;
    }

    @Override
    public Completion parseCompletionResponse(HttpResponse response) {
        JsonNode root = readBody(response);
        Completion completion = Completion.completion();
        JsonNode candidate = root.path("candidates").path(0);
        StringBuilder text = new StringBuilder();
        for (JsonNode part : candidate.path("content").path("parts")) {
            if (part.hasNonNull("text")) {
                text.append(part.path("text").asText());
            }
        }
        if (text.length() > 0) {
            completion.withText(text.toString());
        }
        if (candidate.hasNonNull("finishReason")) {
            completion.withStopReason(candidate.path("finishReason").asText());
        }
        JsonNode usageNode = root.path("usageMetadata");
        if (usageNode.isObject()) {
            Usage usage = Usage.usage();
            if (usageNode.has("promptTokenCount")) {
                usage.withInputTokens(usageNode.path("promptTokenCount").asInt());
            }
            if (usageNode.has("candidatesTokenCount")) {
                usage.withOutputTokens(usageNode.path("candidatesTokenCount").asInt());
            }
            completion.withUsage(usage);
        }
        return completion;
    }
}
