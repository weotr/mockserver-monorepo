package org.mockserver.async.subscribe;

import java.util.List;

/**
 * Subscribes to a message broker channel and records received messages for
 * later retrieval/verification — the consumer counterpart of
 * {@link org.mockserver.async.publish.MessagePublisher}.
 * <p>
 * Implementations exist for Kafka ({@link KafkaMessageSubscriber}) and
 * MQTT ({@link MqttMessageSubscriber}).
 */
public interface MessageSubscriber {

    /**
     * Start subscribing to the given channel. Messages will be recorded
     * internally and are retrievable via {@link #getRecordedMessages(String)}.
     *
     * @param channel the channel/topic to subscribe to
     */
    void subscribe(String channel);

    /**
     * Stop subscribing to the given channel.
     */
    void unsubscribe(String channel);

    /**
     * Return all messages recorded on the given channel since subscription started
     * (or since the last reset).
     */
    List<RecordedMessage> getRecordedMessages(String channel);

    /**
     * Return all messages recorded across all channels.
     */
    List<RecordedMessage> getAllRecordedMessages();

    /**
     * Clear all recorded messages and stop all subscriptions.
     */
    void close();
}
