package org.mockserver.mock.action.http;

import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.metrics.Metrics;
import org.mockserver.time.TimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Safety circuit-breaker for service-scoped chaos: when the number of
 * <em>error-class</em> chaos faults (5xx synthetic errors, dropped connections,
 * and quota-limit responses) within a configurable sliding window exceeds a
 * threshold, all active service-scoped chaos profiles are automatically halted
 * (disabled) via {@link ServiceChaosRegistry#reset()}.
 *
 * <p>Only <b>destructive</b> fault types contribute to the window:
 * {@code "error"} (synthetic 5xx), {@code "drop"} (connection kill), and
 * {@code "quota"} (429/503). Benign fault types such as {@code "latency"},
 * {@code "slow"}, {@code "truncate"}, {@code "malformed"}, and
 * {@code "graphql"} do not count — a latency-only experiment will never
 * auto-halt, which matches the circuit-breaker's purpose.
 *
 * <p>This prevents a chaos experiment from driving a cascading outage — the
 * "steady-state guardrail" SREs expect.
 *
 * <p>The monitor is evaluated per chaos-fault injection (called from
 * {@link org.mockserver.metrics.Metrics#incrementHttpChaosInjected(String)}).
 * It does not block the event loop — the sliding window is maintained in a
 * lock-free {@link ConcurrentLinkedDeque} of timestamps.
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

    /**
     * Only destructive fault types contribute to the auto-halt window:
     * synthetic 5xx errors, dropped connections, and quota-limit responses.
     * Benign faults (latency, slow, truncate, malformed, graphql) are excluded.
     */
    static final Set<String> DESTRUCTIVE_FAULT_TYPES = Set.of("error", "drop", "quota");

    private static final ChaosAutoHaltMonitor INSTANCE = new ChaosAutoHaltMonitor(TimeService::currentTimeMillis);

    private final ConcurrentLinkedDeque<Long> errorTimestamps = new ConcurrentLinkedDeque<>();
    private final LongSupplier clock;
    private final AtomicLong haltCount = new AtomicLong(0);
    /** Guards against concurrent double-trigger: only one thread performs the halt block per trigger. */
    private final AtomicBoolean halting = new AtomicBoolean(false);
    /**
     * Lock that serializes the evict-then-check-threshold critical section.
     * Without this, two concurrent {@code recordError()} threads can both
     * {@code peekFirst()} the same expired head; the loser's {@code pollFirst()}
     * removes an <em>unexpired</em> entry, permanently undercounting the window
     * and preventing the circuit breaker from firing (TOCTOU race).
     */
    private final Object evictLock = new Object();

    ChaosAutoHaltMonitor(LongSupplier clock) {
        this.clock = clock;
    }

    public static ChaosAutoHaltMonitor getInstance() {
        return INSTANCE;
    }

    /**
     * Record a chaos-injected fault and evaluate the circuit-breaker.
     * Called after each chaos fault injection (from {@code Metrics.incrementHttpChaosInjected}).
     *
     * <p>Only <b>destructive</b> fault types ({@code "error"}, {@code "drop"},
     * {@code "quota"}) contribute to the sliding window. Benign faults
     * ({@code "latency"}, {@code "slow"}, {@code "truncate"},
     * {@code "malformed"}, {@code "graphql"}) are ignored — a latency-only
     * experiment will never auto-halt.
     *
     * <p>When the feature is disabled ({@code chaosAutoHaltEnabled} is false),
     * this method is a no-op — no timestamps are recorded, no evaluation occurs.
     *
     * @param faultType the fault type string (e.g. "error", "drop", "latency")
     */
    public void recordError(String faultType) {
        if (!ConfigurationProperties.chaosAutoHaltEnabled()) {
            return;
        }

        // Only destructive fault types contribute to the auto-halt window
        if (faultType == null || !DESTRUCTIVE_FAULT_TYPES.contains(faultType)) {
            return;
        }

        long threshold = ConfigurationProperties.chaosAutoHaltErrorThreshold();
        if (threshold <= 0) {
            // With a non-positive threshold the circuit-breaker can never fire,
            // so skip recording to avoid unbounded timestamp accumulation.
            return;
        }

        long now = clock.getAsLong();
        errorTimestamps.addLast(now);

        // Evict expired entries and read the window size under the same lock to
        // prevent the TOCTOU race where two threads both peek the same expired
        // head and one of them polls an unexpired entry instead.
        int currentSize;
        synchronized (evictLock) {
            evictExpired(now);
            currentSize = errorTimestamps.size();
        }

        if (currentSize >= threshold) {
            // AtomicBoolean guard: only one thread performs the halt block
            if (halting.compareAndSet(false, true)) {
                try {
                    // Re-check after acquiring the guard (another thread may have cleared the window)
                    int recheck;
                    synchronized (evictLock) {
                        recheck = errorTimestamps.size();
                    }
                    boolean serviceChaosActive = !ServiceChaosRegistry.getInstance().entries().isEmpty();
                    boolean tcpChaosActive = TcpChaosRegistry.getInstance().activeCount() > 0;
                    if (recheck >= threshold && (serviceChaosActive || tcpChaosActive)) {
                        haltCount.incrementAndGet();
                        LOG.warn(
                            "chaos auto-halt triggered: {} error-class faults (5xx/dropped/quota/connection-lifecycle RST) in the last {} ms "
                                + "exceeded threshold of {} — disabling all active service-scoped and TCP-layer chaos profiles",
                            recheck,
                            ConfigurationProperties.chaosAutoHaltWindowMillis(),
                            threshold
                        );
                        ServiceChaosRegistry.getInstance().reset();
                        // Connection-lifecycle faults (mid-response RST, host slow-close, HTTP/2 GOAWAY)
                        // live in the TcpChaosRegistry, so the halt must also clear it — otherwise a
                        // RST storm driven by a TCP-layer profile would keep firing after the breaker
                        // trips. The mid-response RST records a "drop" fault here, so this is the
                        // path that stops a lifecycle RST storm.
                        TcpChaosRegistry.getInstance().reset();
                        Metrics.incrementChaosAutoHalt();
                        // Clear the window after halt so the circuit-breaker does not
                        // re-trigger immediately if new chaos is registered
                        errorTimestamps.clear();
                    }
                } finally {
                    halting.set(false);
                }
            }
        }
    }

    /**
     * Evict timestamps older than the current window from the head of the deque.
     * <p><b>Must be called while holding {@code evictLock}</b> so the peek-then-poll
     * sequence is atomic with respect to other threads doing the same eviction.
     *
     * @return the number of evicted entries
     */
    private int evictExpired(long now) {
        long windowMillis = ConfigurationProperties.chaosAutoHaltWindowMillis();
        long cutoff = now - windowMillis;
        int evicted = 0;
        while (true) {
            Long head = errorTimestamps.peekFirst();
            if (head == null || head > cutoff) {
                break;
            }
            if (errorTimestamps.pollFirst() != null) {
                evicted++;
            }
        }
        return evicted;
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
        synchronized (evictLock) {
            evictExpired(clock.getAsLong());
            return errorTimestamps.size();
        }
    }

    /**
     * Reset the monitor state. Called on server reset and for test isolation.
     */
    public void reset() {
        errorTimestamps.clear();
        haltCount.set(0);
        halting.set(false);
    }
}
