package org.mockserver.serialization.model;

import org.mockserver.load.LoadShape;
import org.mockserver.load.RampCurve;
import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;

/**
 * @author jamesdbloom
 */
public class LoadShapeDTO extends ObjectWithReflectiveEqualsHashCodeToString implements DTO<LoadShape> {

    private LoadShape.Type type;
    private LoadShape.Metric metric = LoadShape.Metric.VU;
    private RampCurve curve;
    private Double baseline;
    private Double peak;
    private Long rampUpMillis;
    private Long holdMillis;
    private Long rampDownMillis;
    private Long recoveryHoldMillis;
    private Double start;
    private Double step;
    private Integer steps;
    private Long stepDurationMillis;
    private Double target;
    private Long rampMillis;

    public LoadShapeDTO(LoadShape shape) {
        if (shape != null) {
            type = shape.getType();
            metric = shape.getMetric();
            curve = shape.getCurve();
            baseline = shape.getBaseline();
            peak = shape.getPeak();
            rampUpMillis = shape.getRampUpMillis();
            holdMillis = shape.getHoldMillis();
            rampDownMillis = shape.getRampDownMillis();
            recoveryHoldMillis = shape.getRecoveryHoldMillis();
            start = shape.getStart();
            step = shape.getStep();
            steps = shape.getSteps();
            stepDurationMillis = shape.getStepDurationMillis();
            target = shape.getTarget();
            rampMillis = shape.getRampMillis();
        }
    }

    public LoadShapeDTO() {
    }

    public LoadShape buildObject() {
        return new LoadShape()
            .withType(type)
            .withMetric(metric != null ? metric : LoadShape.Metric.VU)
            .withCurve(curve != null ? curve : RampCurve.LINEAR)
            .withBaseline(baseline)
            .withPeak(peak)
            .withRampUpMillis(rampUpMillis)
            .withHoldMillis(holdMillis)
            .withRampDownMillis(rampDownMillis)
            .withRecoveryHoldMillis(recoveryHoldMillis)
            .withStart(start)
            .withStep(step)
            .withSteps(steps)
            .withStepDurationMillis(stepDurationMillis)
            .withTarget(target)
            .withRampMillis(rampMillis);
    }

    public LoadShape.Type getType() {
        return type;
    }

    public LoadShapeDTO setType(LoadShape.Type type) {
        this.type = type;
        return this;
    }

    public LoadShape.Metric getMetric() {
        return metric;
    }

    public LoadShapeDTO setMetric(LoadShape.Metric metric) {
        this.metric = metric;
        return this;
    }

    public RampCurve getCurve() {
        return curve;
    }

    public LoadShapeDTO setCurve(RampCurve curve) {
        this.curve = curve;
        return this;
    }

    public Double getBaseline() {
        return baseline;
    }

    public LoadShapeDTO setBaseline(Double baseline) {
        this.baseline = baseline;
        return this;
    }

    public Double getPeak() {
        return peak;
    }

    public LoadShapeDTO setPeak(Double peak) {
        this.peak = peak;
        return this;
    }

    public Long getRampUpMillis() {
        return rampUpMillis;
    }

    public LoadShapeDTO setRampUpMillis(Long rampUpMillis) {
        this.rampUpMillis = rampUpMillis;
        return this;
    }

    public Long getHoldMillis() {
        return holdMillis;
    }

    public LoadShapeDTO setHoldMillis(Long holdMillis) {
        this.holdMillis = holdMillis;
        return this;
    }

    public Long getRampDownMillis() {
        return rampDownMillis;
    }

    public LoadShapeDTO setRampDownMillis(Long rampDownMillis) {
        this.rampDownMillis = rampDownMillis;
        return this;
    }

    public Long getRecoveryHoldMillis() {
        return recoveryHoldMillis;
    }

    public LoadShapeDTO setRecoveryHoldMillis(Long recoveryHoldMillis) {
        this.recoveryHoldMillis = recoveryHoldMillis;
        return this;
    }

    public Double getStart() {
        return start;
    }

    public LoadShapeDTO setStart(Double start) {
        this.start = start;
        return this;
    }

    public Double getStep() {
        return step;
    }

    public LoadShapeDTO setStep(Double step) {
        this.step = step;
        return this;
    }

    public Integer getSteps() {
        return steps;
    }

    public LoadShapeDTO setSteps(Integer steps) {
        this.steps = steps;
        return this;
    }

    public Long getStepDurationMillis() {
        return stepDurationMillis;
    }

    public LoadShapeDTO setStepDurationMillis(Long stepDurationMillis) {
        this.stepDurationMillis = stepDurationMillis;
        return this;
    }

    public Double getTarget() {
        return target;
    }

    public LoadShapeDTO setTarget(Double target) {
        this.target = target;
        return this;
    }

    public Long getRampMillis() {
        return rampMillis;
    }

    public LoadShapeDTO setRampMillis(Long rampMillis) {
        this.rampMillis = rampMillis;
        return this;
    }
}
