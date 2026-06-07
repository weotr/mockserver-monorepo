package org.mockserver.async.asyncapi;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a single message definition within an AsyncAPI channel.
 * <p>
 * A channel may contain multiple messages (AsyncAPI 3.x {@code channels.<n>.messages}
 * or AsyncAPI 2.x {@code oneOf} in the operation message). Each message has its own
 * payload schema, examples, optional Kafka key binding, and optional correlation ID location.
 * <p>
 * MQTT qos/retain remain channel-level (operation binding) and are not per-message.
 */
public class AsyncApiMessage {

    private final String name;
    private final JsonNode payloadSchema;
    private final List<JsonNode> payloadExamples;
    private final String kafkaKey;
    private final String correlationIdLocation;

    /**
     * Backward-compatible constructor — no correlation ID.
     *
     * @param name            the message name (nullable; e.g. the key under {@code messages.<name>} in v3)
     * @param payloadSchema   the JSON Schema for the payload (may be null)
     * @param payloadExamples explicit payload examples from the spec (never null after construction)
     * @param kafkaKey        Kafka message key from message-level bindings (may be null)
     */
    public AsyncApiMessage(String name, JsonNode payloadSchema, List<JsonNode> payloadExamples, String kafkaKey) {
        this(name, payloadSchema, payloadExamples, kafkaKey, null);
    }

    /**
     * Full constructor with correlation ID location.
     *
     * @param name                   the message name (nullable; e.g. the key under {@code messages.<name>} in v3)
     * @param payloadSchema          the JSON Schema for the payload (may be null)
     * @param payloadExamples        explicit payload examples from the spec (never null after construction)
     * @param kafkaKey               Kafka message key from message-level bindings (may be null)
     * @param correlationIdLocation  the runtime expression from {@code correlationId.location} (may be null)
     */
    public AsyncApiMessage(String name, JsonNode payloadSchema, List<JsonNode> payloadExamples,
                           String kafkaKey, String correlationIdLocation) {
        this.name = name;
        this.payloadSchema = payloadSchema;
        this.payloadExamples = payloadExamples != null
            ? Collections.unmodifiableList(new ArrayList<>(payloadExamples))
            : Collections.emptyList();
        this.kafkaKey = kafkaKey;
        this.correlationIdLocation = correlationIdLocation;
    }

    /**
     * The message name, or null for anonymous / unnamed messages.
     */
    public String getName() {
        return name;
    }

    /**
     * The JSON Schema describing the payload (may be null if not present).
     */
    public JsonNode getPayloadSchema() {
        return payloadSchema;
    }

    /**
     * Explicit examples found in the spec for this message's payload.
     */
    public List<JsonNode> getPayloadExamples() {
        return payloadExamples;
    }

    /**
     * Kafka message key from message-level bindings, or null if not derivable.
     */
    public String getKafkaKey() {
        return kafkaKey;
    }

    /**
     * The AsyncAPI correlation ID runtime expression (e.g.
     * {@code $message.header#/correlationId} or {@code $message.payload#/metadata/id}),
     * or null if the message does not define a correlation ID.
     */
    public String getCorrelationIdLocation() {
        return correlationIdLocation;
    }
}
