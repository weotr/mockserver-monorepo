package org.mockserver.load;

import java.util.ArrayList;
import java.util.List;

/**
 * Expands a declarative {@link LoadShape} into the ordinary list of {@link LoadStage}s the
 * orchestrator runs. This is the single source of truth for shape → stage expansion; it is pure and
 * side-effect-free (it only reads the shape and builds stages with the existing {@link LoadStage}
 * builders), so {@link LoadProfile#getStages()} can call it lazily and the result is unit-testable
 * without driving traffic.
 *
 * <p>Design choices (documented because they are observable in the expansion):
 * <ul>
 *   <li><b>STAIRS are pure steps</b> — each step is a single constant hold at {@code start + i*step};
 *       there is no inter-step ramp (a flight of stairs is discrete by definition, and pure holds are
 *       the simplest faithful expansion).</li>
 *   <li><b>RATE expansions leave {@code maxVus} unset</b> on every stage, so the global
 *       {@code loadGenerationMaxVirtualUsers} cap governs the auto-scaled VU pool. A shape does not
 *       impose its own (lower) per-stage VU cap — pick the safe option that never silently throttles
 *       below the configured global limit.</li>
 *   <li><b>VU setpoints are integers</b> — VU parameters are rounded to the nearest whole VU when the
 *       shape is built into VU stages; RATE parameters stay fractional iterations/second.</li>
 * </ul>
 */
public final class LoadShapes {

    private LoadShapes() {
    }

    /**
     * Expand {@code shape} into its ordered list of {@link LoadStage}s, or an empty list when
     * {@code shape} is {@code null} or has no {@link LoadShape#getType() type}. The returned list is a
     * fresh, mutable {@link ArrayList} owned by the caller.
     */
    public static List<LoadStage> expand(LoadShape shape) {
        List<LoadStage> stages = new ArrayList<>();
        if (shape == null || shape.getType() == null) {
            return stages;
        }
        switch (shape.getType()) {
            case SPIKE:
                expandSpike(shape, stages);
                break;
            case STAIRS:
                expandStairs(shape, stages);
                break;
            case RAMP_HOLD:
                expandRampHold(shape, stages);
                break;
            default:
                break;
        }
        return stages;
    }

    private static void expandSpike(LoadShape shape, List<LoadStage> stages) {
        double baseline = orZero(shape.getBaseline());
        double peak = orZero(shape.getPeak());
        long rampUp = orZero(shape.getRampUpMillis());
        long hold = orZero(shape.getHoldMillis());
        long rampDown = orZero(shape.getRampDownMillis());
        long recovery = orZero(shape.getRecoveryHoldMillis());
        RampCurve curve = curveOf(shape);

        if (rampUp > 0) {
            stages.add(ramp(shape, baseline, peak, rampUp, curve));
        }
        if (hold > 0) {
            stages.add(hold(shape, peak, hold));
        }
        if (rampDown > 0) {
            stages.add(ramp(shape, peak, baseline, rampDown, curve));
        }
        if (recovery > 0) {
            stages.add(hold(shape, baseline, recovery));
        }
    }

    private static void expandStairs(LoadShape shape, List<LoadStage> stages) {
        double start = orZero(shape.getStart());
        double step = orZero(shape.getStep());
        int steps = shape.getSteps() != null ? shape.getSteps() : 0;
        long stepDuration = orZero(shape.getStepDurationMillis());

        for (int i = 0; i < steps; i++) {
            if (stepDuration > 0) {
                stages.add(hold(shape, start + i * step, stepDuration));
            }
        }
    }

    private static void expandRampHold(LoadShape shape, List<LoadStage> stages) {
        double target = orZero(shape.getTarget());
        long ramp = orZero(shape.getRampMillis());
        long hold = orZero(shape.getHoldMillis());
        RampCurve curve = curveOf(shape);

        if (ramp > 0) {
            stages.add(ramp(shape, 0.0, target, ramp, curve));
        }
        if (hold > 0) {
            stages.add(hold(shape, target, hold));
        }
    }

    /** A constant-setpoint stage at {@code value} for the shape's metric. */
    private static LoadStage hold(LoadShape shape, double value, long durationMillis) {
        if (shape.getMetric() == LoadShape.Metric.RATE) {
            return LoadStage.constantRate(value, durationMillis);
        }
        return LoadStage.constantVus((int) Math.round(value), durationMillis);
    }

    /** A ramp stage from {@code start} to {@code end} along {@code curve} for the shape's metric. */
    private static LoadStage ramp(LoadShape shape, double start, double end, long durationMillis, RampCurve curve) {
        if (shape.getMetric() == LoadShape.Metric.RATE) {
            return LoadStage.rampRate(start, end, durationMillis, curve);
        }
        return LoadStage.rampVus((int) Math.round(start), (int) Math.round(end), durationMillis, curve);
    }

    private static RampCurve curveOf(LoadShape shape) {
        return shape.getCurve() != null ? shape.getCurve() : RampCurve.LINEAR;
    }

    private static double orZero(Double value) {
        return value != null ? value : 0.0;
    }

    private static long orZero(Long value) {
        return value != null ? value : 0L;
    }
}
