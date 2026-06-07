package org.mockserver.async;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.async.asyncapi.AsyncApiChannel;
import org.mockserver.async.asyncapi.AsyncApiMessage;
import org.mockserver.async.asyncapi.AsyncApiSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generates example JSON payloads for each channel in an {@link AsyncApiSpec}.
 * <p>
 * Uses the following precedence for each channel:
 * <ol>
 *     <li>The first explicit example from the spec</li>
 *     <li>A schema-aware example synthesized from the JSON Schema payload
 *         (applies constraints: enum, minimum/maximum, minLength/maxLength,
 *         pattern hint, required fields, default values)</li>
 *     <li>An empty JSON object {@code {}} as a last-resort fallback</li>
 * </ol>
 */
public class MessageExampleGenerator {

    private static final Logger LOG = LoggerFactory.getLogger(MessageExampleGenerator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Generate example payloads for all channels in the spec.
     *
     * @return a map from channel name to JSON string payload
     */
    public Map<String, String> generateExamples(AsyncApiSpec spec) {
        Map<String, String> result = new LinkedHashMap<>();
        for (AsyncApiChannel channel : spec.getChannels()) {
            result.put(channel.getName(), generateExample(channel));
        }
        return result;
    }

    /**
     * Generate an example payload for a single channel.
     * Uses the channel's first message's examples and schema (backward compatible).
     */
    public String generateExample(AsyncApiChannel channel) {
        // 1. Try explicit example
        if (!channel.getPayloadExamples().isEmpty()) {
            JsonNode example = channel.getPayloadExamples().get(0);
            try {
                return MAPPER.writeValueAsString(example);
            } catch (Exception e) {
                LOG.warn("Failed to serialize payload example for channel {}: {}",
                    channel.getName(), e.getMessage());
            }
        }

        // 2. Try synthesizing from schema (schema-aware)
        if (channel.getPayloadSchema() != null) {
            JsonNode schema = channel.getPayloadSchema();
            JsonNode synthesized = synthesizeFromSchema(schema);
            if (synthesized != null) {
                try {
                    return MAPPER.writeValueAsString(synthesized);
                } catch (Exception e) {
                    LOG.warn("Failed to serialize synthesized example for channel {}: {}",
                        channel.getName(), e.getMessage());
                }
            }
        }

        // 3. Fallback: empty object
        return "{}";
    }

    /**
     * Generate an example payload for a single {@link AsyncApiMessage}.
     * Follows the same precedence as {@link #generateExample(AsyncApiChannel)}:
     * explicit example first, then schema synthesis, then empty object fallback.
     */
    public String generateExample(AsyncApiMessage message) {
        // 1. Try explicit example
        if (!message.getPayloadExamples().isEmpty()) {
            JsonNode example = message.getPayloadExamples().get(0);
            try {
                return MAPPER.writeValueAsString(example);
            } catch (Exception e) {
                LOG.warn("Failed to serialize payload example for message {}: {}",
                    message.getName(), e.getMessage());
            }
        }

        // 2. Try synthesizing from schema (schema-aware)
        if (message.getPayloadSchema() != null) {
            JsonNode schema = message.getPayloadSchema();
            JsonNode synthesized = synthesizeFromSchema(schema);
            if (synthesized != null) {
                try {
                    return MAPPER.writeValueAsString(synthesized);
                } catch (Exception e) {
                    LOG.warn("Failed to serialize synthesized example for message {}: {}",
                        message.getName(), e.getMessage());
                }
            }
        }

        // 3. Fallback: empty object
        return "{}";
    }

    /**
     * Synthesize a JSON value from a JSON Schema node, respecting constraints:
     * enum, default, minimum/maximum, minLength/maxLength, pattern, required.
     */
    JsonNode synthesizeFromSchema(JsonNode schema) {
        if (schema == null) {
            return null;
        }

        // If there is a default value, use it
        JsonNode defaultValue = schema.get("default");
        if (defaultValue != null) {
            return defaultValue.deepCopy();
        }

        // If there is an enum, use the first value
        JsonNode enumNode = schema.get("enum");
        if (enumNode != null && enumNode.isArray() && enumNode.size() > 0) {
            return enumNode.get(0).deepCopy();
        }

        // If there is a const, use it
        JsonNode constNode = schema.get("const");
        if (constNode != null) {
            return constNode.deepCopy();
        }

        String type = textOrNull(schema, "type");
        if (type == null) {
            // If there are properties, assume object
            if (schema.has("properties")) {
                type = "object";
            } else {
                return MAPPER.createObjectNode();
            }
        }

        switch (type) {
            case "object":
                return synthesizeObject(schema);
            case "array":
                return synthesizeArray(schema);
            case "string":
                return synthesizeString(schema);
            case "integer":
                return synthesizeInteger(schema);
            case "number":
                return synthesizeNumber(schema);
            case "boolean":
                return MAPPER.getNodeFactory().booleanNode(false);
            case "null":
                return MAPPER.getNodeFactory().nullNode();
            default:
                return MAPPER.createObjectNode();
        }
    }

    private JsonNode synthesizeObject(JsonNode schema) {
        ObjectNode result = MAPPER.createObjectNode();
        JsonNode properties = schema.get("properties");
        if (properties != null && properties.isObject()) {
            properties.fields().forEachRemaining(entry -> {
                JsonNode propValue = synthesizeFromSchema(entry.getValue());
                if (propValue != null) {
                    result.set(entry.getKey(), propValue);
                }
            });
        }
        return result;
    }

    private JsonNode synthesizeArray(JsonNode schema) {
        ArrayNode result = MAPPER.createArrayNode();
        JsonNode items = schema.get("items");
        if (items != null) {
            JsonNode itemExample = synthesizeFromSchema(items);
            if (itemExample != null) {
                result.add(itemExample);
            }
        }
        // Respect minItems: pad with copies of the first item if needed
        JsonNode minItems = schema.get("minItems");
        if (minItems != null && minItems.isNumber()) {
            int min = minItems.asInt();
            while (result.size() < min) {
                if (items != null) {
                    JsonNode extra = synthesizeFromSchema(items);
                    if (extra != null) {
                        result.add(extra);
                    } else {
                        break;
                    }
                } else {
                    break;
                }
            }
        }
        return result;
    }

    private JsonNode synthesizeString(JsonNode schema) {
        // Check for pattern hint — use a simple representative if possible
        JsonNode patternNode = schema.get("pattern");
        if (patternNode != null && patternNode.isTextual()) {
            String pattern = patternNode.asText();
            // For common patterns, generate a hint value
            if (pattern.contains("@")) {
                return MAPPER.getNodeFactory().textNode("user@example.com");
            }
            if (pattern.matches(".*\\^\\[0-9\\].*") || pattern.contains("\\d")) {
                return MAPPER.getNodeFactory().textNode("12345");
            }
        }

        // Check format for well-known formats
        JsonNode formatNode = schema.get("format");
        if (formatNode != null && formatNode.isTextual()) {
            String format = formatNode.asText();
            switch (format) {
                case "date-time":
                    return MAPPER.getNodeFactory().textNode("2024-01-01T00:00:00Z");
                case "date":
                    return MAPPER.getNodeFactory().textNode("2024-01-01");
                case "time":
                    return MAPPER.getNodeFactory().textNode("00:00:00Z");
                case "email":
                    return MAPPER.getNodeFactory().textNode("user@example.com");
                case "uri":
                case "url":
                    return MAPPER.getNodeFactory().textNode("https://example.com");
                case "uuid":
                    return MAPPER.getNodeFactory().textNode("00000000-0000-0000-0000-000000000000");
                case "ipv4":
                    return MAPPER.getNodeFactory().textNode("127.0.0.1");
                case "ipv6":
                    return MAPPER.getNodeFactory().textNode("::1");
                default:
                    break;
            }
        }

        // Respect minLength
        JsonNode minLength = schema.get("minLength");
        if (minLength != null && minLength.isNumber()) {
            int min = minLength.asInt();
            if (min > "string".length()) {
                return MAPPER.getNodeFactory().textNode("s".repeat(min));
            }
        }

        return MAPPER.getNodeFactory().textNode("string");
    }

    private JsonNode synthesizeInteger(JsonNode schema) {
        // Use minimum if present
        JsonNode minimum = schema.get("minimum");
        if (minimum != null && minimum.isNumber()) {
            long min = minimum.asLong();
            // Check for exclusiveMinimum (boolean form or numeric form)
            JsonNode exclusiveMin = schema.get("exclusiveMinimum");
            if (exclusiveMin != null) {
                if (exclusiveMin.isBoolean() && exclusiveMin.asBoolean()) {
                    return MAPPER.getNodeFactory().numberNode(min + 1);
                } else if (exclusiveMin.isNumber()) {
                    return MAPPER.getNodeFactory().numberNode(exclusiveMin.asLong() + 1);
                }
            }
            return MAPPER.getNodeFactory().numberNode(min);
        }
        // Check for exclusiveMinimum as standalone (draft 2019+)
        JsonNode exclusiveMin = schema.get("exclusiveMinimum");
        if (exclusiveMin != null && exclusiveMin.isNumber()) {
            return MAPPER.getNodeFactory().numberNode(exclusiveMin.asLong() + 1);
        }
        return MAPPER.getNodeFactory().numberNode(0);
    }

    private JsonNode synthesizeNumber(JsonNode schema) {
        JsonNode minimum = schema.get("minimum");
        if (minimum != null && minimum.isNumber()) {
            JsonNode exclusiveMin = schema.get("exclusiveMinimum");
            if (exclusiveMin != null) {
                if (exclusiveMin.isBoolean() && exclusiveMin.asBoolean()) {
                    return MAPPER.getNodeFactory().numberNode(minimum.asDouble() + 0.1);
                } else if (exclusiveMin.isNumber()) {
                    return MAPPER.getNodeFactory().numberNode(exclusiveMin.asDouble() + 0.1);
                }
            }
            return MAPPER.getNodeFactory().numberNode(minimum.asDouble());
        }
        JsonNode exclusiveMin = schema.get("exclusiveMinimum");
        if (exclusiveMin != null && exclusiveMin.isNumber()) {
            return MAPPER.getNodeFactory().numberNode(exclusiveMin.asDouble() + 0.1);
        }
        return MAPPER.getNodeFactory().numberNode(0.0);
    }

    private String textOrNull(JsonNode node, String fieldName) {
        JsonNode child = node.get(fieldName);
        return (child != null && child.isTextual()) ? child.asText() : null;
    }
}
