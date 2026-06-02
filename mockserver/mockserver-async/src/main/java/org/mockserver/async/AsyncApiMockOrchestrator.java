package org.mockserver.async;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.async.asyncapi.AsyncApiChannel;
import org.mockserver.async.asyncapi.AsyncApiMessage;
import org.mockserver.async.asyncapi.AsyncApiSpec;
import org.mockserver.async.publish.MessagePublisher;
import org.mockserver.async.publish.PublishOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Orchestrates publishing of example messages derived from an AsyncAPI spec
 * to a message broker via a {@link MessagePublisher}.
 * <p>
 * Supports one-shot publishing ({@link #publishAll()}) and scheduled
 * periodic publishing ({@link #startPublishing(long)} / {@link #stop()}).
 * <p>
 * When a message defines a {@code correlationId.location}, the orchestrator
 * generates a unique correlation ID at publish time and injects it at the
 * specified location (header or payload JSON Pointer).
 */
public class AsyncApiMockOrchestrator {

    private static final Logger LOG = LoggerFactory.getLogger(AsyncApiMockOrchestrator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String HEADER_LOCATION_PREFIX = "$message.header#/";
    private static final String PAYLOAD_LOCATION_PREFIX = "$message.payload#";

    private final AsyncApiSpec spec;
    private final MessagePublisher publisher;
    private final MessageExampleGenerator generator;
    private volatile ScheduledExecutorService scheduler;
    private final Supplier<String> correlationIdSupplier;

    public AsyncApiMockOrchestrator(AsyncApiSpec spec, MessagePublisher publisher) {
        this(spec, publisher, new MessageExampleGenerator());
    }

    /**
     * Constructor for use with a custom generator (used by the control-plane implementation
     * and tests).
     */
    public AsyncApiMockOrchestrator(AsyncApiSpec spec, MessagePublisher publisher, MessageExampleGenerator generator) {
        this(spec, publisher, generator, () -> UUID.randomUUID().toString());
    }

    /**
     * Full constructor with injectable correlation-ID supplier (used by tests to pin the ID).
     */
    AsyncApiMockOrchestrator(AsyncApiSpec spec, MessagePublisher publisher,
                             MessageExampleGenerator generator, Supplier<String> correlationIdSupplier) {
        this.spec = spec;
        this.publisher = publisher;
        this.generator = generator;
        this.correlationIdSupplier = correlationIdSupplier;
    }

    /**
     * Publish the generated example message for each message in each channel,
     * threading any AsyncAPI bindings (MQTT qos/retain, Kafka key) and
     * correlation IDs as {@link PublishOptions}.
     * <p>
     * Multi-message channels (v3 multiple {@code messages}, v2 {@code oneOf})
     * result in one publish call per message. Single-message channels behave
     * identically to the previous single-publish behavior.
     */
    public void publishAll() {
        for (AsyncApiChannel ch : spec.getChannels()) {
            List<AsyncApiMessage> messages = ch.getMessages();
            for (AsyncApiMessage msg : messages) {
                String payload = generator.generateExample(msg);
                if (payload == null || payload.isBlank()) {
                    continue;
                }

                Map<String, String> correlationHeaders = null;
                String correlationIdLocation = msg.getCorrelationIdLocation();
                if (correlationIdLocation != null) {
                    String correlationId = correlationIdSupplier.get();
                    if (correlationIdLocation.startsWith(HEADER_LOCATION_PREFIX)) {
                        String headerName = correlationIdLocation.substring(HEADER_LOCATION_PREFIX.length());
                        if (!headerName.isEmpty()) {
                            correlationHeaders = new LinkedHashMap<>();
                            correlationHeaders.put(headerName, correlationId);
                        }
                    } else if (correlationIdLocation.startsWith(PAYLOAD_LOCATION_PREFIX)) {
                        String pointerStr = correlationIdLocation.substring(PAYLOAD_LOCATION_PREFIX.length());
                        payload = injectIntoPayload(payload, pointerStr, correlationId);
                    } else {
                        LOG.debug("Unrecognised correlationId location prefix '{}'; skipping injection",
                            correlationIdLocation);
                    }
                }

                PublishOptions options = buildPublishOptions(ch, msg, correlationHeaders);
                LOG.info("Publishing example to channel '{}': {}", ch.getName(), payload);
                publisher.publish(ch.getName(), payload, options);
            }
        }
    }

    /**
     * Inject a value into a JSON payload at the given JSON Pointer path.
     * Creates intermediate objects when needed. Returns the original payload
     * unchanged if it is not valid JSON or the pointer is empty.
     */
    private String injectIntoPayload(String payload, String pointerStr, String value) {
        if (pointerStr == null || pointerStr.isEmpty() || pointerStr.equals("/")) {
            LOG.debug("Empty or root-only JSON Pointer for correlation ID; skipping payload injection");
            return payload;
        }
        try {
            JsonNode root = MAPPER.readTree(payload);
            if (!(root instanceof ObjectNode)) {
                LOG.debug("Payload is not a JSON object; skipping correlation ID injection");
                return payload;
            }
            JsonPointer pointer = JsonPointer.compile(pointerStr);
            setValueAtPointer((ObjectNode) root, pointer, value);
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            LOG.debug("Failed to inject correlation ID into payload: {}", e.getMessage());
            return payload;
        }
    }

    /**
     * Set a text value at the given JSON Pointer path in an ObjectNode tree,
     * creating intermediate ObjectNode containers as needed.
     */
    private void setValueAtPointer(ObjectNode root, JsonPointer pointer, String value) {
        // Walk the pointer segments, creating intermediate objects
        JsonPointer head = pointer.head();
        String lastSegment = pointer.last().getMatchingProperty();

        if (head == null || head.matches()) {
            // Single-level pointer: set directly on root
            root.put(lastSegment, value);
            return;
        }

        // Multi-level pointer: navigate/create intermediate nodes
        ObjectNode current = root;
        // Collect all segments except the last
        JsonPointer remaining = pointer;
        java.util.List<String> segments = new java.util.ArrayList<>();
        while (remaining != null && !remaining.matches()) {
            segments.add(remaining.getMatchingProperty());
            remaining = remaining.tail();
        }

        // Navigate to the parent of the last segment, creating intermediates
        for (int i = 0; i < segments.size() - 1; i++) {
            String seg = segments.get(i);
            JsonNode child = current.get(seg);
            if (child instanceof ObjectNode) {
                current = (ObjectNode) child;
            } else {
                ObjectNode newNode = MAPPER.createObjectNode();
                current.set(seg, newNode);
                current = newNode;
            }
        }

        // Set the value at the final segment
        current.put(segments.get(segments.size() - 1), value);
    }

    /**
     * Build {@link PublishOptions} from per-message Kafka key, channel-level
     * MQTT qos/retain bindings, and optional correlation-ID headers.
     */
    private PublishOptions buildPublishOptions(AsyncApiChannel channel, AsyncApiMessage message,
                                               Map<String, String> correlationHeaders) {
        String kafkaKey = message.getKafkaKey();
        Integer mqttQos = channel.getMqttQos();
        Boolean mqttRetain = channel.getMqttRetain();
        boolean hasCorrelationHeaders = correlationHeaders != null && !correlationHeaders.isEmpty();
        if (kafkaKey == null && mqttQos == null && mqttRetain == null && !hasCorrelationHeaders) {
            return PublishOptions.none();
        }
        return new PublishOptions(kafkaKey, mqttQos, mqttRetain, correlationHeaders);
    }

    /**
     * Start periodic publishing at the given interval.
     *
     * @param intervalMillis the interval between publish cycles in milliseconds
     */
    public void startPublishing(long intervalMillis) {
        if (scheduler != null && !scheduler.isShutdown()) {
            LOG.warn("Scheduled publishing already running; stop() first");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "async-mock-publisher");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::publishAll, 0, intervalMillis, TimeUnit.MILLISECONDS);
        LOG.info("Started scheduled publishing every {} ms", intervalMillis);
    }

    /**
     * Stop periodic publishing.
     */
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
            LOG.info("Stopped scheduled publishing");
        }
    }
}
