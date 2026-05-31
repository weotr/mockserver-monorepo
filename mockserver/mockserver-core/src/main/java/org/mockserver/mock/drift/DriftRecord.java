package org.mockserver.mock.drift;

import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;

/**
 * A single drift observation: one field/aspect of a forwarded response that
 * differs structurally from the matching stub expectation's response.
 */
public class DriftRecord extends ObjectWithReflectiveEqualsHashCodeToString {

    private String expectationId;
    private DriftType driftType;
    private String field;
    private String expectedValue;
    private String actualValue;
    private double confidence;
    private long epochTimeMs;

    public String getExpectationId() {
        return expectationId;
    }

    public DriftRecord setExpectationId(String expectationId) {
        this.expectationId = expectationId;
        return this;
    }

    public DriftType getDriftType() {
        return driftType;
    }

    public DriftRecord setDriftType(DriftType driftType) {
        this.driftType = driftType;
        return this;
    }

    public String getField() {
        return field;
    }

    public DriftRecord setField(String field) {
        this.field = field;
        return this;
    }

    public String getExpectedValue() {
        return expectedValue;
    }

    public DriftRecord setExpectedValue(String expectedValue) {
        this.expectedValue = expectedValue;
        return this;
    }

    public String getActualValue() {
        return actualValue;
    }

    public DriftRecord setActualValue(String actualValue) {
        this.actualValue = actualValue;
        return this;
    }

    public double getConfidence() {
        return confidence;
    }

    public DriftRecord setConfidence(double confidence) {
        this.confidence = confidence;
        return this;
    }

    public long getEpochTimeMs() {
        return epochTimeMs;
    }

    public DriftRecord setEpochTimeMs(long epochTimeMs) {
        this.epochTimeMs = epochTimeMs;
        return this;
    }
}
