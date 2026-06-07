package org.mockserver.mock.diff;

import org.mockserver.model.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Compares two {@link HttpRequest} objects field-by-field and returns a list of {@link FieldDiff}
 * entries describing the differences. Supports method, path, body, headers, query parameters,
 * and cookies.
 */
public class TrafficDiffEngine {

    /**
     * Compute field-level diffs between two HTTP requests.
     *
     * @param expected the baseline (expected) request
     * @param actual   the observed (actual) request
     * @return list of differences; empty if the requests are identical (or both null)
     */
    public List<FieldDiff> diff(HttpRequest expected, HttpRequest actual) {
        List<FieldDiff> diffs = new ArrayList<>();
        if (expected == null && actual == null) {
            return diffs;
        }
        if (expected == null) {
            diffs.add(FieldDiff.added("request", "entire request"));
            return diffs;
        }
        if (actual == null) {
            diffs.add(FieldDiff.removed("request", "entire request"));
            return diffs;
        }

        // method
        addDiff(diffs, "method", nottableStringValue(expected.getMethod()), nottableStringValue(actual.getMethod()));

        // path
        addDiff(diffs, "path", nottableStringValue(expected.getPath()), nottableStringValue(actual.getPath()));

        // body
        addDiff(diffs, "body", expected.getBodyAsString(), actual.getBodyAsString());

        // headers (case-insensitive keys)
        diffMultiValueEntries(diffs, "header", expected.getHeaderList(), actual.getHeaderList());

        // query parameters (case-insensitive keys)
        diffMultiValueEntries(diffs, "queryParam", expected.getQueryStringParameterList(), actual.getQueryStringParameterList());

        // cookies (single-value entries)
        diffCookies(diffs, expected.getCookieList(), actual.getCookieList());

        return diffs;
    }

    private void addDiff(List<FieldDiff> diffs, String field, String expected, String actual) {
        boolean expBlank = isBlank(expected);
        boolean actBlank = isBlank(actual);
        if (expBlank && actBlank) {
            return;
        }
        if (expBlank) {
            diffs.add(FieldDiff.added(field, actual));
            return;
        }
        if (actBlank) {
            diffs.add(FieldDiff.removed(field, expected));
            return;
        }
        if (!expected.equals(actual)) {
            diffs.add(FieldDiff.changed(field, expected, actual));
        }
    }

    private void diffMultiValueEntries(List<FieldDiff> diffs, String prefix,
                                       List<? extends KeyToMultiValue> expected,
                                       List<? extends KeyToMultiValue> actual) {
        Map<String, String> expMap = toMultiValueMap(expected);
        Map<String, String> actMap = toMultiValueMap(actual);
        Set<String> allKeys = new LinkedHashSet<>();
        allKeys.addAll(expMap.keySet());
        allKeys.addAll(actMap.keySet());
        for (String key : allKeys) {
            String expVal = expMap.get(key);
            String actVal = actMap.get(key);
            if (expVal == null) {
                diffs.add(FieldDiff.added(prefix + "." + key, actVal));
            } else if (actVal == null) {
                diffs.add(FieldDiff.removed(prefix + "." + key, expVal));
            } else if (!expVal.equals(actVal)) {
                diffs.add(FieldDiff.changed(prefix + "." + key, expVal, actVal));
            }
        }
    }

    private void diffCookies(List<FieldDiff> diffs, List<Cookie> expected, List<Cookie> actual) {
        Map<String, String> expMap = toCookieMap(expected);
        Map<String, String> actMap = toCookieMap(actual);
        Set<String> allKeys = new LinkedHashSet<>();
        allKeys.addAll(expMap.keySet());
        allKeys.addAll(actMap.keySet());
        for (String key : allKeys) {
            String expVal = expMap.get(key);
            String actVal = actMap.get(key);
            if (expVal == null) {
                diffs.add(FieldDiff.added("cookie." + key, actVal));
            } else if (actVal == null) {
                diffs.add(FieldDiff.removed("cookie." + key, expVal));
            } else if (!expVal.equals(actVal)) {
                diffs.add(FieldDiff.changed("cookie." + key, expVal, actVal));
            }
        }
    }

    private Map<String, String> toMultiValueMap(List<? extends KeyToMultiValue> list) {
        if (list == null || list.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (KeyToMultiValue entry : list) {
            if (entry.getName() != null) {
                String key = entry.getName().getValue().toLowerCase();
                String val = entry.getValues() != null
                    ? entry.getValues().stream()
                    .map(NottableString::getValue)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining(","))
                    : "";
                map.put(key, val);
            }
        }
        return map;
    }

    private Map<String, String> toCookieMap(List<Cookie> list) {
        if (list == null || list.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (Cookie cookie : list) {
            if (cookie.getName() != null) {
                String key = cookie.getName().getValue().toLowerCase();
                String val = cookie.getValue() != null ? cookie.getValue().getValue() : "";
                map.put(key, val);
            }
        }
        return map;
    }

    private boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }

    private String nottableStringValue(NottableString ns) {
        return ns != null ? ns.getValue() : null;
    }
}
