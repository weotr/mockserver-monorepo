package org.mockserver.slo;

import org.mockserver.model.ObjectWithJsonToString;

import java.util.ArrayList;
import java.util.List;

/**
 * The resilience verdict produced by {@link SloEvaluator} for one
 * {@link SloCriteria}. The top-level {@link #result} is the AND of the per-objective
 * results: any {@link Result#FAIL} → FAIL; otherwise any {@link Result#INCONCLUSIVE}
 * (including a below-minimum-samples window) → INCONCLUSIVE; otherwise
 * {@link Result#PASS}.
 */
public class SloVerdict extends ObjectWithJsonToString {

    public enum Result {
        PASS,
        FAIL,
        INCONCLUSIVE
    }

    private String name;
    private Result result;
    private long windowFromEpochMillis;
    private long windowToEpochMillis;
    private long sampleCount;
    private List<SloObjectiveResult> objectiveResults = new ArrayList<>();

    public String getName() {
        return name;
    }

    public SloVerdict withName(String name) {
        this.name = name;
        return this;
    }

    public Result getResult() {
        return result;
    }

    public SloVerdict withResult(Result result) {
        this.result = result;
        return this;
    }

    public long getWindowFromEpochMillis() {
        return windowFromEpochMillis;
    }

    public SloVerdict withWindowFromEpochMillis(long windowFromEpochMillis) {
        this.windowFromEpochMillis = windowFromEpochMillis;
        return this;
    }

    public long getWindowToEpochMillis() {
        return windowToEpochMillis;
    }

    public SloVerdict withWindowToEpochMillis(long windowToEpochMillis) {
        this.windowToEpochMillis = windowToEpochMillis;
        return this;
    }

    public long getSampleCount() {
        return sampleCount;
    }

    public SloVerdict withSampleCount(long sampleCount) {
        this.sampleCount = sampleCount;
        return this;
    }

    public List<SloObjectiveResult> getObjectiveResults() {
        return objectiveResults;
    }

    public SloVerdict withObjectiveResults(List<SloObjectiveResult> objectiveResults) {
        this.objectiveResults = objectiveResults != null ? objectiveResults : new ArrayList<>();
        return this;
    }
}
