package org.mockserver.load;

import org.mockserver.model.ObjectWithJsonToString;

/**
 * One stage of a {@link LoadProfile}: a contiguous slice of the run holding or ramping a setpoint
 * for {@code durationMillis}. Stages run in sequence; the total run ends after the last stage
 * (or when {@code maxRequests} is hit, or on stop).
 *
 * <p>A stage is one of three {@link LoadStageType}s:
 * <ul>
 *   <li>{@link LoadStageType#VU} — closed model. Either holds {@code vus} or ramps from
 *       {@code startVus} to {@code endVus} along the {@link #curve}.</li>
 *   <li>{@link LoadStageType#RATE} — open model. Either holds {@code rate} or ramps from
 *       {@code startRate} to {@code endRate} (iterations/second) along the {@link #curve}, optionally
 *       capped at {@code maxVus} virtual users for this stage (else the global VU cap).</li>
 *   <li>{@link LoadStageType#PAUSE} — drives no load for {@code durationMillis}.</li>
 * </ul>
 *
 * <p>{@link #targetVusAt(long)} and {@link #targetRateAt(long)} are the pure, deterministic setpoint
 * functions the orchestrator reads each control tick; both honour the {@link #curve}.
 */
public class LoadStage extends ObjectWithJsonToString {

    private LoadStageType type = LoadStageType.VU;
    private long durationMillis;
    private RampCurve curve = RampCurve.LINEAR;

    // VU-stage fields.
    private Integer vus;
    private Integer startVus;
    private Integer endVus;

    // RATE-stage fields (iterations per second).
    private Double rate;
    private Double startRate;
    private Double endRate;
    private Integer maxVus;

    public LoadStage() {
    }

    public static LoadStage loadStage() {
        return new LoadStage();
    }

    public static LoadStage constantVus(int vus, long durationMillis) {
        return new LoadStage().withType(LoadStageType.VU).withVus(vus).withDurationMillis(durationMillis);
    }

    public static LoadStage rampVus(int startVus, int endVus, long durationMillis, RampCurve curve) {
        return new LoadStage().withType(LoadStageType.VU)
            .withStartVus(startVus).withEndVus(endVus).withDurationMillis(durationMillis)
            .withCurve(curve != null ? curve : RampCurve.LINEAR);
    }

    public static LoadStage rampVus(int startVus, int endVus, long durationMillis) {
        return rampVus(startVus, endVus, durationMillis, RampCurve.LINEAR);
    }

    public static LoadStage constantRate(double rate, long durationMillis) {
        return new LoadStage().withType(LoadStageType.RATE).withRate(rate).withDurationMillis(durationMillis);
    }

    public static LoadStage rampRate(double startRate, double endRate, long durationMillis, RampCurve curve) {
        return new LoadStage().withType(LoadStageType.RATE)
            .withStartRate(startRate).withEndRate(endRate).withDurationMillis(durationMillis)
            .withCurve(curve != null ? curve : RampCurve.LINEAR);
    }

    public static LoadStage rampRate(double startRate, double endRate, long durationMillis) {
        return rampRate(startRate, endRate, durationMillis, RampCurve.LINEAR);
    }

    public static LoadStage pause(long durationMillis) {
        return new LoadStage().withType(LoadStageType.PAUSE).withDurationMillis(durationMillis);
    }

    /** True when this VU stage is a ramp (both start and end VUs supplied), else a hold. */
    public boolean isVuRamp() {
        return startVus != null && endVus != null;
    }

    /** True when this RATE stage is a ramp (both start and end rate supplied), else a hold. */
    public boolean isRateRamp() {
        return startRate != null && endRate != null;
    }

    /**
     * The target VU setpoint at {@code elapsedInStageMillis} into this VU stage. For a hold this is
     * {@code vus}; for a ramp it is {@code round(curve.valueAt(startVus, endVus, progress))}. Returns
     * {@code 0} for non-VU stages.
     */
    public int targetVusAt(long elapsedInStageMillis) {
        if (type != LoadStageType.VU) {
            return 0;
        }
        if (isVuRamp()) {
            double p = progress(elapsedInStageMillis);
            return (int) Math.round(curveOrDefault().valueAt(startVus, endVus, p));
        }
        return vus != null ? vus : 0;
    }

    /**
     * The target arrival rate (iterations/second) at {@code elapsedInStageMillis} into this RATE stage.
     * For a hold this is {@code rate}; for a ramp it is {@code curve.valueAt(startRate, endRate, progress)}.
     * Returns {@code 0} for non-RATE stages.
     */
    public double targetRateAt(long elapsedInStageMillis) {
        if (type != LoadStageType.RATE) {
            return 0.0;
        }
        if (isRateRamp()) {
            double p = progress(elapsedInStageMillis);
            return curveOrDefault().valueAt(startRate, endRate, p);
        }
        return rate != null ? rate : 0.0;
    }

    /** The largest VU count this stage can request (used to enforce the VU cap up-front regardless of shape). */
    public int peakVus() {
        if (type == LoadStageType.VU) {
            if (isVuRamp()) {
                return Math.max(startVus, endVus);
            }
            return vus != null ? vus : 0;
        }
        return 0;
    }

    /** The largest arrival rate this stage can request (used to enforce the rate cap up-front). */
    public double peakRate() {
        if (type == LoadStageType.RATE) {
            if (isRateRamp()) {
                return Math.max(startRate, endRate);
            }
            return rate != null ? rate : 0.0;
        }
        return 0.0;
    }

    private double progress(long elapsedInStageMillis) {
        if (durationMillis <= 0) {
            return 1.0;
        }
        return Math.min(1.0, (double) Math.max(0, elapsedInStageMillis) / durationMillis);
    }

    private RampCurve curveOrDefault() {
        return curve != null ? curve : RampCurve.LINEAR;
    }

    public LoadStageType getType() {
        return type;
    }

    public LoadStage withType(LoadStageType type) {
        this.type = type;
        return this;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public LoadStage withDurationMillis(long durationMillis) {
        this.durationMillis = durationMillis;
        return this;
    }

    public RampCurve getCurve() {
        return curve;
    }

    public LoadStage withCurve(RampCurve curve) {
        this.curve = curve;
        return this;
    }

    public Integer getVus() {
        return vus;
    }

    public LoadStage withVus(Integer vus) {
        this.vus = vus;
        return this;
    }

    public Integer getStartVus() {
        return startVus;
    }

    public LoadStage withStartVus(Integer startVus) {
        this.startVus = startVus;
        return this;
    }

    public Integer getEndVus() {
        return endVus;
    }

    public LoadStage withEndVus(Integer endVus) {
        this.endVus = endVus;
        return this;
    }

    public Double getRate() {
        return rate;
    }

    public LoadStage withRate(Double rate) {
        this.rate = rate;
        return this;
    }

    public Double getStartRate() {
        return startRate;
    }

    public LoadStage withStartRate(Double startRate) {
        this.startRate = startRate;
        return this;
    }

    public Double getEndRate() {
        return endRate;
    }

    public LoadStage withEndRate(Double endRate) {
        this.endRate = endRate;
        return this;
    }

    public Integer getMaxVus() {
        return maxVus;
    }

    public LoadStage withMaxVus(Integer maxVus) {
        this.maxVus = maxVus;
        return this;
    }
}
