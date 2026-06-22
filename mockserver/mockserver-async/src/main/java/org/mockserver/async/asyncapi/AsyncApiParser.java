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

            // Parse MQTT operation bindings (qos, retain) — channel-level
            Integer mqttQos = parseMqttQos(operationNode);
            Boolean mqttRetain = parseMqttRetain(operationNode);

            // Parse AMQP channel binding (bindings.amqp lives on the channel in both v2 and v3)
            AmqpBinding amqpBinding = parseAmqpBinding(channelDef);

            // Check for oneOf (multi-message in AsyncAPI 2.x)
            JsonNode oneOf = messageNode.get("oneOf");
            if (oneOf != null && oneOf.isArray() && oneOf.size() > 1) {
                List<AsyncApiMessage> allMessages = new ArrayList<>();
                List<JsonNode> firstExamples = null;
                JsonNode firstPayloadSchema = null;
                String firstKafkaKey = null;
                String firstCorrelationIdLoc = null;
                boolean isFirst = true;

                for (JsonNode variant : oneOf) {
                    if (variant == null || !variant.isObject()) {
                        continue;
                    }
                    List<JsonNode> variantExamples = new ArrayList<>();
                    JsonNode variantSchema = null;

                    JsonNode payload = variant.get("payload");
                    if (payload != null) {
                        variantSchema = payload;
                        JsonNode payloadExample = payload.get("example");
                        if (payloadExample != null) {
                            variantExamples.add(payloadExample);
                        }
                    }

                    JsonNode variantMsgExamples = variant.get("examples");
                    if (variantMsgExamples != null && variantMsgExamples.isArray()) {
                        for (JsonNode ex : variantMsgExamples) {
                            JsonNode exPayload = ex.get("payload");
                            if (exPayload != null) {
                                variantExamples.add(exPayload);
                            }
                        }
                    }

                    String variantKafkaKey = parseKafkaKeyFromMessage(variant);
                    String variantCorrelationId = parseCorrelationIdLocation(root, variant);

                    // Use the message name if available (e.g. from messageId or name field)
                    String msgName = textOrNull(variant, "name");
                    allMessages.add(new AsyncApiMessage(msgName, variantSchema, variantExamples,
                        variantKafkaKey, variantCorrelationId));

                    if (isFirst) {
                        firstExamples = variantExamples;
                        firstPayloadSchema = variantSchema;
                        firstKafkaKey = variantKafkaKey;
                        firstCorrelationIdLoc = variantCorrelationId;
                        isFirst = false;
                    }
                }

                if (!allMessages.isEmpty()) {
                    List<AsyncApiMessage> explicitMessages = allMessages.size() > 1 ? allMessages : null;
                    result.add(new AsyncApiChannel(channelName,
                        firstExamples != null ? firstExamples : List.of(),
                        firstPayloadSchema,
                        mqttQos, mqttRetain, firstKafkaKey, explicitMessages, firstCorrelationIdLoc, amqpBinding));
                } else {
                    // All oneOf entries were malformed — fall through to empty channel
                    result.add(new AsyncApiChannel(channelName, List.of(), null,
                        mqttQos, mqttRetain, null, null, null, amqpBinding));
                }
            } else {
                // Single message (no oneOf, or oneOf with 0-1 entries)
                JsonNode singleMsg = messageNode;
                // If oneOf has exactly 1 entry, use that entry
                if (oneOf != null && oneOf.isArray() && oneOf.size() == 1) {
                    JsonNode single = oneOf.get(0);
                    if (single != null && single.isObject()) {
                        singleMsg = single;
                    }
                }

                List<JsonNode> examples = new ArrayList<>();
                JsonNode payloadSchema = null;

                JsonNode payload = singleMsg.get("payload");
                if (payload != null) {
                    payloadSchema = payload;
                    // Check for inline example on payload
                    JsonNode payloadExample = payload.get("example");
                    if (payloadExample != null) {
                        examples.add(payloadExample);
                    }
                }

                // Check for message-level examples array (AsyncAPI 2.x extended)
                JsonNode messageExamples = singleMsg.get("examples");
                if (messageExamples != null && messageExamples.isArray()) {
                    for (JsonNode ex : messageExamples) {
                        JsonNode exPayload = ex.get("payload");
                        if (exPayload != null) {
                            examples.add(exPayload);
                        }
                    }
                }

                // Parse Kafka message key binding
                String kafkaKey = parseKafkaKeyFromMessage(singleMsg);

                // Parse correlation ID location
                String correlationIdLoc = parseCorrelationIdLocation(root, singleMsg);

                result.add(new AsyncApiChannel(channelName, examples, payloadSchema,
                    mqttQos, mqttRetain, kafkaKey, null, correlationIdLoc, amqpBinding));
            }
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

            Iterator<Map.Entry<String, JsonNode>> msgFields = messagesNode.fields();
            if (!msgFields.hasNext()) {
                result.add(new AsyncApiChannel(channelName, List.of(), null));
                continue;
            }

            // Parse MQTT bindings: in v3, operation bindings live in the
            // top-level 'operations' section (not on channels). As a
            // best-effort, check for channel-level bindings.mqtt.
            // Full v3 operation-binding navigation is deferred.
            Integer mqttQos = parseMqttQos(channelDef);
            Boolean mqttRetain = parseMqttRetain(channelDef);

            // Parse AMQP channel binding (bindings.amqp lives on the channel in v3)
            AmqpBinding amqpBinding = parseAmqpBinding(channelDef);

            // Iterate ALL message definitions in this channel
            List<AsyncApiMessage> allMessages = new ArrayList<>();
            List<JsonNode> firstExamples = null;
            JsonNode firstPayloadSchema = null;
            String firstKafkaKey = null;
            String firstCorrelationIdLoc = null;
            boolean isFirst = true;

            while (msgFields.hasNext()) {
                Map.Entry<String, JsonNode> msgEntry = msgFields.next();
                String msgName = msgEntry.getKey();
                JsonNode msgDef = msgEntry.getValue();

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

                // Parse correlation ID location
                String correlationIdLoc = parseCorrelationIdLocation(root, msgDef);

                allMessages.add(new AsyncApiMessage(msgName, payloadSchema, examples,
                    kafkaKey, correlationIdLoc));

                // Preserve first message's fields for backward compatibility
                if (isFirst) {
                    firstExamples = examples;
                    firstPayloadSchema = payloadSchema;
                    firstKafkaKey = kafkaKey;
                    firstCorrelationIdLoc = correlationIdLoc;
                    isFirst = false;
                }
            }

            // Build the channel: use explicit messages list only when >1 message
            List<AsyncApiMessage> explicitMessages = allMessages.size() > 1 ? allMessages : null;

            result.add(new AsyncApiChannel(channelName, firstExamples, firstPayloadSchema,
                mqttQos, mqttRetain, firstKafkaKey, explicitMessages, firstCorrelationIdLoc, amqpBinding));
        }

        return result;
    }

    // ---- Binding extraction helpers ----

    /**
     * Extract the AMQP channel binding from {@code channelDef.bindings.amqp}.
     * <p>
     * Reads the {@code is} discriminator ({@code routingKey} / {@code queue}),
     * the {@code exchange} object ({@code name}/{@code type}/{@code durable}),
     * the {@code queue} object ({@code name}/{@code durable}), and an optional
     * top-level {@code routingKey} extension. Returns null when no
     * {@code bindings.amqp} object is present (never throws).
     */
    private AmqpBinding parseAmqpBinding(JsonNode channelDef) {
        try {
            if (channelDef == null) {
                return null;
            }
            JsonNode bindings = channelDef.get("bindings");
            if (bindings == null) {
                return null;
            }
            JsonNode amqp = bindings.get("amqp");
            if (amqp == null || !amqp.isObject()) {
                return null;
            }

            // Discriminator: 'is' = routingKey (default) | queue
            AmqpBinding.ChannelType channelType = AmqpBinding.ChannelType.ROUTING_KEY;
            JsonNode isNode = amqp.get("is");
            if (isNode != null && isNode.isTextual() && "queue".equalsIgnoreCase(isNode.asText())) {
                channelType = AmqpBinding.ChannelType.QUEUE;
            }

            // Exchange object
            String exchangeName = null;
            String exchangeType = null;
            boolean exchangeDurable = true;
            JsonNode exchange = amqp.get("exchange");
            if (exchange != null && exchange.isObject()) {
                exchangeName = textOrNull(exchange, "name");
                exchangeType = textOrNull(exchange, "type");
                JsonNode durable = exchange.get("durable");
                if (durable != null && durable.isBoolean()) {
                    exchangeDurable = durable.asBoolean();
                }
            }

            // Queue object
            String queueName = null;
            boolean queueDurable = true;
            JsonNode queue = amqp.get("queue");
            if (queue != null && queue.isObject()) {
                queueName = textOrNull(queue, "name");
                JsonNode durable = queue.get("durable");
                if (durable != null && durable.isBoolean()) {
                    queueDurable = durable.asBoolean();
                }
            }

            // Optional explicit routing key (extension; AsyncAPI itself uses the channel name)
            String routingKey = textOrNull(amqp, "routingKey");

            return new AmqpBinding(channelType, exchangeName, exchangeType, exchangeDurable,
                queueName, queueDurable, routingKey);
        } catch (Exception e) {
            LOG.warn("Failed to parse AMQP channel binding: {}", e.getMessage());
            return null;
        }
    }

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
     * Extract the correlation ID location runtime expression from a message node.
     * <p>
     * The {@code correlationId} field may be:
     * <ul>
     *   <li>An inline object with {@code location} (and optional {@code description})</li>
     *   <li>A {@code $ref} to {@code #/components/correlationIds/<name>}</li>
     * </ul>
     * Returns null when absent, malformed, or not resolvable (never throws).
     */
    private String parseCorrelationIdLocation(JsonNode root, JsonNode messageNode) {
        try {
            JsonNode correlationIdNode = messageNode.get("correlationId");
            if (correlationIdNode == null) {
                return null;
            }

            // Resolve $ref if present (e.g. $ref: #/components/correlationIds/myId)
            correlationIdNode = resolveRef(root, correlationIdNode);

            if (correlationIdNode != null && correlationIdNode.isObject()) {
                JsonNode locationNode = correlationIdNode.get("location");
                if (locationNode != null && locationNode.isTextual()) {
                    return locationNode.asText();
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to parse correlationId from message: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Basic $ref resolution within the document — resolves any local {@code #/}-prefixed
     * JSON-Pointer reference (e.g. {@code #/components/messages/<name>} or
     * {@code #/components/correlationIds/<name>}) by walking the path segments.
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
