package org.mockserver.async.publish;

/**
 * Immutable carrier for per-message publish-time options extracted from AsyncAPI bindings.
 * <p>
 * All fields are nullable; a {@code null} value means "use the publisher default".
 * <ul>
 *     <li>{@code key} — Kafka record key (from {@code message.bindings.kafka.key})</li>
 *     <li>{@code qos} — MQTT QoS level 0, 1, or 2 (from {@code operationBindings.mqtt.qos})</li>
 *     <li>{@code retain} — MQTT retain flag (from {@code operationBindings.mqtt.retain})</li>
 * </ul>
 *
 * @see org.mockserver.async.asyncapi.AsyncApiChannel#toPublishOptions()
 */
public final class PublishOptions {

    private static final PublishOptions NONE = new PublishOptions(null, null, null);

    private final String key;
    private final Integer qos;
    private final Boolean retain;

    /**
     * @param key    Kafka record key (may be null)
     * @param qos    MQTT QoS level (may be null; must be 0, 1, or 2 when non-null)
     * @param retain MQTT retain flag (may be null)
     * @throws IllegalArgumentException if {@code qos} is non-null and not in {0, 1, 2}
     */
    public PublishOptions(String key, Integer qos, Boolean retain) {
        if (qos != null && (qos < 0 || qos > 2)) {
            throw new IllegalArgumentException("MQTT QoS must be 0, 1, or 2; got: " + qos);
        }
        this.key = key;
        this.qos = qos;
        this.retain = retain;
    }

    /**
     * @return a shared instance with all fields null (no bindings)
     */
    public static PublishOptions none() {
        return NONE;
    }

    /**
     * @return the Kafka record key, or null if not specified
     */
    public String getKey() {
        return key;
    }

    /**
     * @return the MQTT QoS level (0, 1, or 2), or null if not specified
     */
    public Integer getQos() {
        return qos;
    }

    /**
     * @return the MQTT retain flag, or null if not specified
     */
    public Boolean getRetain() {
        return retain;
    }

    /**
     * @return true if all fields are null (no bindings)
     */
    public boolean isEmpty() {
        return key == null && qos == null && retain == null;
    }

    @Override
    public String toString() {
        return "PublishOptions{key=" + key + ", qos=" + qos + ", retain=" + retain + '}';
    }
}
