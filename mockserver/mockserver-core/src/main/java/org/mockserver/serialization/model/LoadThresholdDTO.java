package org.mockserver.serialization.model;

import org.mockserver.load.LoadThreshold;
import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;
import org.mockserver.slo.SloObjective;

/**
 * @author jamesdbloom
 */
public class LoadThresholdDTO extends ObjectWithReflectiveEqualsHashCodeToString implements DTO<LoadThreshold> {

    private LoadThreshold.Metric metric;
    private SloObjective.Comparator comparator;
    private double threshold;

    public LoadThresholdDTO(LoadThreshold loadThreshold) {
        if (loadThreshold != null) {
            metric = loadThreshold.getMetric();
            comparator = loadThreshold.getComparator();
            threshold = loadThreshold.getThreshold();
        }
    }

    public LoadThresholdDTO() {
    }

    public LoadThreshold buildObject() {
        return new LoadThreshold()
            .withMetric(metric)
            .withComparator(comparator)
            .withThreshold(threshold);
    }

    public LoadThreshold.Metric getMetric() {
        return metric;
    }

    public LoadThresholdDTO setMetric(LoadThreshold.Metric metric) {
        this.metric = metric;
        return this;
    }

    public SloObjective.Comparator getComparator() {
        return comparator;
    }

    public LoadThresholdDTO setComparator(SloObjective.Comparator comparator) {
        this.comparator = comparator;
        return this;
    }

    public double getThreshold() {
        return threshold;
    }

    public LoadThresholdDTO setThreshold(double threshold) {
        this.threshold = threshold;
        return this;
    }
}
