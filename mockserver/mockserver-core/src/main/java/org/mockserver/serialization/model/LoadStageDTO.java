package org.mockserver.serialization.model;

import org.mockserver.load.LoadStage;
import org.mockserver.load.LoadStageType;
import org.mockserver.load.RampCurve;
import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;

/**
 * @author jamesdbloom
 */
public class LoadStageDTO extends ObjectWithReflectiveEqualsHashCodeToString implements DTO<LoadStage> {

    private LoadStageType type = LoadStageType.VU;
    private long durationMillis;
    private RampCurve curve;
    private Integer vus;
    private Integer startVus;
    private Integer endVus;
    private Double rate;
    private Double startRate;
    private Double endRate;
    private Integer maxVus;

    public LoadStageDTO(LoadStage stage) {
        if (stage != null) {
            type = stage.getType();
            durationMillis = stage.getDurationMillis();
            curve = stage.getCurve();
            vus = stage.getVus();
            startVus = stage.getStartVus();
            endVus = stage.getEndVus();
            rate = stage.getRate();
            startRate = stage.getStartRate();
            endRate = stage.getEndRate();
            maxVus = stage.getMaxVus();
        }
    }

    public LoadStageDTO() {
    }

    public LoadStage buildObject() {
        return new LoadStage()
            .withType(type != null ? type : LoadStageType.VU)
            .withDurationMillis(durationMillis)
            .withCurve(curve != null ? curve : RampCurve.LINEAR)
            .withVus(vus)
            .withStartVus(startVus)
            .withEndVus(endVus)
            .withRate(rate)
            .withStartRate(startRate)
            .withEndRate(endRate)
            .withMaxVus(maxVus);
    }

    public LoadStageType getType() {
        return type;
    }

    public LoadStageDTO setType(LoadStageType type) {
        this.type = type;
        return this;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public LoadStageDTO setDurationMillis(long durationMillis) {
        this.durationMillis = durationMillis;
        return this;
    }

    public RampCurve getCurve() {
        return curve;
    }

    public LoadStageDTO setCurve(RampCurve curve) {
        this.curve = curve;
        return this;
    }

    public Integer getVus() {
        return vus;
    }

    public LoadStageDTO setVus(Integer vus) {
        this.vus = vus;
        return this;
    }

    public Integer getStartVus() {
        return startVus;
    }

    public LoadStageDTO setStartVus(Integer startVus) {
        this.startVus = startVus;
        return this;
    }

    public Integer getEndVus() {
        return endVus;
    }

    public LoadStageDTO setEndVus(Integer endVus) {
        this.endVus = endVus;
        return this;
    }

    public Double getRate() {
        return rate;
    }

    public LoadStageDTO setRate(Double rate) {
        this.rate = rate;
        return this;
    }

    public Double getStartRate() {
        return startRate;
    }

    public LoadStageDTO setStartRate(Double startRate) {
        this.startRate = startRate;
        return this;
    }

    public Double getEndRate() {
        return endRate;
    }

    public LoadStageDTO setEndRate(Double endRate) {
        this.endRate = endRate;
        return this;
    }

    public Integer getMaxVus() {
        return maxVus;
    }

    public LoadStageDTO setMaxVus(Integer maxVus) {
        this.maxVus = maxVus;
        return this;
    }
}
