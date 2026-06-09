package org.mockserver.mock.action.http;

import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.metrics.Metrics;
import org.mockserver.time.TimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Safety circuit-breaker for service-scoped chaos: when the number of chaos-injected
 * errors within a configurable sliding window exceeds a threshold, all active
 * service-scoped chaos profiles are automatically halted (disabled) via
 * {@link ServiceChaosRegistry#reset()}.
 *
 * <p>This prevents a chaos experiment from driving a cascading outage — the "steady-state
 * guardrail" SREs expect.
 *
 * <p>The monitor is evaluated per chaos-fault injection (called from
 * {@link org.mockserver.metrics.Metrics#incrementHttpChaosInjected(String)}).
 * It does not block the event loop — the sliding window is maintained in a lock-free
 * {@link ConcurrentLinkedDeque} of timestamps.
 *
 * <p><b>Configuration</b> (all read dynamically from {@link ConfigurationProperties}):
 * <ul>
 *   <li>{@code chaosAutoHaltEnabled} — master switch (default false = inert)</li>
 *   <li>{@code chaosAutoHaltErrorThreshold} — error count to trigger halt (default 50)</li>
 *   <li>{@code chaosAutoHaltWindowMillis} — sliding window (default 60 000 ms)</li>
 * </ul>
 *
 * <p>The singleton instance is shared process-wide, consistent with
 * {@link ServiceChaosRegistry}'s singleton pattern.
 */
public class ChaosAutoHaltMonitor {

    private static final Logger LOG = LoggerFactory.getLogger(ChaosAutoHaltMonitor.class);

    private static final ChaosAutoHaltMonitor INSTANCE = new ChaosAutoHaltMonitor(TimeService::currentTimeMillis);

    private final ConcurrentLinkedDeque<Long> errorTimestamps = new ConcurrentLinkedDeque<>();
    private final LongSupplier clock;
    private final AtomicLong haltCount = new AtomicLong(0);

    ChaosAutoHaltMonitor(LongSupplier clock) {
        this.clock = clock;
    }

    public static ChaosAutoHaltMonitor getInstance() {
        return INSTANCE;
    }

    /**
     * Record a chaos-injected error and evaluate the circuit-breaker.
     * Called after each chaos fault injection (from {@code Metrics.incrementHttpChaosInjected}).
     *
     * <p>When the feature is disabled ({@code chaosAutoHaltEnabled} is false),
     * this method is a no-op — no timestamps are recorded, no evaluation occurs.
     */
    public void recordError() {
        if (!ConfigurationProperties.chaosAutoHaltEnabled()) {
            return;
        }

        long now = clock.getAsLong();
        errorTimestamps.addLast(now);
        evictExpired(now);

        long threshold = ConfigurationProperties.chaosAutoHaltErrorThreshold();
        if (threshold <= 0) {
            return;
        }

        if (errorTimestamps.size() >= threshold) {
            // Check if there is any active chaos to halt
            if (!ServiceChaosRegistry.getInstance().entries().isEmpty()) {
                haltCount.incrementAndGet();
                LOG.warn(
                    "chaos auto-halt triggered: {} errors in the last {} ms exceeded threshold of {} — "
                        + "disabling all active service-scoped chaos profiles",
                    errorTimestamps.size(),
                    ConfigurationProperties.chaosAutoHaltWindowMillis(),
                    threshold
                );
                ServiceChaosRegistry.getInstance().reset();
                Metrics.incrementChaosAutoHalt();
                // Clear the window after halt so the circuit-breaker does not
                // re-trigger immediately if new chaos is registered
                errorTimestamps.clear();
            }
        }
    }

    /**
     * Evict timestamps older than the current window from the head of the deque.
     */
    private void evictExpired(long now) {
        long windowMillis = ConfigurationProperties.chaosAutoHaltWindowMillis();
        long cutoff = now - windowMillis;
        while (true) {
            Long head = errorTimestamps.peekFirst();
            if (head == null || head > cutoff) {
                break;
            }
            errorTimestamps.pollFirst();
        }
    }

    /**
     * Returns the total number of times the auto-halt circuit-breaker has triggered
     * since the process started (or since the last {@link #reset()}).
     */
    public long getHaltCount() {
        return haltCount.get();
    }

    /**
     * Returns the number of error timestamps currently in the sliding window.
     */
    public int currentWindowSize() {
        evictExpired(clock.getAsLong());
        return errorTimestamps.size();
    }

    /**
     * Reset the monitor state. Called on server reset and for test isolation.
     */
    public void reset() {
        errorTimestamps.clear();
        haltCount.set(0);
    }
}
