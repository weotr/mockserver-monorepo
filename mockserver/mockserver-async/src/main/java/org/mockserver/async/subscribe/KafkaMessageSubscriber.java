package org.mockserver.async.subscribe;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.mockserver.async.security.KafkaSecurity;
import org.mockserver.async.security.KafkaSecurityProperties;
import org.mockserver.metrics.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A {@link MessageSubscriber} that uses a Kafka {@link KafkaConsumer} to subscribe
 * to topics and record received messages. Runs a background poll loop on a daemon
 * thread.
 * <p>
 * All {@link KafkaConsumer} access is confined to the single poll thread to avoid
 * {@link java.util.ConcurrentModificationException}. Subscribe and unsubscribe
 * requests are queued and drained at the top of each poll iteration.
 * <p>
 * Recorded messages are stored in a bounded {@link BoundedMessageStore} per channel
 * to prevent unbounded memory growth.
 */
public class KafkaMessageSubscriber implements MessageSubscriber {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaMessageSubscriber.class);
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(100);
    private static final int MAX_LOG_PAYLOAD_LENGTH = 100;

    private final KafkaConsumer<String, String> consumer;
    private final ConcurrentMap<String, BoundedMessageStore> recordedMessages = new ConcurrentHashMap<>();
    private final int maxRecordedMessages;

    /**
     * The desired set of topics. Updated from any thread; the poll thread reads this
     * after draining pending ops to build the actual consumer subscription.
     */
    private final Set<String> desiredTopics = ConcurrentHashMap.newKeySet();

    /**
     * Queue of pending subscribe/unsubscribe operations. The poll thread drains this
     * at the top of each iteration and re-subscribes the consumer to the current
     * desired topic set. This ensures all KafkaConsumer API calls happen on the
     * poll thread.
     */
    private final ConcurrentLinkedQueue<Runnable> pendingOps = new ConcurrentLinkedQueue<>();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService pollExecutor;

    /**
     * Create a subscriber connected to the given Kafka bootstrap servers
     * using plaintext (no security). Backward-compatible entry point.
     *
     * @param bootstrapServers comma-separated list of host:port pairs
     * @param groupId          the consumer group ID
     */
    public KafkaMessageSubscriber(String bootstrapServers, String groupId) {
        this(bootstrapServers, groupId, BoundedMessageStore.DEFAULT_MAX_RECORDED_MESSAGES);
    }

    /**
     * Create a subscriber with a custom recorded-message cap per channel.
     */
    public KafkaMessageSubscriber(String bootstrapServers, String groupId, int maxRecordedMessages) {
        this(bootstrapServers, groupId, maxRecordedMessages, null);
    }

    /**
     * Create a subscriber with optional security configuration.
     *
     * @param bootstrapServers comma-separated list of host:port pairs
     * @param groupId          the consumer group ID
     * @param maxRecordedMessages maximum recorded messages per channel
     * @param security         security configuration (may be null for plaintext)
     */
    public KafkaMessageSubscriber(String bootstrapServers, String groupId,
                                  int maxRecordedMessages, KafkaSecurity security) {
        Properties props = buildConsumerProperties(bootstrapServers, groupId, security);
        this.consumer = new KafkaConsumer<>(props);
        this.maxRecordedMessages = maxRecordedMessages;
        this.pollExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "async-kafka-subscriber");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Package-private constructor for injecting a mock consumer in tests.
     */
    KafkaMessageSubscriber(KafkaConsumer<String, String> consumer) {
        this(consumer, BoundedMessageStore.DEFAULT_MAX_RECORDED_MESSAGES);
    }

    /**
     * Package-private constructor for injecting a mock consumer with custom cap in tests.
     */
    KafkaMessageSubscriber(KafkaConsumer<String, String> consumer, int maxRecordedMessages) {
        this.consumer = consumer;
        this.maxRecordedMessages = maxRecordedMessages;
        this.pollExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "async-kafka-subscriber-test");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Build the Kafka consumer properties with security applied.
     * Package-private for direct unit-testing of the property assembly.
     *
     * @param bootstrapServers comma-separated list of host:port pairs
     * @param groupId          the consumer group ID
     * @param security         security configuration (may be null)
     * @return the fully configured properties
     */
    static Properties buildConsumerProperties(String bootstrapServers, String groupId,
                                              KafkaSecurity security) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        KafkaSecurityProperties.applySecurity(props, security);
        return props;
    }

    @Override
    public void subscribe(String channel) {
        desiredTopics.add(channel);
        recordedMessages.putIfAbsent(channel, new BoundedMessageStore(maxRecordedMessages));
        // Queue an op so the poll thread re-subscribes to the updated topic set
        pendingOps.add(() -> consumer.subscribe(new ArrayList<>(desiredTopics)));
        ensurePollLoopRunning();
        LOG.info("Subscribed to Kafka topic '{}'", channel);
    }

    @Override
    public void unsubscribe(String channel) {
        desiredTopics.remove(channel);
        if (desiredTopics.isEmpty()) {
            pendingOps.add(consumer::unsubscribe);
        } else {
            pendingOps.add(() -> consumer.subscribe(new ArrayList<>(desiredTopics)));
        }
        LOG.info("Unsubscribed from Kafka topic '{}'", channel);
    }

    @Override
    public List<RecordedMessage> getRecordedMessages(String channel) {
        BoundedMessageStore store = recordedMessages.get(channel);
        return store != null ? Collections.unmodifiableList(store.snapshot()) : Collections.emptyList();
    }

    @Override
    public List<RecordedMessage> getAllRecordedMessages() {
        List<RecordedMessage> all = new ArrayList<>();
        recordedMessages.values().forEach(store -> all.addAll(store.snapshot()));
        all.sort(Comparator.comparing(RecordedMessage::getTimestamp));
        return Collections.unmodifiableList(all);
    }

    @Override
    public void close() {
        // Signal the poll loop to stop
        running.set(false);
        // consumer.wakeup() is the only thread-safe KafkaConsumer method — breaks poll()
        consumer.wakeup();
        // Wait for the poll thread to finish (it will close the consumer in its finally block)
        pollExecutor.shutdown();
        try {
            if (!pollExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                pollExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            pollExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        recordedMessages.clear();
        desiredTopics.clear();
        pendingOps.clear();
    }

    /**
     * Start the poll loop exactly once using {@link AtomicBoolean#compareAndSet}.
     */
    private void ensurePollLoopRunning() {
        if (running.compareAndSet(false, true)) {
            pollExecutor.submit(this::pollLoop);
        }
    }

    private void pollLoop() {
        try {
            while (running.get()) {
                // Drain all pending subscribe/unsubscribe ops (executed on this thread)
                Runnable op;
                while ((op = pendingOps.poll()) != null) {
                    op.run();
                }

                ConsumerRecords<String, String> records = consumer.poll(POLL_TIMEOUT);
                for (ConsumerRecord<String, String> record : records) {
                    Map<String, String> headers = new LinkedHashMap<>();
                    for (Header header : record.headers()) {
                        headers.put(header.key(),
                            header.value() != null ? new String(header.value(), StandardCharsets.UTF_8) : null);
                    }
                    RecordedMessage msg = new RecordedMessage(
                        record.topic(),
                        record.key(),
                        record.value(),
                        headers
                    );
                    recordedMessages.computeIfAbsent(record.topic(),
                        k -> new BoundedMessageStore(maxRecordedMessages)).add(msg);
                    Metrics.incrementAsyncMessageConsumed(record.topic());
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Recorded message from Kafka topic '{}': key={}, value={}",
                            record.topic(), record.key(), truncate(record.value()));
                    }
                }
            }
        } catch (WakeupException e) {
            // Expected on close() — consumer.wakeup() causes this
            if (running.get()) {
                LOG.error("Unexpected WakeupException in Kafka poll loop", e);
            }
        } catch (Exception e) {
            if (running.get()) {
                LOG.error("Kafka poll loop terminated unexpectedly: {}", e.getMessage(), e);
            }
        } finally {
            // Close the consumer on the poll thread — the only safe place to do so
            try {
                consumer.close(Duration.ofSeconds(5));
            } catch (Exception e) {
                LOG.warn("Error closing Kafka consumer: {}", e.getMessage());
            }
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return "null";
        }
        return value.length() <= MAX_LOG_PAYLOAD_LENGTH
            ? value
            : value.substring(0, MAX_LOG_PAYLOAD_LENGTH) + "...(" + value.length() + " chars)";
    }
}
