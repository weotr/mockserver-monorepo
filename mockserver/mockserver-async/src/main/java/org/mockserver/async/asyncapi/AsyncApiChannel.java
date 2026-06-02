package org.mockserver.async.asyncapi;

import com.fasterxml.jackson.databind.JsonNode;
import org.mockserver.async.publish.PublishOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a single channel from an AsyncAPI specification,
 * with its name, zero or more message payload examples, and
 * optional publish-time bindings (MQTT qos/retain, Kafka key).
 */
public class AsyncApiChannel {

    private final String name;
    private final List<JsonNode> payloadExamples;
    private final JsonNode payloadSchema;
    private final Integer mqttQos;
    private final Boolean mqttRetain;
    private final String kafkaKey;

    /**
     * Backward-compatible constructor — no bindings.
     */
    public AsyncApiChannel(String name, List<JsonNode> payloadExamples, JsonNode payloadSchema) {
        this(name, payloadExamples, payloadSchema, null, null, null);
    }

    /**
     * Full constructor with optional binding fields.
     *
     * @param name            the channel / topic name
     * @param payloadExamples explicit payload examples from the spec
     * @param payloadSchema   the JSON Schema for the payload (may be null)
     * @param mqttQos         MQTT QoS level from operation bindings (may be null)
     * @param mqttRetain      MQTT retain flag from operation bindings (may be null)
     * @param kafkaKey        Kafka message key from message bindings (may be null)
     */
    public AsyncApiChannel(String name, List<JsonNode> payloadExamples, JsonNode payloadSchema,
                           Integer mqttQos, Boolean mqttRetain, String kafkaKey) {
        this.name = name;
        this.payloadExamples = payloadExamples != null
            ? Collections.unmodifiableList(new ArrayList<>(payloadExamples))
            : Collections.emptyList();
        this.payloadSchema = payloadSchema;
        this.mqttQos = mqttQos;
        this.mqttRetain = mqttRetain;
        this.kafkaKey = kafkaKey;
    }

    public String getName() {
        return name;
    }

    /**
     * Explicit examples found in the spec for the channel's message payload.
     */
    public List<JsonNode> getPayloadExamples() {
        return payloadExamples;
    }

    /**
     * The JSON Schema describing the payload (may be null if not present).
     */
    public JsonNode getPayloadSchema() {
        return payloadSchema;
    }

    /**
     * MQTT QoS level (0, 1, or 2) from operation-level bindings, or null if not specified.
     */
    public Integer getMqttQos() {
        return mqttQos;
    }

    /**
     * MQTT retain flag from operation-level bindings, or null if not specified.
     */
    public Boolean getMqttRetain() {
        return mqttRetain;
    }

    /**
     * Kafka message key from message-level bindings, or null if not derivable.
     * Only populated when a literal value (const, example, examples[0]) is present.
     */
    public String getKafkaKey() {
        return kafkaKey;
    }

    /**
     * Build a {@link PublishOptions} from the parsed bindings on this channel.
     *
     * @return a PublishOptions carrying the binding values, or {@link PublishOptions#none()} when all are null
     */
    public PublishOptions toPublishOptions() {
        if (mqttQos == null && mqttRetain == null && kafkaKey == null) {
            return PublishOptions.none();
        }
        return new PublishOptions(kafkaKey, mqttQos, mqttRetain);
    }
}
