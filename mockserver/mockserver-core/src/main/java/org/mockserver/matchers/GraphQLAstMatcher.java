package org.mockserver.matchers;

import org.mockserver.model.GraphQLBody;
import org.mockserver.model.SelectionSetMatchType;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AST-aware GraphQL body matcher. Supports:
 * <ul>
 *   <li>{@link SelectionSetMatchType#AST_EXACT} — operation type, name, and top-level field set must match exactly</li>
 *   <li>{@link SelectionSetMatchType#AST_SUBSET} — expected fields must be a subset of actual query's top-level fields</li>
 * </ul>
 * <p>
 * This is a lightweight parser — it does not use graphql-java. It extracts
 * operation type/name and top-level field names from GraphQL query strings
 * using character-level parsing that handles strings, comments, and brace nesting.
 */
public class GraphQLAstMatcher {

    private static final Pattern OPERATION_TYPE_PATTERN = Pattern.compile("^\\s*(query|mutation|subscription)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPERATION_NAME_PATTERN = Pattern.compile("^\\s*(?:query|mutation|subscription)\\s+(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern JSON_QUERY_PATTERN = Pattern.compile("\"query\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private final SelectionSetMatchType mode;
    private final String expectedQuery;
    private final List<String> expectedFields;

    // Pre-computed from the expected query
    private final String expectedOpType;
    private final String expectedOpName;
    private final Set<String> expectedFieldSet;

    public GraphQLAstMatcher(GraphQLBody body) {
        this.mode = body.getSelectionSetMatchType() != null
            ? body.getSelectionSetMatchType()
            : SelectionSetMatchType.NORMALISED_STRING;
        this.expectedQuery = body.getQuery();
        this.expectedFields = body.getFields() != null ? body.getFields() : Collections.emptyList();

        if (mode != SelectionSetMatchType.NORMALISED_STRING) {
            String parsed = expectedQuery != null ? expectedQuery : "";
            this.expectedOpType = extractOperationType(parsed);
            this.expectedOpName = extractOperationName(parsed);
            if (!expectedFields.isEmpty()) {
                this.expectedFieldSet = new LinkedHashSet<>(expectedFields);
            } else {
                this.expectedFieldSet = extractTopLevelFields(parsed);
            }
        } else {
            this.expectedOpType = null;
            this.expectedOpName = null;
            this.expectedFieldSet = Collections.emptySet();
        }
    }

    /**
     * Match the actual request body against the expected GraphQL body using
     * AST_EXACT or AST_SUBSET mode.
     *
     * @param actualBody the raw request body string (may be a JSON wrapper or raw GraphQL)
     * @return true if the body matches according to the configured mode
     */
    public boolean matches(String actualBody) {
        if (actualBody == null || actualBody.isBlank()) {
            return false;
        }

        String actualQuery = extractQueryFromBody(actualBody);

        switch (mode) {
            case AST_EXACT:
                return matchExact(actualQuery);
            case AST_SUBSET:
                return matchSubset(actualQuery);
            default:
                return false; // NORMALISED_STRING handled by GraphQLMatcher
        }
    }

    private boolean matchExact(String actualQuery) {
        String actualOpType = extractOperationType(actualQuery);
        String actualOpName = extractOperationName(actualQuery);
        Set<String> actualFields = extractTopLevelFields(actualQuery);

        if (expectedOpType != null && !expectedOpType.isEmpty() && !expectedOpType.equalsIgnoreCase(actualOpType)) {
            return false;
        }
        if (expectedOpName != null && !expectedOpName.isEmpty() && !expectedOpName.equals(actualOpName)) {
            return false;
        }
        return expectedFieldSet.equals(actualFields);
    }

    private boolean matchSubset(String actualQuery) {
        String actualOpType = extractOperationType(actualQuery);
        if (expectedOpType != null && !expectedOpType.isEmpty() && !expectedOpType.equalsIgnoreCase(actualOpType)) {
            return false;
        }
        if (expectedOpName != null && !expectedOpName.isEmpty()) {
            String actualOpName = extractOperationName(actualQuery);
            if (!expectedOpName.equals(actualOpName)) {
                return false;
            }
        }
        Set<String> actualFields = extractTopLevelFields(actualQuery);
        return actualFields.containsAll(expectedFieldSet);
    }

    /**
     * Extract the GraphQL query string from a request body. Handles both raw
     * GraphQL strings and JSON-wrapped bodies (GraphQL over HTTP format).
     */
    static String extractQueryFromBody(String body) {
        String trimmed = body.trim();
        // Check for JSON-wrapped GraphQL: {"query": "..."}
        if (trimmed.startsWith("{") && trimmed.contains("\"query\"")) {
            Matcher m = JSON_QUERY_PATTERN.matcher(trimmed);
            if (m.find()) {
                return m.group(1)
                    .replace("\\n", "\n")
                    .replace("\\t", "\t")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
            }
        }
        return trimmed;
    }

    /**
     * Extract the operation type (query, mutation, subscription) from a GraphQL query string.
     * Returns "query" for shorthand queries starting with '{'.
     */
    static String extractOperationType(String query) {
        if (query == null) {
            return "query";
        }
        String trimmed = stripComments(query).trim();
        if (trimmed.startsWith("{")) {
            return "query"; // shorthand query
        }
        Matcher m = OPERATION_TYPE_PATTERN.matcher(trimmed);
        return m.find() ? m.group(1).toLowerCase() : "query";
    }

    /**
     * Extract the operation name from a GraphQL query string.
     * Returns an empty string if no operation name is present.
     */
    static String extractOperationName(String query) {
        if (query == null) {
            return "";
        }
        String trimmed = stripComments(query).trim();
        Matcher m = OPERATION_NAME_PATTERN.matcher(trimmed);
        return m.find() ? m.group(1) : "";
    }

    /**
     * Extract top-level field names from the outermost selection set of a GraphQL query.
     * Handles string literals, comments, and nested braces. Skips GraphQL keywords
     * like "on" (inline fragments) and "fragment".
     */
    static Set<String> extractTopLevelFields(String query) {
        if (query == null) {
            return Collections.emptySet();
        }
        Set<String> fields = new LinkedHashSet<>();
        String body = stripComments(query).trim();

        // Find the first opening brace (skip past operation type + name + variables + directives)
        int braceStart = body.indexOf('{');
        if (braceStart < 0) {
            return fields;
        }

        // Parse from brace start: depth 1 = top-level fields
        int depth = 0;
        int parenDepth = 0;
        boolean inString = false;
        boolean prevWasBackslash = false;
        StringBuilder fieldName = new StringBuilder();

        for (int i = braceStart; i < body.length(); i++) {
            char c = body.charAt(i);

            if (inString) {
                if (c == '"' && !prevWasBackslash) {
                    inString = false;
                }
                prevWasBackslash = (c == '\\' && !prevWasBackslash);
                continue;
            }
            prevWasBackslash = false;
            if (c == '"') {
                inString = true;
                continue;
            }
            // Track parentheses to skip argument lists like (id: $id)
            if (c == '(') {
                if (depth == 1 && fieldName.length() > 0) {
                    addFieldName(fields, fieldName);
                }
                parenDepth++;
                continue;
            }
            if (c == ')') {
                parenDepth--;
                continue;
            }
            if (parenDepth > 0) {
                continue;
            }
            if (c == '{') {
                if (depth == 1 && fieldName.length() > 0) {
                    addFieldName(fields, fieldName);
                }
                depth++;
                continue;
            }
            if (c == '}') {
                if (depth == 1 && fieldName.length() > 0) {
                    addFieldName(fields, fieldName);
                }
                depth--;
                if (depth == 0) {
                    break;
                }
                continue;
            }
            if (depth == 1) {
                if (Character.isLetterOrDigit(c) || c == '_') {
                    fieldName.append(c);
                } else {
                    if (fieldName.length() > 0) {
                        addFieldName(fields, fieldName);
                    }
                }
            }
        }
        return fields;
    }

    private static void addFieldName(Set<String> fields, StringBuilder fieldName) {
        String name = fieldName.toString();
        fieldName.setLength(0);
        // Skip GraphQL keywords that appear at field level
        if (!"on".equals(name) && !"fragment".equals(name)) {
            fields.add(name);
        }
    }

    /**
     * Strip single-line comments (# ...) from a GraphQL query string.
     */
    private static String stripComments(String query) {
        return query.replaceAll("#[^\n]*", "");
    }
}
