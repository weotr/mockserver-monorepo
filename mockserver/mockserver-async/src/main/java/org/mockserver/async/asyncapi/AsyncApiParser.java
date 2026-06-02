package org.mockserver.async.asyncapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Parses an AsyncAPI 2.x or 3.x document (JSON or YAML) into an {@link AsyncApiSpec}.
 * <p>
 * Supported structures:
 * <ul>
 *     <li>AsyncAPI 2.x: {@code channels.<name>.publish|subscribe.message.payload} (schema)
 *         and {@code channels.<name>.publish|subscribe.message.payload.example} or
 *         {@code channels.<name>.publish|subscribe.message.examples[].payload}</li>
 *     <li>AsyncAPI 3.x: {@code channels.<name>.messages.<msgName>.payload} (schema)
 *         and {@code channels.<name>.messages.<msgName>.examples[].payload} or
 *         {@code components.messages.<msgName>.examples[].payload}</li>
 * </ul>
 */
public class AsyncApiParser {

    private static final Logger LOG = LoggerFactory.getLogger(AsyncApiParser.class);
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    /**
     * Parse an AsyncAPI document from a string (auto-detects JSON vs YAML).
     */
    public AsyncApiSpec parse(String document) throws IOException {
        if (document == null || document.isBlank()) {
            throw new IllegalArgumentException("AsyncAPI document must not be null or blank");
        }
        String trimmed = document.trim();
        ObjectMapper mapper = trimmed.startsWith("{") ? JSON_MAPPER : YAML_MAPPER;
        JsonNode root = mapper.readTree(trimmed);
        return parseRoot(root);
    }

    private AsyncApiSpec parseRoot(JsonNode root) {
        String version = textOrNull(root, "asyncapi");
        String title = null;
        JsonNode info = root.get("info");
        if (info != null) {
            title = textOrNull(info, "title");
        }

        List<AsyncApiChannel> channels;
        if (version != null && version.startsWith("3")) {
            channels = parseV3Channels(root);
        } else {
            // Default to 2.x parsing
            channels = parseV2Channels(root);
        }

        return new AsyncApiSpec(version, title, channels);
    }

    // ---- AsyncAPI 2.x ----

    private List<AsyncApiChannel> parseV2Channels(JsonNode root) {
        List<AsyncApiChannel> result = new ArrayList<>();
        JsonNode channelsNode = root.get("channels");
        if (channelsNode == null || !channelsNode.isObject()) {
            return result;
        }

        Iterator<Map.Entry<String, JsonNode>> fields = channelsNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String channelName = entry.getKey();
            JsonNode channelDef = entry.getValue();

            // Try publish, then subscribe — capture the operation node for bindings
            JsonNode operationNode = findV2Operation(channelDef);
            JsonNode messageNode = (operationNode != null) ? operationNode.get("message") : null;
            if (messageNode == null) {
                result.add(new AsyncApiChannel(channelName, List.of(), null));
                continue;
            }

            List<JsonNode> examples = new ArrayList<>();
            JsonNode payloadSchema = null;

            JsonNode payload = messageNode.get("payload");
            if (payload != null) {
                payloadSchema = payload;
                // Check for inline example on payload
                JsonNode payloadExample = payload.get("example");
                if (payloadExample != null) {
                    examples.add(payloadExample);
                }
            }

            // Check for message-level examples array (AsyncAPI 2.x extended)
            JsonNode messageExamples = messageNode.get("examples");
            if (messageExamples != null && messageExamples.isArray()) {
                for (JsonNode ex : messageExamples) {
                    JsonNode exPayload = ex.get("payload");
                    if (exPayload != null) {
                        examples.add(exPayload);
                    }
                }
            }

            // Parse MQTT operation bindings (qos, retain)
            Integer mqttQos = parseMqttQos(operationNode);
            Boolean mqttRetain = parseMqttRetain(operationNode);

            // Parse Kafka message key binding
            String kafkaKey = parseKafkaKeyFromMessage(messageNode);

            result.add(new AsyncApiChannel(channelName, examples, payloadSchema,
                mqttQos, mqttRetain, kafkaKey));
        }

