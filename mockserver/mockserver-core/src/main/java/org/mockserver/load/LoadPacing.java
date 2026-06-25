package org.mockserver.load;

import org.mockserver.model.ObjectWithJsonToString;

/**
 * Adaptive iteration pacing (think-time) for a {@link LoadScenario}: a target per-virtual-user
 * iteration <em>cycle</em> time. After a closed-model VU finishes one pass through the steps, the
 * orchestrator waits whatever remains of the target cycle before launching that VU's next iteration;
 * if the iteration's work overran the cycle, the next iteration starts immediately (no wait). This
 * smooths a closed-model VU loop toward a target per-VU iteration rate (Locust
 * {@code constant_pacing}/{@code constant_throughput}, Gatling {@code pace}).
 *
 * <p><b>Scope:</b> pacing governs only the closed-model VU loop ({@code looping} iterations). One-shot
 * open-model RATE iterations ignore pacing — their arrival rate already governs the spacing between
 * starts. Pacing composes <em>with</em> (it does not replace) per-step {@link LoadStep#getThinkTime()
 * thinkTime}: thinkTime adds intra-iteration pauses between steps; pacing targets the whole
 * iteration cycle and only delays the start of the NEXT iteration. Pacing never alters in-flight
 * latency measurement (the coordinated-omission-corrected sample is unchanged) — it only changes
 * <em>when</em> the next iteration launches.
 *
 * @see LoadScenario#getPacing()
 */
public class LoadPacing extends ObjectWithJsonToString {

    /**
     * How the target iteration cycle is derived from {@link #value}.
     *
     * <ul>
     *   <li>{@link #NONE} — no pacing (the default); the next iteration launches immediately, exactly
     *       as if no pacing were configured.</li>
     *   <li>{@link #CONSTANT_PACING} — {@code value} is the target cycle in <b>milliseconds</b>; a VU
     *       starts an iteration at most once per {@code value} ms.</li>
     *   <li>{@link #CONSTANT_THROUGHPUT} — {@code value} is the target iterations/second <b>per VU</b>;
     *       the cycle is {@code 1000 / value} ms.</li>
     * </ul>
     */
    public enum Mode {
        NONE,
        CONSTANT_PACING,
        CONSTANT_THROUGHPUT
    }

    private Mode mode = Mode.NONE;
    /**
     * For {@link Mode#CONSTANT_PACING} the target cycle in milliseconds; for
     * {@link Mode#CONSTANT_THROUGHPUT} the target iterations/second per VU. Ignored when
     * {@link #mode} is {@link Mode#NONE}. Must be {@code > 0} when the mode is not NONE.
     */
    private double value;

    public static LoadPacing loadPacing() {
        return new LoadPacing();
    }

    /** Convenience: a constant-pacing target cycle in milliseconds. */
    public static LoadPacing constantPacing(double cycleMillis) {
        return new LoadPacing().withMode(Mode.CONSTANT_PACING).withValue(cycleMillis);
    }

    /** Convenience: a constant-throughput target of {@code iterationsPerSecond} per VU. */
    public static LoadPacing constantThroughput(double iterationsPerSecond) {
        return new LoadPacing().withMode(Mode.CONSTANT_THROUGHPUT).withValue(iterationsPerSecond);
    }

    public Mode getMode() {
        return mode;
    }

    public LoadPacing withMode(Mode mode) {
        this.mode = mode;
        return this;
    }

    public double getValue() {
        return value;
    }

    public LoadPacing withValue(double value) {
        this.value = value;
        return this;
    }

    /**
     * The target iteration cycle in milliseconds derived from {@link #mode} and {@link #value}, or
     * {@code 0} when there is no pacing ({@link Mode#NONE}, a null mode, or a non-positive value).
     * {@link Mode#CONSTANT_PACING} returns {@code value}; {@link Mode#CONSTANT_THROUGHPUT} returns
     * {@code 1000 / value}.
     */
    public double cycleMillis() {
        if (mode == null || mode == Mode.NONE || value <= 0) {
            return 0;
        }
        switch (mode) {
            case CONSTANT_PACING:
                return value;
            case CONSTANT_THROUGHPUT:
                return 1000.0 / value;
            default:
                return 0;
        }
    }
}
