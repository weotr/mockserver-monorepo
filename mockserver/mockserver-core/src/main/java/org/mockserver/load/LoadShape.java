package org.mockserver.load;

import org.mockserver.model.ObjectWithJsonToString;

/**
 * A declarative, named load shape on a {@link LoadProfile}. A shape is a high-level description of a
 * common traffic pattern (a SPIKE, a flight of STAIRS, a RAMP_HOLD) that {@link LoadShapes#expand}
 * turns into the ordinary list of {@link LoadStage}s the orchestrator already runs. A profile carries
 * <em>either</em> an explicit {@link LoadProfile#getStages() stages} list <em>or</em> a {@code shape}
 * — when a shape is set and no explicit stages are present, {@link LoadProfile#getStages()} returns the
 * expanded stages, so the orchestrator needs no changes.
 *
 * <p>Each shape drives one {@link Metric}: {@link Metric#VU} (concurrent virtual users, closed model)
 * or {@link Metric#RATE} (arrival rate in iterations/second, open model). The numeric parameters are
 * interpreted per {@link Type} — only the subset each type needs is read (see each type's javadoc on
 * {@link Type}); the rest are ignored.
 *
 * <table>
 *   <caption>Parameters read per shape type</caption>
 *   <tr><th>Type</th><th>Parameters</th></tr>
 *   <tr><td>{@link Type#SPIKE}</td>
 *       <td>{@code baseline}, {@code peak}, {@code rampUpMillis}, {@code holdMillis},
 *           {@code rampDownMillis}, optional {@code recoveryHoldMillis} (hold at {@code baseline} after
 *           the down-ramp). {@code curve} shapes both ramps.</td></tr>
 *   <tr><td>{@link Type#STAIRS}</td>
 *       <td>{@code start}, {@code step}, {@code steps} (count), {@code stepDurationMillis} (each step
 *           holds at its level — pure steps, no inter-step ramp).</td></tr>
 *   <tr><td>{@link Type#RAMP_HOLD}</td>
 *       <td>{@code target}, {@code rampMillis}, {@code holdMillis}, optional {@code curve} (default
 *           {@link RampCurve#LINEAR}) — ramp {@code 0 → target} then hold {@code target}.</td></tr>
 * </table>
 */
public class LoadShape extends ObjectWithJsonToString {

    /** The kind of named shape. See {@link LoadShape} for the parameters each type reads. */
    public enum Type {
        /** Ramp baseline → peak, hold the peak, ramp peak → baseline, optional recovery hold at baseline. */
        SPIKE,
        /** A flight of {@code steps} pure holds, each at {@code start + i*step} for {@code stepDurationMillis}. */
        STAIRS,
        /** Ramp {@code 0 → target} along {@code curve}, then hold {@code target}. */
        RAMP_HOLD
    }

    /** Whether a shape drives concurrent virtual users ({@link #VU}) or an arrival rate ({@link #RATE}). */
    public enum Metric {
        /** Concurrent virtual users — expands to {@link LoadStageType#VU} stages. */
        VU,
        /** Arrival rate (iterations/second) — expands to {@link LoadStageType#RATE} stages. */
        RATE
    }

    private Type type;
    private Metric metric = Metric.VU;
    private RampCurve curve = RampCurve.LINEAR;

    // SPIKE parameters.
    private Double baseline;
    private Double peak;
    private Long rampUpMillis;
    private Long holdMillis;
    private Long rampDownMillis;
    private Long recoveryHoldMillis;

    // STAIRS parameters.
    private Double start;
    private Double step;
    private Integer steps;
    private Long stepDurationMillis;

    // RAMP_HOLD parameters.
    private Double target;
    private Long rampMillis;

    public LoadShape() {
    }

    public static LoadShape loadShape() {
        return new LoadShape();
    }

    /** A SPIKE shape (no recovery hold). */
    public static LoadShape spike(Metric metric, double baseline, double peak, long rampUpMillis, long holdMillis, long rampDownMillis) {
        return new LoadShape().withType(Type.SPIKE).withMetric(metric)
            .withBaseline(baseline).withPeak(peak)
            .withRampUpMillis(rampUpMillis).withHoldMillis(holdMillis).withRampDownMillis(rampDownMillis);
    }

    /** A STAIRS shape. */
    public static LoadShape stairs(Metric metric, double start, double step, int steps, long stepDurationMillis) {
        return new LoadShape().withType(Type.STAIRS).withMetric(metric)
            .withStart(start).withStep(step).withSteps(steps).withStepDurationMillis(stepDurationMillis);
    }

    /** A RAMP_HOLD shape using {@link RampCurve#LINEAR}. */
    public static LoadShape rampHold(Metric metric, double target, long rampMillis, long holdMillis) {
        return new LoadShape().withType(Type.RAMP_HOLD).withMetric(metric)
            .withTarget(target).withRampMillis(rampMillis).withHoldMillis(holdMillis);
    }

    public Type getType() {
        return type;
    }

    public LoadShape withType(Type type) {
        this.type = type;
        return this;
    }

    public Metric getMetric() {
        return metric;
    }

    public LoadShape withMetric(Metric metric) {
        this.metric = metric;
        return this;
    }

    public RampCurve getCurve() {
        return curve;
    }

    public LoadShape withCurve(RampCurve curve) {
        this.curve = curve;
        return this;
    }

    public Double getBaseline() {
        return baseline;
    }

    public LoadShape withBaseline(Double baseline) {
        this.baseline = baseline;
        return this;
    }

    public Double getPeak() {
        return peak;
    }

    public LoadShape withPeak(Double peak) {
        this.peak = peak;
        return this;
    }

    public Long getRampUpMillis() {
        return rampUpMillis;
    }

    public LoadShape withRampUpMillis(Long rampUpMillis) {
        this.rampUpMillis = rampUpMillis;
        return this;
    }

    public Long getHoldMillis() {
        return holdMillis;
    }

    public LoadShape withHoldMillis(Long holdMillis) {
        this.holdMillis = holdMillis;
        return this;
    }

    public Long getRampDownMillis() {
        return rampDownMillis;
    }

    public LoadShape withRampDownMillis(Long rampDownMillis) {
        this.rampDownMillis = rampDownMillis;
        return this;
    }

    public Long getRecoveryHoldMillis() {
        return recoveryHoldMillis;
    }

    public LoadShape withRecoveryHoldMillis(Long recoveryHoldMillis) {
        this.recoveryHoldMillis = recoveryHoldMillis;
        return this;
    }

    public Double getStart() {
        return start;
    }

    public LoadShape withStart(Double start) {
        this.start = start;
        return this;
    }

    public Double getStep() {
        return step;
    }

    public LoadShape withStep(Double step) {
        this.step = step;
        return this;
    }

    public Integer getSteps() {
        return steps;
    }

    public LoadShape withSteps(Integer steps) {
        this.steps = steps;
        return this;
    }

    public Long getStepDurationMillis() {
        return stepDurationMillis;
    }

    public LoadShape withStepDurationMillis(Long stepDurationMillis) {
        this.stepDurationMillis = stepDurationMillis;
        return this;
    }

    public Double getTarget() {
        return target;
    }

    public LoadShape withTarget(Double target) {
        this.target = target;
        return this;
    }

    public Long getRampMillis() {
        return rampMillis;
    }

    public LoadShape withRampMillis(Long rampMillis) {
        this.rampMillis = rampMillis;
        return this;
    }
}
