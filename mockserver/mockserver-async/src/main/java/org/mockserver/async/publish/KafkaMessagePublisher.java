package org.mockserver.async.publish;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringSerializer;
import org.mockserver.async.security.KafkaSecurity;
import org.mockserver.async.security.KafkaSecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;

/**
 * A {@link MessagePublisher} that delegates to a Kafka {@link KafkaProducer}.
 * The channel name maps directly to a Kafka topic.
 * <p>
 * Supports configurable record keys and message headers in addition to
 * the basic payload publishing.
 */
public class KafkaMessagePublisher implements MessagePublisher {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaMessagePublisher.class);

    private final KafkaProducer<String, String> producer;

    /**
     * Create a publisher connected to the given Kafka bootstrap servers
     * using plaintext (no security). Backward-compatible entry point.
     *
     * @param bootstrapServers comma-separated list of host:port pairs
     */
    public KafkaMessagePublisher(String bootstrapServers) {
        this(bootstrapServers, null);
    }

    /**
     * Create a publisher connected to the given Kafka bootstrap servers
     * with optional security configuration.
     *
     * @param bootstrapServers comma-separated list of host:port pairs
     * @param security         security configuration (may be null for plaintext)
     */
    public KafkaMessagePublisher(String bootstrapServers, KafkaSecurity security) {
        Properties props = buildProducerProperties(bootstrapServers, security);
        this.producer = new KafkaProducer<>(props);
    }

    /**
     * Package-private constructor for injecting a mock producer in tests.
     */
    KafkaMessagePublisher(KafkaProducer<String, String> producer) {
        this.producer = producer;
    }

    /**
     * Build the Kafka producer properties with security applied.
     * Package-private for direct unit-testing of the property assembly.
     *
     * @param bootstrapServers comma-separated list of host:port pairs
     * @param security         security configuration (may be null)
     * @return the fully configured properties
     */
    static Properties buildProducerProperties(String bootstrapServers, KafkaSecurity security) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        KafkaSecurityProperties.applySecurity(props, security);
        return props;
    }

    @Override
    public void publish(String channel, String payload) {
        publish(channel, null, payload, null);
    }

    /**
     * Publish a message with per-message options from AsyncAPI bindings.
     * Only the {@link PublishOptions#getKey()} is applied (Kafka has no
     * per-message QoS or retain). Other binding fields are ignored.
     *
     * @param channel the Kafka topic name
     * @param payload the message payload (typically JSON)
     * @param options per-message publish options (may be null)
     */
    @Override
    public void publish(String channel, String payload, PublishOptions options) {
        String key = (options != null) ? options.getKey() : null;
        publish(channel, key, payload, null);
    }

    /**
     * Publish a message with a specific key and optional headers.
     *
     * @param channel the Kafka topic name
     * @param key     the record key (may be null)
     * @param payload the message payload (typically JSON)
     * @param headers optional headers (may be null or empty)
     */
    public void publish(String channel, String key, String payload, Map<String, String> headers) {
        LOG.debug("Publishing to Kafka topic '{}': key={}, payload={}", channel, key, payload);
        ProducerRecord<String, String> record = new ProducerRecord<>(channel, key, payload);
        if (headers != null) {
            for (Map.Entry<String, String> header : headers.entrySet()) {
                byte[] value = header.getValue() != null
                    ? header.getValue().getBytes(StandardCharsets.UTF_8) : null;
                record.headers().add(new RecordHeader(header.getKey(), value));
            }
        }
        producer.send(record);
    }

    @Override
    public void close() {
        producer.close();
    }
}
