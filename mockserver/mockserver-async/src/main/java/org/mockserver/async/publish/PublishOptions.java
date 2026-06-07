package org.mockserver.async.publish;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable carrier for per-message publish-time options extracted from AsyncAPI bindings.
 * <p>
 * All fields are nullable; a {@code null} value means "use the publisher default".
 * <ul>
 *     <li>{@code key} — Kafka record key (from {@code message.bindings.kafka.key})</li>
 *     <li>{@code qos} — MQTT QoS level 0, 1, or 2 (from {@code operationBindings.mqtt.qos})</li>
 *     <li>{@code retain} — MQTT retain flag (from {@code operationBindings.mqtt.retain})</li>
 *     <li>{@code headers} — message headers, e.g. for correlation IDs (may be empty)</li>
 * </ul>
 *
 * @see org.mockserver.async.asyncapi.AsyncApiChannel#toPublishOptions()
 */
public final class PublishOptions {

    private static final PublishOptions NONE = new PublishOptions(null, null, null);

    private final String key;
    private final Integer qos;
    private final Boolean retain;
    private final Map<String, String> headers;

    /**
     * Backward-compatible constructor — no headers.
     *
     * @param key    Kafka record key (may be null)
     * @param qos    MQTT QoS level (may be null; must be 0, 1, or 2 when non-null)
     * @param retain MQTT retain flag (may be null)
     * @throws IllegalArgumentException if {@code qos} is non-null and not in {0, 1, 2}
     */
    public PublishOptions(String key, Integer qos, Boolean retain) {
        this(key, qos, retain, null);
    }

    /**
     * Full constructor with headers.
     *
     * @param key     Kafka record key (may be null)
     * @param qos     MQTT QoS level (may be null; must be 0, 1, or 2 when non-null)
     * @param retain  MQTT retain flag (may be null)
     * @param headers message headers (may be null; defensively copied)
     * @throws IllegalArgumentException if {@code qos} is non-null and not in {0, 1, 2}
     */
    public PublishOptions(String key, Integer qos, Boolean retain, Map<String, String> headers) {
        if (qos != null && (qos < 0 || qos > 2)) {
            throw new IllegalArgumentException("MQTT QoS must be 0, 1, or 2; got: " + qos);
        }
        this.key = key;
        this.qos = qos;
        this.retain = retain;
        this.headers = (headers != null && !headers.isEmpty())
            ? Collections.unmodifiableMap(new LinkedHashMap<>(headers))
            : Collections.emptyMap();
    }

    /**
     * @return a shared instance with all fields null/empty (no bindings)
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
     * @return unmodifiable message headers (never null; empty when no headers)
     */
    public Map<String, String> getHeaders() {
        return headers;
    }

    /**
     * @return true if all fields are null/empty (no bindings)
     */
    public boolean isEmpty() {
        return key == null && qos == null && retain == null && headers.isEmpty();
    }

    @Override
    public String toString() {
        return "PublishOptions{key=" + key + ", qos=" + qos + ", retain=" + retain
            + ", headers=" + headers + '}';
    }
}
