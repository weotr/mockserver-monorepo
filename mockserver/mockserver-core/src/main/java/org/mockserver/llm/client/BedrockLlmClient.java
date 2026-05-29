package org.mockserver.llm.client;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.llm.ParsedConversation;
import org.mockserver.llm.ParsedMessage;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.Provider;

/**
 * Runtime client for Amazon Bedrock's Anthropic models
 * ({@code POST /model/{modelId}/invoke}). The request body and response shape
 * are Anthropic's (so response parsing is inherited from
 * {@link AnthropicLlmClient}), wrapped with the Bedrock
 * {@code anthropic_version} field.
 * <p>
 * <strong>Auth limitation:</strong> Bedrock requires AWS SigV4 request signing.
 * Automatic SigV4 signing is not yet implemented in this client, so callers must
 * supply auth out of band — e.g. via the {@link LlmBackend#headers()} escape
 * hatch carrying a pre-signed {@code Authorization} header, or by pointing
 * {@code baseUrl} at a local signing proxy / sidecar that signs and forwards.
 * Without valid auth the request fails closed (no completion), like any other
 * backend error. Tracked alongside the Bedrock codec limitations in
 * {@code docs/code/llm-security-audit.md}.
 */
public class BedrockLlmClient extends AnthropicLlmClient {

    static final String BEDROCK_ANTHROPIC_VERSION = "bedrock-2023-05-31";
    static final String DEFAULT_MODEL = "anthropic.claude-3-5-sonnet-20241022-v2:0";
    private static final int DEFAULT_MAX_TOKENS = 1024;

    @Override
    public Provider provider() {
        return Provider.BEDROCK;
    }

    @Override
    public HttpRequest buildCompletionRequest(LlmBackend backend, ParsedConversation prompt) {
        // baseUrl is the regional endpoint, e.g. https://bedrock-runtime.us-east-1.amazonaws.com
        String baseUrl = resolveBaseUrl(backend, "https://bedrock-runtime.us-east-1.amazonaws.com");
        String modelId = resolveModel(backend, DEFAULT_MODEL);

        ObjectNode body = OBJECT_MAPPER.createObjectNode();
        body.put("anthropic_version", BEDROCK_ANTHROPIC_VERSION);
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

        // Auth headers (if any) come from the backend.headers escape hatch — see class Javadoc.
        return postJson(backend, baseUrl, "/model/" + modelId + "/invoke", writeJson(body));
    }
}
