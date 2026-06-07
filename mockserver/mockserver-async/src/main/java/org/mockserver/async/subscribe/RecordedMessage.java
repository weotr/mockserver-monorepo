package org.mockserver.async.subscribe;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * An immutable record of a message received from a broker subscription.
 * Mirrors how MockServer records HTTP requests for later verification.
 */
public class RecordedMessage {

    private final String channel;
    private final String key;
    private final String payload;
    private final Map<String, String> headers;
    private final Instant timestamp;

    public RecordedMessage(String channel, String key, String payload, Map<String, String> headers) {
        this.channel = channel;
        this.key = key;
        this.payload = payload;
        this.headers = headers != null ? Collections.unmodifiableMap(headers) : Collections.emptyMap();
        this.timestamp = Instant.now();
    }

    public String getChannel() {
        return channel;
    }

    public String getKey() {
        return key;
    }

    public String getPayload() {
        return payload;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "RecordedMessage{" +
            "channel='" + channel + '\'' +
            ", key='" + key + '\'' +
            ", payload='" + payload + '\'' +
            ", headers=" + headers +
            ", timestamp=" + timestamp +
            '}';
    }
}
