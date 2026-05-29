package org.mockserver.llm.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.llm.ParsedConversation;
import org.mockserver.llm.ParsedMessage;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.serialization.ObjectMapperFactory;

import java.net.URI;
import java.util.Map;

import static org.mockserver.model.HttpRequest.request;

/**
 * Shared scaffolding for {@link LlmClient} implementations: URL parsing,
 * base-request construction (method, path, host, scheme, custom headers) and a
 * shared {@link ObjectMapper}. Subclasses add provider-specific auth, request
 * body, and response parsing.
 */
public abstract class AbstractLlmClient implements LlmClient {

    protected static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createObjectMapper();

    /**
     * Resolve the effective base URL: the backend's value if set, else the
     * provider default.
     */
    protected String resolveBaseUrl(LlmBackend backend, String defaultBaseUrl) {
        return backend.baseUrl() != null && !backend.baseUrl().isEmpty() ? backend.baseUrl() : defaultBaseUrl;
    }

    /**
     * Resolve the effective model: the backend's value if set, else the
     * provider default.
     */
    protected String resolveModel(LlmBackend backend, String defaultModel) {
        return backend.model() != null && !backend.model().isEmpty() ? backend.model() : defaultModel;
    }

    /**
     * Build a POST request to {@code baseUrl + path}, parsing the URL into host,
     * port, and scheme so the transport can route it. Applies a JSON
     * content-type and any caller-supplied {@code headers} escape hatch.
     */
    protected HttpRequest postJson(LlmBackend backend, String baseUrl, String path, String jsonBody) {
        URI uri = URI.create(baseUrl);
        String host = uri.getHost();
        boolean secure = "https".equalsIgnoreCase(uri.getScheme());
        int port = uri.getPort() != -1 ? uri.getPort() : (secure ? 443 : 80);
        // Combine the base URL's path with the endpoint path.
        String basePath = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (basePath.endsWith("/") && path.startsWith("/")) {
            basePath = basePath.substring(0, basePath.length() - 1);
        }
        String fullPath = basePath + path;

        HttpRequest httpRequest = request()
            .withMethod("POST")
            .withPath(fullPath)
            .withSecure(secure)
            .withSocketAddress(secure, host, port)
            .withHeader("Host", port == (secure ? 443 : 80) ? host : host + ":" + port)
            .withHeader("Content-Type", "application/json")
            .withBody(jsonBody);
        for (Map.Entry<String, String> header : backend.headers().entrySet()) {
            httpRequest.withHeader(header.getKey(), header.getValue());
        }
        return httpRequest;
    }

    /**
     * Parse a response body into a JSON tree. Throws on a malformed body; the
     * caller ({@link LlmCompletionService}) treats that fail-closed.
     */
    protected JsonNode readBody(HttpResponse response) {
        try {
            return OBJECT_MAPPER.readTree(response.getBodyAsString());
        } catch (Exception e) {
            throw new IllegalStateException("failed to parse " + provider() + " response body", e);
        }
    }

    /**
     * Serialize a JSON node to a string, wrapping the checked exception as
     * unchecked (the node is always well-formed here).
     */
    protected String writeJson(JsonNode node) {
        try {
            return OBJECT_MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialise LLM request body", e);
        }
    }

    /**
     * Append the conversation's messages to {@code messagesArray} as
     * {@code {role, content}} objects using the given role names. Messages with
     * no text content are skipped (the runtime client carries prompt text;
     * outbound tool-call round-tripping is out of scope for this SPI).
     */
    protected void appendRoleContentMessages(ArrayNode messagesArray, ParsedConversation prompt,
                                             String systemRole, String userRole,
                                             String assistantRole, String toolRole) {
        for (ParsedMessage message : prompt.getMessages()) {
            String text = message.getTextContent();
            if (text == null || text.isEmpty()) {
                continue;
            }
            ObjectNode messageNode = messagesArray.addObject();
            messageNode.put("role", roleName(message.getRole(), systemRole, userRole, assistantRole, toolRole));
            messageNode.put("content", text);
        }
    }

    private String roleName(ParsedMessage.Role role, String systemRole, String userRole,
                            String assistantRole, String toolRole) {
        switch (role) {
            case SYSTEM:
                return systemRole;
            case ASSISTANT:
                return assistantRole;
            case TOOL:
                return toolRole;
            case USER:
            default:
                return userRole;
        }
    }
}
