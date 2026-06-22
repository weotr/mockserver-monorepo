package org.mockserver.collections;

import com.google.common.annotations.VisibleForTesting;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.MatchDifference;
import org.mockserver.matchers.RegexStringMatcher;
import org.mockserver.model.*;

import java.util.*;

import static org.mockserver.collections.ImmutableEntry.entry;
import static org.mockserver.collections.SubSetMatcher.containsSubset;
import static org.mockserver.model.NottableString.string;

/**
 * @author jamesdbloom
 */
public class NottableStringMultiMap extends ObjectWithReflectiveEqualsHashCodeToString {

    private final Map<NottableString, List<NottableString>> backingMap = new LinkedHashMap<>();
    private final RegexStringMatcher regexStringMatcher;
    private final KeyMatchStyle keyMatchStyle;

    public NottableStringMultiMap(MockServerLogger mockServerLogger, boolean controlPlaneMatcher, KeyMatchStyle keyMatchStyle, List<? extends KeyToMultiValue> entries) {
        this.keyMatchStyle = keyMatchStyle;
        regexStringMatcher = new RegexStringMatcher(mockServerLogger, controlPlaneMatcher);
        for (KeyToMultiValue keyToMultiValue : entries) {
            backingMap.put(keyToMultiValue.getName(), keyToMultiValue.getValues());
        }
    }

    /**
     * Returns a {@link NottableStringMultiMap} view of the given request-side collection, reusing a
     * memoized instance held on the collection when one is available for this {@code controlPlaneMatcher}.
     * <p>
     * The conversion is keyed by {@code controlPlaneMatcher} because the resulting map embeds a
     * control-plane-sensitive {@link RegexStringMatcher}; the memo on {@link KeysToMultiValues} is cleared
     * on every mutation, so a collection that is mutated mid-scan (e.g. query parameters split by
     * {@code ExpandedParameterDecoder.splitParameters}) rebuilds rather than serving a stale view.
     * <p>
     * For matcher (expectation) side maps the existing constructor is used directly; this factory is for
     * the request (matched) side, which would otherwise be rebuilt once per candidate expectation.
     */
    public static NottableStringMultiMap multiMap(MockServerLogger mockServerLogger, boolean controlPlaneMatcher, KeysToMultiValues<? extends KeyToMultiValue, ? extends KeysToMultiValues> matched) {
        Object cached = matched.getConvertedMatcher(controlPlaneMatcher);
        if (cached instanceof NottableStringMultiMap) {
            return (NottableStringMultiMap) cached;
        }
        NottableStringMultiMap converted = new NottableStringMultiMap(mockServerLogger, controlPlaneMatcher, matched.getKeyMatchStyle(), matched.getEntries());
        matched.setConvertedMatcher(controlPlaneMatcher, converted);
        return converted;
    }

    @VisibleForTesting
    public NottableStringMultiMap(MockServerLogger mockServerLogger, boolean controlPlaneMatcher, KeyMatchStyle keyMatchStyle, NottableString[]... keyAndValues) {
        this.keyMatchStyle = keyMatchStyle;
        regexStringMatcher = new RegexStringMatcher(mockServerLogger, controlPlaneMatcher);
        for (NottableString[] keyAndValue : keyAndValues) {
            if (keyAndValue.length > 0) {
                backingMap.put(keyAndValue[0], keyAndValue.length > 1 ? Arrays.asList(keyAndValue).subList(1, keyAndValue.length) : Collections.emptyList());
            }
        }
    }

    public KeyMatchStyle getKeyMatchStyle() {
        return keyMatchStyle;
    }

