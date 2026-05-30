package org.mockserver.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicReference;

public class TimeService {

    public static final Instant FIXED_INSTANT_FOR_TESTS = Instant.now();

    /**
     * Thread-safe frozen instant. When non-null, {@link #now()} returns this value
     * instead of the real clock.
     */
    private static final AtomicReference<Instant> frozenInstant = new AtomicReference<>(null);

    /**
     * Backward-compatible flag. Setting {@code fixedTime = true} freezes the clock
     * at {@link #FIXED_INSTANT_FOR_TESTS}; setting it to {@code false} resets to real time.
     */
    public static boolean fixedTime;

    static {
        fixedTime = false;
    }

    /**
     * Returns the current instant: frozen instant if set, {@code FIXED_INSTANT_FOR_TESTS}
     * if {@code fixedTime} is true, or the real wall-clock time.
     */
    public static Instant now() {
        Instant frozen = frozenInstant.get();
        if (frozen != null) {
            return frozen;
        }
        if (fixedTime) {
            return FIXED_INSTANT_FOR_TESTS;
        }
        return Instant.now();
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
     * Freeze the clock at the given instant. If {@code instant} is null, freezes at
     * the current real time.
     */
    public static void freeze(Instant instant) {
        frozenInstant.set(instant != null ? instant : Instant.now());
    }

    /**
     * Advance the frozen clock by the given duration. If the clock is not currently
     * frozen, it is first frozen at the current real time, then advanced.
     */
    public static void advance(Duration duration) {
        frozenInstant.updateAndGet(current -> {
            Instant base = current != null ? current : (fixedTime ? FIXED_INSTANT_FOR_TESTS : Instant.now());
            return base.plus(duration);
        });
    }

    /**
     * Reset the clock to real time (unfrozen).
     */
    public static void reset() {
        frozenInstant.set(null);
    }

    /**
     * Returns true if the clock is currently frozen (either via {@link #freeze(Instant)}
     * or via the legacy {@code fixedTime} flag).
     */
    public static boolean isFrozen() {
        return frozenInstant.get() != null || fixedTime;
    }

}
