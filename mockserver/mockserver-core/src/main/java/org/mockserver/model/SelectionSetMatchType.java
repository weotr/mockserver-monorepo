package org.mockserver.model;

/**
 * Controls how GraphQL body matching compares the selection set (fields).
 *
 * <ul>
 *   <li>{@link #NORMALISED_STRING} (default) — whitespace-normalised string comparison of the full query.</li>
 *   <li>{@link #AST_EXACT} — operation type, name, and top-level field set must match exactly.</li>
 *   <li>{@link #AST_SUBSET} — expected fields must be a subset of the actual query's top-level fields.</li>
 * </ul>
 */
public enum SelectionSetMatchType {

    NORMALISED_STRING, // default — existing behaviour
    AST_EXACT,         // operation type + name + field set must match exactly
    AST_SUBSET         // expected fields must be a subset of actual fields
}