    public boolean containsAll(MockServerLogger mockServerLogger, MatchDifference context, NottableStringMultiMap subset) {
        switch (subset.keyMatchStyle) {
            case SUB_SET: {
                boolean isSubset = containsSubset(mockServerLogger, context, regexStringMatcher, subset.entryList(), entryList());
                if (!isSubset && context != null) {
                    context.addDifference(mockServerLogger, "multimap subset match failed subset:{}was not a subset of:{}", subset.entryList(), entryList());
                }
                return isSubset;
            }
            case MATCHING_KEY: {
                for (NottableString matcherKey : subset.backingMap.keySet()) {
                    // A notted matcher key (e.g. "!X") asserts that the key is ABSENT, mirroring the
                    // SUB_SET semantic in SubSetMatcher#nottedAndPresent. Without this special case
                    // getAll(matcherKey) would "match" every actual key that is NOT X (via the XOR
                    // not-semantics in RegexStringMatcher), aggregating a bag of unrelated values from
                    // those keys — which is not a meaningful "this key must be absent" assertion. So
                    // instead fail iff some actual (non-notted) key matches the un-notted matcher key,
                    // and otherwise treat the absence requirement as satisfied (no values to assert).
                    if (matcherKey.isNot()) {
                        if (containsUnNottedKey(matcherKey)) {
                            if (context != null) {
                                context.addDifference(mockServerLogger, "multimap matching key match failed for notted key:{}", matcherKey);
                            }
                            return false;
                        }
                        continue;
                    }

                    List<NottableString> matchedValuesForKey = getAll(matcherKey);
                    if (matchedValuesForKey.isEmpty() && !matcherKey.isOptional()) {
                        if (context != null) {
                            context.addDifference(mockServerLogger, "multimap subset match failed subset:{}did not have expected key:{}", subset, matcherKey);
                        }
                        return false;
                    }

                    List<NottableString> matcherValuesForKey = subset.getAll(matcherKey);
                    for (NottableString matchedValue : matchedValuesForKey) {
                        boolean matchesValue = false;
                        for (NottableString matcherValue : matcherValuesForKey) {
                            // match first as list
                            if (matcherValue instanceof NottableSchemaString && ((NottableSchemaString) matcherValue).matches(mockServerLogger, context, matchedValuesForKey)) {
                                matchesValue = true;
                                break;
                                // otherwise match item by item
                            } else if (regexStringMatcher.matches(mockServerLogger, context, matcherValue, matchedValue)) {
                                matchesValue = true;
                                break;
                            } else {
                                if (context != null) {
                                    context.addDifference(mockServerLogger, "multimap matching key match failed for key:{}", matcherKey);
                                }
                            }
                        }
                        if (!matchesValue) {
                            return false;
                        }
                    }
                }
                return true;
            }
        }
        return false;
    }

    public boolean allKeysNotted() {
        if (!isEmpty()) {
            for (NottableString key : backingMap.keySet()) {
                if (!key.isNot()) {
                    return false;
                }
            }
        }
        return true;
    }

    public boolean allKeysOptional() {
        if (!isEmpty()) {
            for (NottableString key : backingMap.keySet()) {
                if (!key.isOptional()) {
                    return false;
                }
            }
        }
        return true;
    }

    public boolean isEmpty() {
        return backingMap.isEmpty();
    }

    /**
     * Returns true when some actual (non-notted) key matches the un-notted form of the given notted
     * matcher key — i.e. the key the {@code "!X"} matcher asserts must be absent is in fact present.
     * Mirrors {@link SubSetMatcher} {@code nottedAndPresent} so MATCHING_KEY and SUB_SET agree on the
     * "this key must be absent" semantic for a notted key.
     */
    private boolean containsUnNottedKey(NottableString nottedKey) {
        if (!isEmpty()) {
            NottableString unNottedKey = string(nottedKey.getValue());
            for (NottableString actualKey : backingMap.keySet()) {
                if (!actualKey.isNot() && regexStringMatcher.matches(unNottedKey, actualKey)) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<NottableString> getAll(NottableString key) {
        if (!isEmpty()) {
            List<NottableString> values = new ArrayList<>();
            for (Map.Entry<NottableString, List<NottableString>> entry : backingMap.entrySet()) {
                if (regexStringMatcher.matches(key, entry.getKey())) {
                    values.addAll(entry.getValue());
                }
            }
            return values;
        } else {
            return Collections.emptyList();
        }
    }

    // The backingMap is immutable after construction, so the derived entryList is computed once and reused.
    // This matters because a memoized request-side map (see #multiMap) has its entryList read once per
    // candidate expectation during a request's scan.
    private List<ImmutableEntry> entryList;

    private List<ImmutableEntry> entryList() {
        if (entryList == null) {
            if (!isEmpty()) {
                List<ImmutableEntry> entrySet = new ArrayList<>();
                for (Map.Entry<NottableString, List<NottableString>> entry : backingMap.entrySet()) {
                    for (NottableString value : entry.getValue()) {
                        entrySet.add(entry(regexStringMatcher, entry.getKey(), value));
                    }
                }
                entryList = entrySet;
            } else {
                entryList = Collections.emptyList();
            }
        }
        return entryList;
    }
}



