package org.mockserver.slo;

import org.mockserver.model.ObjectWithJsonToString;

/**
 * The evaluated outcome of a single {@link SloObjective}: the observed indicator
 * value over the window and whether it satisfied the threshold. {@code observedValue}
 * is null (and {@code result} is {@link SloVerdict.Result#INCONCLUSIVE}) when the
 * indicator could not be computed — e.g. error rate over zero requests.
 */
public class SloObjectiveResult extends ObjectWithJsonToString {

    private SloObjective.Sli sli;
    private SloObjective.Comparator comparator;
    private double threshold;
    private Double observedValue;
    private SloVerdict.Result result;
    private String detail;

    public SloObjective.Sli getSli() {
        return sli;
    }

    public SloObjectiveResult withSli(SloObjective.Sli sli) {
        this.sli = sli;
        return this;
    }

    public SloObjective.Comparator getComparator() {
        return comparator;
    }

    public SloObjectiveResult withComparator(SloObjective.Comparator comparator) {
        this.comparator = comparator;
        return this;
    }

    public double getThreshold() {
        return threshold;
    }

    public SloObjectiveResult withThreshold(double threshold) {
        this.threshold = threshold;
        return this;
    }

    public Double getObservedValue() {
        return observedValue;
    }

    public SloObjectiveResult withObservedValue(Double observedValue) {
        this.observedValue = observedValue;
        return this;
    }

    public SloVerdict.Result getResult() {
        return result;
    }

    public SloObjectiveResult withResult(SloVerdict.Result result) {
        this.result = result;
        return this;
    }

    public String getDetail() {
        return detail;
    }

    public SloObjectiveResult withDetail(String detail) {
        this.detail = detail;
        return this;
    }
}
