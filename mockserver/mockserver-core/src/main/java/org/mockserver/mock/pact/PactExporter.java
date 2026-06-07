package org.mockserver.mock.pact;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.mock.Expectation;
import org.mockserver.model.Header;
import org.mockserver.model.Headers;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.NottableString;
import org.mockserver.model.Parameter;
import org.mockserver.model.Parameters;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.List;
import java.util.regex.Pattern;

import static org.apache.commons.lang3.StringUtils.isBlank;

/**
 * Exports MockServer response expectations as a
 * <a href="https://docs.pact.io/">Pact</a> v3 consumer contract (JSON), so traffic mocked or
 * recorded in MockServer can be published to a Pact Broker / PactFlow and used for
 * consumer-driven contract testing.
 *
 * <p>Only expectations with an {@link HttpResponse} action and a concrete {@link HttpRequest}
 * matcher are exported — forward, callback, and template actions have no Pact equivalent and are
 * skipped. Matcher values are emitted as concrete example values (Pact's default exact matching);
 * MockServer matching rules (regex, JSON-schema, XPath, …) are not yet translated into Pact
 * matchingRules and a body that is not a literal value is exported as its string form.
 */
public class PactExporter {

    private static final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
    private static final Pattern UUID_PATTERN =
        Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /**
     * @param expectations the expectations to export (typically the active expectations)
     * @param consumer     the consumer name (defaults to "consumer" when blank)
     * @param provider     the provider name (defaults to "provider" when blank)
     * @return the Pact contract as pretty-printed JSON
     */
    public String export(List<Expectation> expectations, String consumer, String provider) {
        final ObjectNode root = objectMapper.createObjectNode();
        root.putObject("consumer").put("name", blankToDefault(consumer, "consumer"));
        root.putObject("provider").put("name", blankToDefault(provider, "provider"));

        final ArrayNode interactions = root.putArray("interactions");
        for (final Expectation expectation : expectations) {
            if (!(expectation.getHttpRequest() instanceof HttpRequest)) {
                continue;
            }
            final HttpRequest request = (HttpRequest) expectation.getHttpRequest();
            // a notted method/path matcher (match anything *except*) has no positive Pact equivalent
            if (isNotted(request.getMethod()) || isNotted(request.getPath())) {
                continue;
            }
            final HttpResponse response = representativeResponse(expectation);
            if (response == null) {
                // only response actions (single or sequence) have a Pact equivalent
                continue;
            }
            final ObjectNode interaction = interactions.addObject();
            interaction.put("description", describe(expectation, request));
            interaction.set("request", buildRequest(request));
            interaction.set("response", buildResponse(response));
        }

        root.putObject("metadata").putObject("pactSpecification").put("version", "3.0.0");

        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize Pact contract", e);
        }
    }

    private ObjectNode buildRequest(HttpRequest request) {
        final ObjectNode node = objectMapper.createObjectNode();
        node.put("method", request.getMethod() != null ? request.getMethod().getValue() : "GET");
        node.put("path", request.getPath() != null ? request.getPath().getValue() : "/");
        final ObjectNode query = queryToNode(request.getQueryStringParameters());
        if (query != null) {
            node.set("query", query);
        }
        final ObjectNode headers = headersToNode(request.getHeaders());
        if (headers != null) {
            node.set("headers", headers);
        }
        if (request.getBody() != null) {
            setBody(node, request.getBodyAsString());
        }
        return node;
    }

    private ObjectNode buildResponse(HttpResponse response) {
        final ObjectNode node = objectMapper.createObjectNode();
        node.put("status", response.getStatusCode() != null ? response.getStatusCode() : 200);
        final ObjectNode headers = headersToNode(response.getHeaders());
        if (headers != null) {
            node.set("headers", headers);
        }
        if (response.getBody() != null) {
            setBody(node, response.getBodyAsString());
        }
        return node;
    }

    /**
     * Sets the "body" field, parsing JSON bodies into a JSON node so the Pact contract carries
     * structured content rather than an escaped string; falls back to the raw string otherwise.
     */
    private void setBody(ObjectNode node, String body) {
        if (isBlank(body)) {
            return;
        }
        try {
            final JsonNode parsed = objectMapper.readTree(body);
            node.set("body", parsed);
        } catch (JsonProcessingException notJson) {
            node.put("body", body);
        }
    }

    private ObjectNode headersToNode(Headers headers) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        final ObjectNode node = objectMapper.createObjectNode();
        for (final Header header : headers.getEntries()) {
            if (isNotted(header.getName())) {
                continue; // a notted header-name matcher has no positive Pact equivalent
            }
            final ArrayNode values = node.putArray(header.getName().getValue());
            for (final NottableString value : header.getValues()) {
                if (!isNotted(value)) {
                    values.add(value.getValue());
                }
            }
        }
        return node;
    }

    private ObjectNode queryToNode(Parameters parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return null;
        }
        final ObjectNode node = objectMapper.createObjectNode();
        for (final Parameter parameter : parameters.getEntries()) {
            if (isNotted(parameter.getName())) {
                continue; // a notted query-parameter-name matcher has no positive Pact equivalent
            }
            final ArrayNode values = node.putArray(parameter.getName().getValue());
            for (final NottableString value : parameter.getValues()) {
                if (!isNotted(value)) {
                    values.add(value.getValue());
                }
            }
        }
        return node;
    }

    /** Returns the single response, or the first of a response sequence, or null for non-response actions. */
    private static HttpResponse representativeResponse(Expectation expectation) {
        if (expectation.getHttpResponse() != null) {
            return expectation.getHttpResponse();
        }
        final List<HttpResponse> responses = expectation.getHttpResponses();
        return (responses != null && !responses.isEmpty()) ? responses.get(0) : null;
    }

    /**
     * Builds a human-readable interaction description. Auto-generated UUID expectation ids make poor
     * Pact interaction labels, so falls back to "METHOD path" unless a meaningful id was set.
     */
    private static String describe(Expectation expectation, HttpRequest request) {
        final String id = expectation.getId();
        if (id != null && !UUID_PATTERN.matcher(id).matches()) {
            return id;
        }
        final String method = request.getMethod() != null ? request.getMethod().getValue() : "GET";
        final String path = request.getPath() != null ? request.getPath().getValue() : "/";
        return method + " " + path;
    }

    private static boolean isNotted(NottableString value) {
        return value != null && value.isNot();
    }

    private static String blankToDefault(String value, String fallback) {
        return (value == null || value.trim().isEmpty()) ? fallback : value;
    }
}
