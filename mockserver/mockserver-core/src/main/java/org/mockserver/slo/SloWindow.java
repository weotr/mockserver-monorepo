package org.mockserver.slo;

import org.mockserver.model.ObjectWithJsonToString;

/**
 * The time window over which an {@link SloCriteria} is evaluated. Two mutually
 * exclusive forms are supported (v1):
 *
 * <ul>
 *   <li>{@link Type#EXPLICIT} — an absolute {@code [fromEpochMillis, toEpochMillis]}
 *       range. Both bounds are required.</li>
 *   <li>{@link Type#LOOKBACK} — a trailing window of {@code lookbackMillis}
 *       ending at evaluation time ({@code now - lookbackMillis} up to {@code now}),
 *       resolved by the {@link SloEvaluator} against the controllable clock.</li>
 * </ul>
 *
 * <p>The window is always already-elapsed: the evaluator never blocks waiting for
 * the window to close. An EXPERIMENT-relative window is intentionally deferred.
 */
public class SloWindow extends ObjectWithJsonToString {

    public enum Type {
        EXPLICIT,
        LOOKBACK
    }

    private Type type;
    private Long fromEpochMillis;
    private Long toEpochMillis;
    private Long lookbackMillis;

    public static SloWindow explicit(long fromEpochMillis, long toEpochMillis) {
        return new SloWindow()
            .withType(Type.EXPLICIT)
            .withFromEpochMillis(fromEpochMillis)
            .withToEpochMillis(toEpochMillis);
    }

    public static SloWindow lookback(long lookbackMillis) {
        return new SloWindow()
            .withType(Type.LOOKBACK)
            .withLookbackMillis(lookbackMillis);
    }

    public Type getType() {
        return type;
    }

    public SloWindow withType(Type type) {
        this.type = type;
        return this;
    }

    public Long getFromEpochMillis() {
        return fromEpochMillis;
    }

    public SloWindow withFromEpochMillis(Long fromEpochMillis) {
        this.fromEpochMillis = fromEpochMillis;
        return this;
    }

    public Long getToEpochMillis() {
        return toEpochMillis;
    }

    public SloWindow withToEpochMillis(Long toEpochMillis) {
        this.toEpochMillis = toEpochMillis;
        return this;
    }

    public Long getLookbackMillis() {
        return lookbackMillis;
    }

    public SloWindow withLookbackMillis(Long lookbackMillis) {
        this.lookbackMillis = lookbackMillis;
        return this;
    }
}
