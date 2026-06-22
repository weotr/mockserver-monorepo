package org.mockserver.collections;

import com.google.common.annotations.VisibleForTesting;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.MatchDifference;
import org.mockserver.matchers.RegexStringMatcher;
import org.mockserver.model.KeyAndValue;
import org.mockserver.model.KeysAndValues;
import org.mockserver.model.NottableString;

import java.util.*;

import static org.mockserver.collections.ImmutableEntry.entry;
import static org.mockserver.collections.SubSetMatcher.containsSubset;
import static org.mockserver.model.NottableString.string;

/**
 * @author jamesdbloom
 */
public class NottableStringHashMap {

    private final Map<NottableString, NottableString> backingMap = new LinkedHashMap<>();
    private final RegexStringMatcher regexStringMatcher;

    public NottableStringHashMap(MockServerLogger mockServerLogger, boolean controlPlaneMatcher, List<? extends KeyAndValue> entries) {
        regexStringMatcher = new RegexStringMatcher(mockServerLogger, controlPlaneMatcher);
        for (KeyAndValue keyToMultiValue : entries) {
            put(keyToMultiValue.getName(), keyToMultiValue.getValue());
        }
    }

    /**
     * Returns a {@link NottableStringHashMap} view of the given request-side collection, reusing a
     * memoized instance held on the collection when one is available for this {@code controlPlaneMatcher}.
     * <p>
     * The conversion is keyed by {@code controlPlaneMatcher} because the resulting map embeds a
     * control-plane-sensitive {@link RegexStringMatcher}; the memo on {@link KeysAndValues} is cleared on
     * every mutation, so a mutated collection rebuilds rather than serving a stale view. This factory is
     * for the request (matched) side, which would otherwise be rebuilt once per candidate expectation.
     */
    @SuppressWarnings("rawtypes")
    public static NottableStringHashMap hashMap(MockServerLogger mockServerLogger, boolean controlPlaneMatcher, KeysAndValues<? extends KeyAndValue, ? extends KeysAndValues> matched) {
        Object cached = matched.getConvertedMatcher(controlPlaneMatcher);
        if (cached instanceof NottableStringHashMap) {
            return (NottableStringHashMap) cached;
        }
        NottableStringHashMap converted = new NottableStringHashMap(mockServerLogger, controlPlaneMatcher, matched.getEntries());
        matched.setConvertedMatcher(controlPlaneMatcher, converted);
        return converted;
    }

    @VisibleForTesting
    public NottableStringHashMap(MockServerLogger mockServerLogger, boolean controlPlaneMatcher, NottableString[]... keyAndValues) {
        regexStringMatcher = new RegexStringMatcher(mockServerLogger, controlPlaneMatcher);
        for (NottableString[] keyAndValue : keyAndValues) {
            if (keyAndValue.length >= 2) {
                put(keyAndValue[0], keyAndValue[1]);
            }
        }
    }

    public boolean containsAll(MockServerLogger mockServerLogger, MatchDifference context, NottableStringHashMap subset) {
        return containsSubset(mockServerLogger, context, regexStringMatcher, subset.entryList(), entryList());
    }

    public boolean allKeysNotted() {
        for (NottableString key : backingMap.keySet()) {
            if (!key.isNot()) {
                return false;
            }
        }
        return true;
    }

    public boolean allKeysOptional() {
        for (NottableString key : backingMap.keySet()) {
            if (!key.isOptional()) {
                return false;
            }
        }
        return true;
    }

    public boolean isEmpty() {
        return backingMap.isEmpty();
    }

    private void put(NottableString key, NottableString value) {
        backingMap.put(key, value != null ? value : string(""));
    }

    // The backingMap is immutable after construction, so the derived entryList is computed once and reused
    // across the multiple containsAll calls a memoized request-side map (see #hashMap) receives during a
    // request's expectation scan.
    private List<ImmutableEntry> entryList;

    private List<ImmutableEntry> entryList() {
        if (entryList == null) {
            if (!backingMap.isEmpty()) {
                List<ImmutableEntry> entrySet = new ArrayList<>();
                for (Map.Entry<NottableString, NottableString> entry : backingMap.entrySet()) {
                    entrySet.add(entry(regexStringMatcher, entry.getKey(), entry.getValue()));
                }
                entryList = entrySet;
            } else {
                entryList = Collections.emptyList();
            }
        }
        return entryList;
    }
}
