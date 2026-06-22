package org.mockserver.scim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal SCIM filter evaluator supporting a single {@code attr op "value"} comparison over
 * top-level attributes. Supported operators: {@code eq}, {@code co}, {@code sw}, and the
 * unary presence operator {@code pr}.
 *
 * <p>Deliberately scoped for v1: compound expressions ({@code and}/{@code or}/{@code not}),
 * value-paths, and the ordered operators ({@code ge}/{@code le}/{@code gt}/{@code lt}) are not
 * supported. A filter referencing an unknown attribute matches nothing.
 */
public class ScimFilter {

    // attr pr   |   attr op "value"
    private static final Pattern PRESENCE = Pattern.compile("^\\s*([A-Za-z][\\w$.-]*)\\s+pr\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPARISON = Pattern.compile("^\\s*([A-Za-z][\\w$.-]*)\\s+(eq|co|sw)\\s+\"((?:[^\"\\\\]|\\\\.)*)\"\\s*$", Pattern.CASE_INSENSITIVE);

    private final String attribute;
    private final String operator;
    private final String value;
    private final boolean presence;

    private ScimFilter(String attribute, String operator, String value, boolean presence) {
        this.attribute = attribute;
        this.operator = operator;
        this.value = value;
        this.presence = presence;
    }

    /**
     * Parses a SCIM filter string.
     *
     * @return the parsed filter, or {@code null} if {@code filter} is blank
     * @throws IllegalArgumentException if the filter is non-blank but unparseable
     */
    public static ScimFilter parse(String filter) {
        if (filter == null || filter.trim().isEmpty()) {
            return null;
        }
        Matcher presenceMatcher = PRESENCE.matcher(filter);
        if (presenceMatcher.matches()) {
            return new ScimFilter(presenceMatcher.group(1), "pr", null, true);
        }
        Matcher comparisonMatcher = COMPARISON.matcher(filter);
        if (comparisonMatcher.matches()) {
            String attr = comparisonMatcher.group(1);
            String op = comparisonMatcher.group(2).toLowerCase();
            String val = unescape(comparisonMatcher.group(3));
            return new ScimFilter(attr, op, val, false);
        }
        throw new IllegalArgumentException("unsupported or malformed SCIM filter: " + filter);
    }

    /**
     * Applies this filter to the supplied resources, returning only the matching ones (in order).
     */
    public List<ObjectNode> apply(List<ObjectNode> resources) {
        List<ObjectNode> result = new ArrayList<>();
        for (ObjectNode resource : resources) {
            if (matches(resource)) {
                result.add(resource);
            }
        }
        return result;
    }

    public boolean matches(ObjectNode resource) {
        JsonNode node = resource.get(attribute);
        if (presence) {
            return node != null && !node.isNull()
                && !(node.isTextual() && node.asText().isEmpty());
        }
        if (node == null || node.isNull()) {
            return false;
        }
        String actual = node.asText();
        switch (operator) {
            case "eq":
                return actual.equalsIgnoreCase(value);
            case "co":
                return actual.toLowerCase().contains(value.toLowerCase());
            case "sw":
                return actual.toLowerCase().startsWith(value.toLowerCase());
            default:
                return false;
        }
    }

    public String getAttribute() {
        return attribute;
    }

    public String getOperator() {
        return operator;
    }

    public String getValue() {
        return value;
    }

    public boolean isPresence() {
        return presence;
    }

    private static String unescape(String raw) {
        return raw.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
