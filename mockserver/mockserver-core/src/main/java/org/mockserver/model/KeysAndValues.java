package org.mockserver.model;

import java.util.*;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.model.NottableString.string;

/**
 * @author jamesdbloom
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public abstract class KeysAndValues<T extends KeyAndValue, K extends KeysAndValues> extends ObjectWithJsonToString {

    private final Map<NottableString, NottableString> map;

    // Lazily memoized request-side conversion (see #getConvertedMatcher / #setConvertedMatcher).
    // Two slots keyed by controlPlaneMatcher: index 0 = data-plane (false), index 1 = control-plane (true).
    // Cleared on every mutation via #clearConvertedMatcher so a mutated collection never serves a stale
    // conversion. A request-side collection is matched on a single I/O thread, so this lazy cache is not
    // contended; it is declared volatile only to make any future cross-thread read see a fully-published
    // array rather than a torn one, at negligible cost.
    private transient volatile Object[] convertedMatcher;

    protected KeysAndValues() {
        map = new LinkedHashMap<>();
    }

    protected KeysAndValues(Map<NottableString, NottableString> map) {
        this.map = new LinkedHashMap<>(map);
    }

    public abstract T build(NottableString name, NottableString value);

    /**
     * Returns the memoized request-side conversion for the given control-plane flag, or {@code null} if
     * not yet built. The conversion is intentionally keyed by {@code controlPlaneMatcher} because the
     * converted form embeds a control-plane-sensitive matcher; a data-plane conversion must never be
     * served to a control-plane caller or vice versa.
     */
    public Object getConvertedMatcher(boolean controlPlaneMatcher) {
        Object[] cache = convertedMatcher;
        return cache == null ? null : cache[controlPlaneMatcher ? 1 : 0];
    }

    /**
     * Stores the memoized request-side conversion for the given control-plane flag. The cache is cleared
     * automatically on any mutation, so callers may safely reuse the value for the lifetime of an
     * unmutated collection (e.g. across a single request's expectation scan).
     */
    public void setConvertedMatcher(boolean controlPlaneMatcher, Object converted) {
        Object[] cache = convertedMatcher;
        if (cache == null) {
            cache = new Object[2];
            convertedMatcher = cache;
        }
        cache[controlPlaneMatcher ? 1 : 0] = converted;
    }

    private void clearConvertedMatcher() {
        convertedMatcher = null;
    }

    public K withEntries(List<T> entries) {
        clearConvertedMatcher();
        map.clear();
        if (entries != null) {
            for (T cookie : entries) {
                withEntry(cookie);
            }
        }
        return (K) this;
    }

    public K withEntries(T... entries) {
        if (entries != null) {
            withEntries(Arrays.asList(entries));
        }
        return (K) this;
    }

    public K withEntry(T entry) {
        if (entry != null) {
            clearConvertedMatcher();
            map.put(entry.getName(), entry.getValue());
        }
        return (K) this;
    }

    public K withEntry(String name, String value) {
        clearConvertedMatcher();
        map.put(string(name), string(value));
        return (K) this;
    }

    public K withEntry(NottableString name, NottableString value) {
        clearConvertedMatcher();
        map.put(name, value);
        return (K) this;
    }

    public K replaceEntryIfExists(final T entry) {
        if (entry != null) {
            if (remove(entry.getName())) {
                clearConvertedMatcher();
                map.put(entry.getName(), entry.getValue());
            }
        }
        return (K) this;
    }

    public List<T> getEntries() {
        if (!map.isEmpty()) {
            ArrayList<T> cookies = new ArrayList<>();
            for (NottableString nottableString : map.keySet()) {
                cookies.add(build(nottableString, map.get(nottableString)));
            }
            return cookies;
        } else {
            return Collections.emptyList();
        }
    }

    public Map<NottableString, NottableString> getMap() {
        return map;
    }

    public boolean isEmpty() {
        return map.isEmpty();
    }

    public boolean remove(NottableString name) {
        return remove(name.getValue());
    }

    public boolean remove(String name) {
        if (isNotBlank(name)) {
            clearConvertedMatcher();
            return map.remove(string(name)) != null;
        }
        return false;
    }

    public abstract K clone();
}
