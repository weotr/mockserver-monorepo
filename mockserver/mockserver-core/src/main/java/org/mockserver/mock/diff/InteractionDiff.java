package org.mockserver.mock.diff;

import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;

import java.util.ArrayList;
import java.util.List;

/**
 * A single entry in a {@link BaselineDiffReport}, describing one interaction (matched across the
 * baseline and current sets by its request {@code key} of {@code METHOD normalized-path}).
 *
 * <p>For {@code added}/{@code removed} entries only the {@link #key} is populated. For {@code changed}
 * entries the {@link #requestDiffs} (from {@link TrafficDiffEngine}) and/or {@link #responseDiffs}
 * (structural response diffs) describe what drifted.
 */
public class InteractionDiff extends ObjectWithReflectiveEqualsHashCodeToString {

    private String key;
    private List<FieldDiff> requestDiffs = new ArrayList<>();
    private List<FieldDiff> responseDiffs = new ArrayList<>();

    public static InteractionDiff of(String key) {
        return new InteractionDiff().setKey(key);
    }

    public String getKey() {
        return key;
    }

    public InteractionDiff setKey(String key) {
        this.key = key;
        return this;
    }

    public List<FieldDiff> getRequestDiffs() {
        return requestDiffs;
    }

    public InteractionDiff setRequestDiffs(List<FieldDiff> requestDiffs) {
        this.requestDiffs = requestDiffs;
        return this;
    }

    public List<FieldDiff> getResponseDiffs() {
        return responseDiffs;
    }

    public InteractionDiff setResponseDiffs(List<FieldDiff> responseDiffs) {
        this.responseDiffs = responseDiffs;
        return this;
    }
}
