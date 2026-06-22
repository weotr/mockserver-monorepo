package org.mockserver.mock.diff;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;

import java.util.ArrayList;
import java.util.List;

/**
 * Structured drift report produced by {@link BaselineDiffer} when comparing a CURRENT set of
 * recorded interactions against a previously-saved BASELINE.
 *
 * <p>Three buckets are reported:
 * <ul>
 *     <li>{@code added}   — interactions present in CURRENT but not in BASELINE (matched by request key)</li>
 *     <li>{@code removed} — interactions present in BASELINE but not in CURRENT</li>
 *     <li>{@code changed} — interactions present in both (same request key) whose request fields or
 *         response <em>structure</em> differs (status code, headers, or JSON body shape)</li>
 * </ul>
 *
 * <p>The report carries a {@link #hasDrift()} convenience flag so CI can fail fast when any drift is
 * detected.
 */
public class BaselineDiffReport extends ObjectWithReflectiveEqualsHashCodeToString {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    private List<InteractionDiff> added = new ArrayList<>();
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private List<InteractionDiff> removed = new ArrayList<>();
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private List<InteractionDiff> changed = new ArrayList<>();

    public List<InteractionDiff> getAdded() {
        return added;
    }

    public BaselineDiffReport setAdded(List<InteractionDiff> added) {
        this.added = added;
        return this;
    }

    public List<InteractionDiff> getRemoved() {
        return removed;
    }

    public BaselineDiffReport setRemoved(List<InteractionDiff> removed) {
        this.removed = removed;
        return this;
    }

    public List<InteractionDiff> getChanged() {
        return changed;
    }

    public BaselineDiffReport setChanged(List<InteractionDiff> changed) {
        this.changed = changed;
        return this;
    }

    /**
     * @return {@code true} if any interaction was added, removed or changed
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public boolean isHasDrift() {
        return !added.isEmpty() || !removed.isEmpty() || !changed.isEmpty();
    }

    public boolean hasDrift() {
        return isHasDrift();
    }
}
