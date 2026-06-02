package org.mockserver.async;

import org.mockserver.async.asyncapi.AsyncApiChannel;
import org.mockserver.async.asyncapi.AsyncApiSpec;
import org.mockserver.async.publish.MessagePublisher;
import org.mockserver.async.publish.PublishOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates publishing of example messages derived from an AsyncAPI spec
 * to a message broker via a {@link MessagePublisher}.
 * <p>
 * Supports one-shot publishing ({@link #publishAll()}) and scheduled
 * periodic publishing ({@link #startPublishing(long)} / {@link #stop()}).
 */
public class AsyncApiMockOrchestrator {

    private static final Logger LOG = LoggerFactory.getLogger(AsyncApiMockOrchestrator.class);

    private final AsyncApiSpec spec;
    private final MessagePublisher publisher;
    private final MessageExampleGenerator generator;
    private volatile ScheduledExecutorService scheduler;

    public AsyncApiMockOrchestrator(AsyncApiSpec spec, MessagePublisher publisher) {
        this(spec, publisher, new MessageExampleGenerator());
    }

    /**
     * Constructor for use with a custom generator (used by the control-plane implementation
     * and tests).
     */
    public AsyncApiMockOrchestrator(AsyncApiSpec spec, MessagePublisher publisher, MessageExampleGenerator generator) {
        this.spec = spec;
        this.publisher = publisher;
        this.generator = generator;
    }

    /**
     * Publish the generated example message for each channel once,
     * threading any AsyncAPI bindings (MQTT qos/retain, Kafka key) as
     * {@link PublishOptions}.
     */
    public void publishAll() {
        Map<String, String> examples = generator.generateExamples(spec);
        for (Map.Entry<String, String> entry : examples.entrySet()) {
            String channel = entry.getKey();
            String payload = entry.getValue();
            PublishOptions options = resolvePublishOptions(channel);
            LOG.info("Publishing example to channel '{}': {}", channel, payload);
            publisher.publish(channel, payload, options);
        }
    }

    /**
     * Look up the {@link AsyncApiChannel} by name and return its
     * {@link PublishOptions}. Returns {@code PublishOptions.none()} when
     * the channel is not found or has no bindings.
     */
    private PublishOptions resolvePublishOptions(String channelName) {
        for (AsyncApiChannel ch : spec.getChannels()) {
            if (ch.getName().equals(channelName)) {
                return ch.toPublishOptions();
            }
        }
        return PublishOptions.none();
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
