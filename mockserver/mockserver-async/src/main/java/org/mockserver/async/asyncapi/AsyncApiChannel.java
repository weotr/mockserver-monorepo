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
    private final List<AsyncApiMessage> explicitMessages;
    private final String correlationIdLocation;
    private final AmqpBinding amqpBinding;

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
        this(name, payloadExamples, payloadSchema, mqttQos, mqttRetain, kafkaKey, null, null);
    }

    /**
     * Full constructor with optional binding fields and explicit multi-message list.
     *
     * @param name              the channel / topic name
     * @param payloadExamples   explicit payload examples from the spec (first message's examples for back-compat)
     * @param payloadSchema     the JSON Schema for the payload (first message's schema for back-compat)
     * @param mqttQos           MQTT QoS level from operation bindings (may be null)
     * @param mqttRetain        MQTT retain flag from operation bindings (may be null)
     * @param kafkaKey          Kafka message key from first message's bindings (may be null)
     * @param explicitMessages  the list of all messages in this channel (null or empty for single-message channels)
     */
    public AsyncApiChannel(String name, List<JsonNode> payloadExamples, JsonNode payloadSchema,
                           Integer mqttQos, Boolean mqttRetain, String kafkaKey,
                           List<AsyncApiMessage> explicitMessages) {
        this(name, payloadExamples, payloadSchema, mqttQos, mqttRetain, kafkaKey, explicitMessages, null);
    }

    /**
     * Full constructor with optional binding fields, explicit multi-message list,
     * and correlation ID location for single-message channels.
     *
     * @param name                    the channel / topic name
     * @param payloadExamples         explicit payload examples from the spec (first message's examples for back-compat)
     * @param payloadSchema           the JSON Schema for the payload (first message's schema for back-compat)
     * @param mqttQos                 MQTT QoS level from operation bindings (may be null)
     * @param mqttRetain              MQTT retain flag from operation bindings (may be null)
     * @param kafkaKey                Kafka message key from first message's bindings (may be null)
     * @param explicitMessages        the list of all messages in this channel (null or empty for single-message channels)
     * @param correlationIdLocation   correlation ID runtime expression for single-message channels (may be null)
     */
    public AsyncApiChannel(String name, List<JsonNode> payloadExamples, JsonNode payloadSchema,
                           Integer mqttQos, Boolean mqttRetain, String kafkaKey,
                           List<AsyncApiMessage> explicitMessages, String correlationIdLocation) {
        this(name, payloadExamples, payloadSchema, mqttQos, mqttRetain, kafkaKey,
            explicitMessages, correlationIdLocation, null);
    }

    /**
     * Full constructor with optional binding fields, explicit multi-message list,
     * correlation ID location, and AMQP channel binding.
     *
     * @param name                    the channel / topic name
     * @param payloadExamples         explicit payload examples from the spec (first message's examples for back-compat)
     * @param payloadSchema           the JSON Schema for the payload (first message's schema for back-compat)
     * @param mqttQos                 MQTT QoS level from operation bindings (may be null)
     * @param mqttRetain              MQTT retain flag from operation bindings (may be null)
     * @param kafkaKey                Kafka message key from first message's bindings (may be null)
     * @param explicitMessages        the list of all messages in this channel (null or empty for single-message channels)
     * @param correlationIdLocation   correlation ID runtime expression for single-message channels (may be null)
     * @param amqpBinding             parsed AMQP channel binding (may be null when the channel has no amqp binding)
     */
    public AsyncApiChannel(String name, List<JsonNode> payloadExamples, JsonNode payloadSchema,
                           Integer mqttQos, Boolean mqttRetain, String kafkaKey,
                           List<AsyncApiMessage> explicitMessages, String correlationIdLocation,
                           AmqpBinding amqpBinding) {
        this.name = name;
        this.payloadExamples = payloadExamples != null
            ? Collections.unmodifiableList(new ArrayList<>(payloadExamples))
            : Collections.emptyList();
        this.payloadSchema = payloadSchema;
        this.mqttQos = mqttQos;
        this.mqttRetain = mqttRetain;
        this.kafkaKey = kafkaKey;
        this.explicitMessages = (explicitMessages != null && !explicitMessages.isEmpty())
            ? Collections.unmodifiableList(new ArrayList<>(explicitMessages))
            : null;
        this.correlationIdLocation = correlationIdLocation;
        this.amqpBinding = amqpBinding;
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
     * The parsed AMQP channel binding ({@code bindings.amqp}), or null when the
     * channel declares no AMQP binding. Carries the exchange/queue/routing-key
     * routing information used by the AMQP publisher.
     */
    public AmqpBinding getAmqpBinding() {
        return amqpBinding;
    }

    /**
     * Returns all messages for this channel as a uniform list.
     * <p>
     * If this channel was parsed with multiple explicit messages (v3 multi-message
     * or v2 oneOf), returns that list. Otherwise, synthesizes a single-element list
     * from this channel's existing payload schema, examples, and kafka key fields,
     * providing a uniform per-message view for both single- and multi-message channels.
     *
     * @return an unmodifiable list of at least one {@link AsyncApiMessage}
     */
    public List<AsyncApiMessage> getMessages() {
        if (explicitMessages != null) {
            return explicitMessages;
        }
        return List.of(new AsyncApiMessage(null, payloadSchema, payloadExamples, kafkaKey, correlationIdLocation));
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
