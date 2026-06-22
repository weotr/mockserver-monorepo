package org.mockserver.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicReference;

public class TimeService {

    public static final Instant FIXED_INSTANT_FOR_TESTS = Instant.now();

    /**
     * Constant monotonic value returned by {@link #nanoTime()} when a test has pinned time. Any
     * constant works; both the start and end reads in a duration measurement return it, so the
     * delta is deterministically 0.
     */
    public static final long FIXED_NANOS_FOR_TESTS = System.nanoTime();

    /**
     * Thread-safe frozen instant backing the controllable clock ({@link #freeze}, {@link #advance},
     * {@link #reset}). When non-null, {@link #now()} returns this value instead of the real clock.
     * This pin is intentionally JVM-global (it models a server-wide frozen clock for chaos
     * time-outage features) and is independent of the test-only per-thread fixing below.
     */
    private static final AtomicReference<Instant> frozenInstant = new AtomicReference<>(null);

    // Test-only fixed-instant switch. A single volatile gate keeps the production hot path to one read
    // while allowing time to be pinned either per-thread (parallel-safe) or globally (for tests that
    // assert on timestamps produced on another thread; those must run in the sequential phase).
    private static volatile int fixedActiveCount = 0;
    private static final ThreadLocal<Boolean> FIXED_ON_THREAD = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static volatile boolean fixedGlobally = false;

    /**
     * Returns the current instant: the controllable-clock frozen instant if set, then
     * {@link #FIXED_INSTANT_FOR_TESTS} if a test has pinned time (per-thread or globally), otherwise
     * the real wall-clock time.
     */
    public static Instant now() {
        Instant frozen = frozenInstant.get();
        if (frozen != null) {
            return frozen;
        }
        if (fixedActiveCount != 0 && (fixedGlobally || FIXED_ON_THREAD.get())) {
            return FIXED_INSTANT_FOR_TESTS;
        }
        return Instant.now();
    }

    /**
     * Returns a monotonic nanosecond timestamp for measuring elapsed time, mirroring
     * {@link System#nanoTime()}. In production (no test pin) this returns the real
     * {@code System.nanoTime()} - monotonic semantics and overhead are unchanged (the fast path is a
     * single volatile read of {@code fixedActiveCount == 0}). When a test has pinned time (per-thread
     * via {@code org.mockserver.time.FixedTime} or globally via {@code GlobalFixedTime}), it returns
     * the constant {@link #FIXED_NANOS_FOR_TESTS}, so both the start and end reads of a duration
     * measurement are equal and the elapsed delta is deterministically 0. Unlike {@link #now()}, this
     * is NOT affected by the controllable {@link #freeze(Instant)} clock, since elapsed-time
     * measurements must remain monotonic.
     */
    public static long nanoTime() {
        if (fixedActiveCount != 0 && (fixedGlobally || FIXED_ON_THREAD.get())) {
            return FIXED_NANOS_FOR_TESTS;
        }
        return System.nanoTime();
    }

    /**
     * Returns current time in epoch milliseconds, consistent with {@link #now()}.
     */
    public static long currentTimeMillis() {
        return now().toEpochMilli();
    }

    public static OffsetDateTime offsetNow() {
        Instant now = TimeService.now();
        return OffsetDateTime.ofInstant(now, Clock.systemDefaultZone().getZone().getRules().getOffset(now));
    }

    /**
     * Freeze the clock at the given instant. If {@code instant} is null, freezes at the current real
     * time. Part of the controllable-clock API used by chaos time-outage features; JVM-global.
     */
    public static void freeze(Instant instant) {
        frozenInstant.set(instant != null ? instant : Instant.now());
    }

    /**
     * Advance the frozen clock by the given duration. If the clock is not currently frozen, it is
     * first frozen at the current real time (or the test-fixed instant if a test has pinned time),
     * then advanced.
     */
    public static void advance(Duration duration) {
        frozenInstant.updateAndGet(current -> {
            Instant base = current != null ? current : (fixedTime() ? FIXED_INSTANT_FOR_TESTS : Instant.now());
            return base.plus(duration);
        });
    }

    /**
     * Reset the controllable clock to real time (unfrozen). Does not affect test-only time pinning.
     */
    public static void reset() {
        frozenInstant.set(null);
    }

    /**
     * Returns true if the clock is currently frozen, either via the controllable clock
     * ({@link #freeze(Instant)}) or because a test has pinned time.
     */
    public static boolean isFrozen() {
        return frozenInstant.get() != null || fixedTime();
    }

    /**
     * Test-only: pin (or unpin) {@link #now()} to {@link #FIXED_INSTANT_FOR_TESTS} for the CURRENT
     * thread only, so parallel test classes do not observe each other's fixed clock. Prefer the
     * {@code org.mockserver.time.FixedTime} JUnit rule, which guarantees the reset.
     */
    public static synchronized void fixedTime(boolean fixed) {
        boolean current = FIXED_ON_THREAD.get();
        if (fixed && !current) {
            FIXED_ON_THREAD.set(Boolean.TRUE);
            fixedActiveCount++;
        } else if (!fixed && current) {
            FIXED_ON_THREAD.set(Boolean.FALSE);
            fixedActiveCount--;
        }
    }

    /**
     * Test-only: pin (or unpin) {@link #now()} for ALL threads. JVM-global and therefore NOT
     * parallel-safe - use only from tests in the sequential Surefire phase. Prefer the
     * {@code org.mockserver.time.GlobalFixedTime} JUnit rule.
     */
    public static synchronized void fixedTimeGlobally(boolean fixed) {
        if (fixed && !fixedGlobally) {
            fixedGlobally = true;
            fixedActiveCount++;
        } else if (!fixed && fixedGlobally) {
            fixedGlobally = false;
            fixedActiveCount--;
        }
    }

    public static boolean fixedTime() {
        return fixedGlobally || FIXED_ON_THREAD.get();
    }

}