        return result;
    }

    /**
     * Find the v2 operation node (publish preferred, then subscribe).
     * Returns the operation node itself (not the message), so callers can
     * read operation-level bindings.
     */
    private JsonNode findV2Operation(JsonNode channelDef) {
        JsonNode publish = channelDef.get("publish");
        if (publish != null && publish.get("message") != null) {
            return publish;
        }
        JsonNode subscribe = channelDef.get("subscribe");
        if (subscribe != null && subscribe.get("message") != null) {
            return subscribe;
        }
        return null;
    }

    // ---- AsyncAPI 3.x ----

    private List<AsyncApiChannel> parseV3Channels(JsonNode root) {
        List<AsyncApiChannel> result = new ArrayList<>();
        JsonNode channelsNode = root.get("channels");
        if (channelsNode == null || !channelsNode.isObject()) {
            return result;
        }

        Iterator<Map.Entry<String, JsonNode>> fields = channelsNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String channelName = entry.getKey();
            JsonNode channelDef = entry.getValue();

            JsonNode messagesNode = channelDef.get("messages");
            if (messagesNode == null || !messagesNode.isObject()) {
                result.add(new AsyncApiChannel(channelName, List.of(), null));
                continue;
            }

            // Take the first message definition
            Iterator<Map.Entry<String, JsonNode>> msgFields = messagesNode.fields();
            if (!msgFields.hasNext()) {
                result.add(new AsyncApiChannel(channelName, List.of(), null));
                continue;
            }

            Map.Entry<String, JsonNode> firstMessage = msgFields.next();
            JsonNode msgDef = firstMessage.getValue();

            // Resolve $ref if present
            msgDef = resolveRef(root, msgDef);

            List<JsonNode> examples = new ArrayList<>();
            JsonNode payloadSchema = null;

            JsonNode payload = msgDef.get("payload");
            if (payload != null) {
                payloadSchema = payload;
            }

            // Check for examples array
            JsonNode msgExamples = msgDef.get("examples");
            if (msgExamples != null && msgExamples.isArray()) {
                for (JsonNode ex : msgExamples) {
                    JsonNode exPayload = ex.get("payload");
                    if (exPayload != null) {
                        examples.add(exPayload);
                    }
                }
            }

            // Parse Kafka message key binding from the message definition
            String kafkaKey = parseKafkaKeyFromMessage(msgDef);

            // Parse MQTT bindings: in v3, operation bindings live in the
            // top-level 'operations' section (not on channels). As a
            // best-effort, check for channel-level bindings.mqtt.
            // Full v3 operation-binding navigation is deferred.
            Integer mqttQos = parseMqttQos(channelDef);
            Boolean mqttRetain = parseMqttRetain(channelDef);

            result.add(new AsyncApiChannel(channelName, examples, payloadSchema,
                mqttQos, mqttRetain, kafkaKey));
        }

        return result;
    }

    // ---- Binding extraction helpers ----

    /**
     * Extract MQTT QoS from {@code node.bindings.mqtt.qos} (int 0/1/2).
     * Returns null when absent, non-integer, or out of range (never throws).
     */
    private Integer parseMqttQos(JsonNode node) {
        try {
            JsonNode bindings = node.get("bindings");
            if (bindings == null) {
                return null;
            }
            JsonNode mqtt = bindings.get("mqtt");
            if (mqtt == null) {
                return null;
            }
            JsonNode qosNode = mqtt.get("qos");
            if (qosNode != null && qosNode.isNumber()) {
                int qos = qosNode.asInt();
                if (qos >= 0 && qos <= 2) {
                    return qos;
                }
                LOG.warn("Ignoring MQTT QoS binding with out-of-range value: {}", qos);
            }
        } catch (Exception e) {
            LOG.warn("Failed to parse MQTT QoS binding: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Extract MQTT retain flag from {@code node.bindings.mqtt.retain} (boolean).
     * Returns null when absent or non-boolean (never throws).
     */
    private Boolean parseMqttRetain(JsonNode node) {
        try {
            JsonNode bindings = node.get("bindings");
            if (bindings == null) {
                return null;
            }
            JsonNode mqtt = bindings.get("mqtt");
            if (mqtt == null) {
                return null;
            }
            JsonNode retainNode = mqtt.get("retain");
            if (retainNode != null && retainNode.isBoolean()) {
                return retainNode.asBoolean();
            }
        } catch (Exception e) {
            LOG.warn("Failed to parse MQTT retain binding: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Extract a literal Kafka message key from {@code messageNode.bindings.kafka.key}.
     * <p>
     * The binding's {@code key} may be:
     * <ul>
     *   <li>a scalar literal (string/number) — used directly</li>
     *   <li>a schema with {@code const} — the const value</li>
     *   <li>a schema with {@code example} — the example value</li>
     *   <li>a schema with {@code examples[0]} — the first example</li>
     *   <li>a bare schema with no literal — returns null (not derivable)</li>
     * </ul>
     * Returns null when absent or not derivable (never throws).
     */
    private String parseKafkaKeyFromMessage(JsonNode messageNode) {
        try {
            JsonNode bindings = messageNode.get("bindings");
            if (bindings == null) {
                return null;
            }
            JsonNode kafka = bindings.get("kafka");
            if (kafka == null) {
                return null;
            }
            JsonNode keyNode = kafka.get("key");
            if (keyNode == null) {
                return null;
            }

            // Direct scalar value
            if (keyNode.isTextual()) {
                return keyNode.asText();
            }
            if (keyNode.isNumber()) {
                return keyNode.asText();
            }

            // Schema-like object: try const, example, examples[0]
            if (keyNode.isObject()) {
                JsonNode constNode = keyNode.get("const");
                if (constNode != null && constNode.isValueNode()) {
                    return constNode.asText();
                }
                JsonNode exampleNode = keyNode.get("example");
                if (exampleNode != null && exampleNode.isValueNode()) {
                    return exampleNode.asText();
                }
                JsonNode examplesNode = keyNode.get("examples");
                if (examplesNode != null && examplesNode.isArray() && examplesNode.size() > 0) {
                    JsonNode first = examplesNode.get(0);
                    if (first != null && first.isValueNode()) {
                        return first.asText();
                    }
                }
                // Bare schema with no derivable literal — return null
            }
        } catch (Exception e) {
            LOG.warn("Failed to parse Kafka key binding: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Basic single-level $ref resolution within the document.
     * Only resolves {@code #/components/messages/<name>} style references.
     */
    private JsonNode resolveRef(JsonNode root, JsonNode node) {
        if (node == null) {
            return null;
        }
        JsonNode ref = node.get("$ref");
        if (ref != null && ref.isTextual()) {
            String refPath = ref.asText();
            if (refPath.startsWith("#/")) {
                String[] parts = refPath.substring(2).split("/");
                JsonNode resolved = root;
                for (String part : parts) {
                    if (resolved == null) {
                        break;
                    }
                    resolved = resolved.get(part);
                }
                if (resolved != null) {
                    return resolved;
                }
                LOG.warn("Could not resolve $ref: {}", refPath);
            }
        }
        return node;
    }

    private String textOrNull(JsonNode node, String fieldName) {
        JsonNode child = node.get(fieldName);
        return (child != null && child.isTextual()) ? child.asText() : null;
    }
}
