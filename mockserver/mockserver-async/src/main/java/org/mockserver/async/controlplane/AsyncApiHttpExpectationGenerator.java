package org.mockserver.async.controlplane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockserver.async.MessageExampleGenerator;
import org.mockserver.async.asyncapi.AsyncApiChannel;
import org.mockserver.async.asyncapi.AsyncApiSpec;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.model.MediaType;
import org.mockserver.serialization.ExpectationSerializer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Generates HTTP mock {@link Expectation}s from a parsed AsyncAPI spec so that the
 * example messages each channel describes can be served over plain HTTP — without
 * standing up a live Kafka / MQTT / AMQP broker.
 * <p>
 * For each channel in the spec one {@code GET} expectation is produced that responds
 * with the schema-aware example payload (the same payload the broker publisher would
 * send), at a path derived from the channel name. This is the import analogue of the
 * broker-publishing flow in {@link AsyncApiControlPlaneImpl#load(String)}; it reuses
 * the existing {@link AsyncApiParser} and {@link MessageExampleGenerator} rather than
 * re-deriving examples.
 */
public class AsyncApiHttpExpectationGenerator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final org.mockserver.async.asyncapi.AsyncApiParser parser = new org.mockserver.async.asyncapi.AsyncApiParser();
    private final MessageExampleGenerator exampleGenerator = new MessageExampleGenerator();
    private final ExpectationSerializer expectationSerializer = new ExpectationSerializer(new MockServerLogger());

    /**
     * Parse the request body and return the generated expectations serialized as a JSON
     * array string in the standard MockServer expectation format.
     *
     * @param requestBody a plain AsyncAPI spec (JSON/YAML) or a JSON wrapper
     *                    {@code {"spec": "...", "channelPathPrefix": "..."}}
     * @return a JSON array string of expectations
     * @throws IllegalArgumentException if the body is empty or the spec cannot be parsed
     */
    public String generateSerialized(String requestBody) {
        List<Expectation> expectations = generate(requestBody);
        return expectationSerializer.serialize(expectations);
    }

    /**
     * Parse the request body and return the generated expectations.
     *
     * @param requestBody a plain AsyncAPI spec (JSON/YAML) or a JSON wrapper
     *                    {@code {"spec": "...", "channelPathPrefix": "..."}}
     * @return one expectation per channel
     * @throws IllegalArgumentException if the body is empty or the spec cannot be parsed
     */
    public List<Expectation> generate(String requestBody) {
        if (requestBody == null || requestBody.isBlank()) {
            throw new IllegalArgumentException("request body must contain an AsyncAPI spec (JSON/YAML)");
        }

        String specContent = requestBody;
        String channelPathPrefix = "";
        JsonNode wrapper = tryParseJson(requestBody);
        if (wrapper != null && wrapper.has("spec")) {
            JsonNode specNode = wrapper.get("spec");
            try {
                specContent = specNode.isTextual() ? specNode.asText() : MAPPER.writeValueAsString(specNode);
            } catch (Exception e) {
                throw new IllegalArgumentException("unable to read AsyncAPI spec from request: " + e.getMessage(), e);
            }
            if (wrapper.has("channelPathPrefix") && wrapper.get("channelPathPrefix").isTextual()) {
                channelPathPrefix = wrapper.get("channelPathPrefix").asText();
            }
        }

        AsyncApiSpec spec;
        try {
            spec = parser.parse(specContent);
        } catch (Exception e) {
            throw new IllegalArgumentException("unable to parse AsyncAPI spec: " + e.getMessage(), e);
        }
        if (spec.getChannels().isEmpty()) {
            throw new IllegalArgumentException("AsyncAPI spec defined no channels");
        }

        List<Expectation> expectations = new ArrayList<>();
        Set<String> usedPaths = new LinkedHashSet<>();
        for (AsyncApiChannel channel : spec.getChannels()) {
            String example = exampleGenerator.generateExample(channel);
            String path = toPath(channelPathPrefix, channel.getName(), usedPaths);
            Expectation expectation = new Expectation(
                request()
                    .withMethod("GET")
                    .withPath(path)
            ).thenRespond(
                response()
                    .withStatusCode(200)
                    .withBody(example, MediaType.APPLICATION_JSON)
            );
            expectations.add(expectation);
        }
        return expectations;
    }

    /**
     * Map a channel / topic name to a unique URL path. AsyncAPI channel names are
     * frequently dot- or slash-separated topic names; dots become slashes so the path
     * reads naturally, and collisions are disambiguated with a numeric suffix.
     */
    private static String toPath(String prefix, String channelName, Set<String> usedPaths) {
        String name = channelName == null ? "" : channelName.trim();
        // Normalise common topic separators to a single URL form.
        name = name.replace('.', '/');
        while (name.startsWith("/")) {
            name = name.substring(1);
        }
        StringBuilder pathBuilder = new StringBuilder();
        if (prefix != null && !prefix.isBlank()) {
            String trimmedPrefix = prefix.trim();
            if (!trimmedPrefix.startsWith("/")) {
                pathBuilder.append('/');
            }
            pathBuilder.append(trimmedPrefix.endsWith("/")
                ? trimmedPrefix.substring(0, trimmedPrefix.length() - 1)
                : trimmedPrefix);
        }
        pathBuilder.append('/');
        pathBuilder.append(name.isEmpty() ? "channel" : name);
        String basePath = pathBuilder.toString().replaceAll("/+", "/");

        String candidate = basePath;
        int suffix = 2;
        while (!usedPaths.add(candidate)) {
            candidate = basePath + "/" + suffix++;
        }
        return candidate;
    }

    private static JsonNode tryParseJson(String body) {
        String trimmed = body.trim();
        if (!trimmed.startsWith("{")) {
            return null;
        }
        try {
            return MAPPER.readTree(trimmed);
        } catch (Exception e) {
            return null;
        }
    }
}
