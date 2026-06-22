package org.mockserver.load;

import org.mockserver.model.ObjectWithJsonToString;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The load profile of a {@link LoadScenario}: an ordered list of {@link LoadStage}s the orchestrator
 * runs <em>in sequence</em>. Each stage holds or ramps a setpoint — concurrent virtual users
 * (closed model, {@link LoadStageType#VU}), an arrival rate in iterations/second (open model,
 * {@link LoadStageType#RATE}), or no load at all ({@link LoadStageType#PAUSE}) — for its
 * {@code durationMillis}, optionally shaped by a {@link RampCurve}.
 *
 * <p>The total run duration is the sum of the stage durations; the orchestrator advances stage by
 * stage and ends after the last one (or when {@code maxRequests} is hit, or on stop). Per-stage
 * setpoint functions ({@link LoadStage#targetVusAt(long)} / {@link LoadStage#targetRateAt(long)}) are
 * pure and deterministic so ramp progression is unit-testable without driving traffic.
 */
public class LoadProfile extends ObjectWithJsonToString {

    private List<LoadStage> stages = new ArrayList<>();

    public LoadProfile() {
    }

    public static LoadProfile loadProfile() {
        return new LoadProfile();
    }

    public static LoadProfile of(LoadStage... stages) {
        LoadProfile profile = new LoadProfile();
        Collections.addAll(profile.stages, stages);
        return profile;
    }

    /** Convenience: a single constant-VU stage. */
    public static LoadProfile constant(int vus, long durationMillis) {
        return of(LoadStage.constantVus(vus, durationMillis));
    }

    /** Convenience: a single linear VU ramp stage. */
    public static LoadProfile linear(int startVus, int endVus, long durationMillis) {
        return of(LoadStage.rampVus(startVus, endVus, durationMillis, RampCurve.LINEAR));
    }

    /** Convenience: a single constant arrival-rate (iterations/second) stage. */
    public static LoadProfile constantRate(double rate, long durationMillis) {
        return of(LoadStage.constantRate(rate, durationMillis));
    }

    public List<LoadStage> getStages() {
        return stages;
    }

    public LoadProfile withStages(List<LoadStage> stages) {
        this.stages = stages != null ? stages : new ArrayList<>();
        return this;
    }

    public LoadProfile withStages(LoadStage... stages) {
        this.stages = new ArrayList<>();
        Collections.addAll(this.stages, stages);
        return this;
    }

    public LoadProfile addStage(LoadStage stage) {
        if (this.stages == null) {
            this.stages = new ArrayList<>();
        }
        this.stages.add(stage);
        return this;
    }

    /** Sum of all stage durations — the total run length. */
    public long totalDurationMillis() {
        long total = 0;
        if (stages != null) {
            for (LoadStage stage : stages) {
                total += Math.max(0, stage.getDurationMillis());
            }
        }
        return total;
    }

    /** The maximum VU count any stage requests, used to enforce the VU hard cap up-front. */
    public int peakVus() {
        int peak = 0;
        if (stages != null) {
            for (LoadStage stage : stages) {
                peak = Math.max(peak, stage.peakVus());
            }
        }
        return peak;
    }

    /** The maximum arrival rate any stage requests, used to enforce the rate hard cap up-front. */
    public double peakRate() {
        double peak = 0.0;
        if (stages != null) {
            for (LoadStage stage : stages) {
                peak = Math.max(peak, stage.peakRate());
            }
        }
        return peak;
    }
}
