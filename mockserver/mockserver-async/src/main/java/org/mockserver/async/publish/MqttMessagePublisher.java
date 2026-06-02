package org.mockserver.async.publish;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.mockserver.async.security.MqttSecurity;
import org.mockserver.async.security.MqttSecurityOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * A {@link MessagePublisher} that delegates to an MQTT {@link MqttClient}.
 * The channel name maps directly to an MQTT topic.
 * <p>
 * Supports configurable QoS (0, 1, or 2) and both text and binary payloads.
 */
public class MqttMessagePublisher implements MessagePublisher {

    private static final Logger LOG = LoggerFactory.getLogger(MqttMessagePublisher.class);
    private static final int DEFAULT_QOS = 1;

    private final MqttClient client;
    private final int qos;

    /**
     * Create a publisher connected to the given MQTT broker with default QoS (1)
     * and no security. Backward-compatible entry point.
     *
     * @param brokerUrl the MQTT broker URL (e.g. {@code tcp://localhost:1883})
     * @param clientId  the client identifier
     */
    public MqttMessagePublisher(String brokerUrl, String clientId) {
        this(brokerUrl, clientId, DEFAULT_QOS);
    }

    /**
     * Create a publisher connected to the given MQTT broker with a specific QoS
     * and no security. Backward-compatible entry point.
     *
     * @param brokerUrl the MQTT broker URL (e.g. {@code tcp://localhost:1883})
     * @param clientId  the client identifier
     * @param qos       the MQTT QoS level (0, 1, or 2)
     */
    public MqttMessagePublisher(String brokerUrl, String clientId, int qos) {
        this(brokerUrl, clientId, qos, null);
    }

    /**
     * Create a publisher connected to the given MQTT broker with optional
     * security configuration.
     *
     * @param brokerUrl the MQTT broker URL (e.g. {@code ssl://localhost:8883})
     * @param clientId  the client identifier
     * @param qos       the MQTT QoS level (0, 1, or 2)
     * @param security  security configuration (may be null for plaintext)
     */
    public MqttMessagePublisher(String brokerUrl, String clientId, int qos, MqttSecurity security) {
        if (qos < 0 || qos > 2) {
            throw new IllegalArgumentException("MQTT QoS must be 0, 1, or 2; got: " + qos);
        }
        try {
            this.client = new MqttClient(brokerUrl, clientId);
            MqttConnectOptions options = MqttSecurityOptions.buildConnectOptions(security);
            if (options != null) {
                this.client.connect(options);
            } else {
                this.client.connect();
            }
            this.qos = qos;
        } catch (MqttException e) {
            throw new RuntimeException("Failed to connect to MQTT broker: " + brokerUrl, e);
        }
    }

    /**
     * Package-private constructor for injecting a mock client in tests.
     */
    MqttMessagePublisher(MqttClient client, int qos) {
        this.client = client;
        this.qos = qos;
    }

    @Override
    public void publish(String channel, String payload) {
        publishBytes(channel, payload.getBytes(StandardCharsets.UTF_8), null);
    }

    /**
     * Publish a message with per-message options from AsyncAPI bindings.
     * Applies {@link PublishOptions#getQos()} and {@link PublishOptions#getRetain()}
     * when non-null; falls back to the instance-level QoS and no-retain defaults.
     * The Kafka {@code key} field is ignored for MQTT.
     *
     * @param channel the MQTT topic name
     * @param payload the message payload (typically JSON)
     * @param options per-message publish options (may be null)
     */
    @Override
    public void publish(String channel, String payload, PublishOptions options) {
        publishBytes(channel, payload.getBytes(StandardCharsets.UTF_8), options);
    }

    /**
     * Publish a binary payload to the given channel. Supports binary message
     * formats as specified in AsyncAPI channel bindings.
     *
     * @param channel the MQTT topic name
     * @param payload the raw binary payload
     */
    public void publishBytes(String channel, byte[] payload) {
        publishBytes(channel, payload, null);
    }

    /**
     * Publish a binary payload with optional per-message options.
     *
     * @param channel the MQTT topic name
     * @param payload the raw binary payload
     * @param options per-message publish options (may be null)
     */
    void publishBytes(String channel, byte[] payload, PublishOptions options) {
        try {
            LOG.debug("Publishing to MQTT topic '{}': {} bytes", channel, payload.length);
            MqttMessage message = new MqttMessage(payload);
            int effectiveQos = (options != null && options.getQos() != null) ? options.getQos() : this.qos;
            message.setQos(effectiveQos);
            if (options != null && options.getRetain() != null) {
                message.setRetained(options.getRetain());
            }
            client.publish(channel, message);
        } catch (MqttException e) {
            throw new RuntimeException("Failed to publish to MQTT topic: " + channel, e);
        }
    }

    /**
     * @return the configured QoS level for this publisher
     */
    public int getQos() {
        return qos;
    }

    @Override
    public void close() {
        try {
            if (client.isConnected()) {
                client.disconnect();
            }
            client.close();
        } catch (MqttException e) {
            LOG.warn("Error closing MQTT client: {}", e.getMessage());
        }
    }
}
