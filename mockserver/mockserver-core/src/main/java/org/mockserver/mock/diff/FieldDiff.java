package org.mockserver.mock.diff;

import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;

/**
 * Represents a single field-level difference between two {@code HttpRequest} objects.
 */
public class FieldDiff extends ObjectWithReflectiveEqualsHashCodeToString {

    public enum DiffType {ADDED, REMOVED, CHANGED, EQUAL}

    private String field;
    private String expectedValue;
    private String actualValue;
    private DiffType diffType;

    public static FieldDiff added(String field, String actualValue) {
        return new FieldDiff()
            .setField(field)
            .setActualValue(actualValue)
            .setDiffType(DiffType.ADDED);
    }

    public static FieldDiff removed(String field, String expectedValue) {
        return new FieldDiff()
            .setField(field)
            .setExpectedValue(expectedValue)
            .setDiffType(DiffType.REMOVED);
    }

    public static FieldDiff changed(String field, String expectedValue, String actualValue) {
        return new FieldDiff()
            .setField(field)
            .setExpectedValue(expectedValue)
            .setActualValue(actualValue)
            .setDiffType(DiffType.CHANGED);
    }

    public String getField() {
        return field;
    }

    public FieldDiff setField(String field) {
        this.field = field;
        return this;
    }

    public String getExpectedValue() {
        return expectedValue;
    }

    public FieldDiff setExpectedValue(String expectedValue) {
        this.expectedValue = expectedValue;
        return this;
    }

    public String getActualValue() {
        return actualValue;
    }

    public FieldDiff setActualValue(String actualValue) {
        this.actualValue = actualValue;
        return this;
    }

    public DiffType getDiffType() {
        return diffType;
    }

    public FieldDiff setDiffType(DiffType diffType) {
        this.diffType = diffType;
        return this;
    }
}
