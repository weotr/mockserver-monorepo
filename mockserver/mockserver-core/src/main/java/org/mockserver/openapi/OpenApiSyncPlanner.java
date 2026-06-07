package org.mockserver.openapi;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Pure-function helper for incremental OpenAPI sync: given the set of existing
 * expectation ids, the set of newly-generated ids, and the namespace prefixes
 * that the new generation covers, determines which existing ids should be pruned.
 *
 * <p>An existing id is pruned iff it starts with one of the namespace prefixes
 * AND is not in the new id set. This ensures that re-importing a spec updates
 * expectations in place and removes operations no longer present, without
 * affecting expectations from other specs or manually created expectations.
 */
public final class OpenApiSyncPlanner {

    /** Prefix used for all OpenAPI-generated expectation ids. */
    public static final String OPENAPI_ID_PREFIX = "openapi:";

    private OpenApiSyncPlanner() {
        // utility class
    }

    /**
     * Computes the set of existing expectation ids that should be removed
     * (pruned) during an incremental OpenAPI sync.
     *
     * @param existingIds        all currently active expectation ids
     * @param newIds             ids generated from the newly-imported spec(s)
     * @param namespacePrefixes  the {@code "openapi:<specKey>:"} prefixes
     *                           covered by this import batch
     * @return the ids to remove — a subset of {@code existingIds}
     */
    public static Set<String> idsToPrune(
        Collection<String> existingIds,
        Collection<String> newIds,
        Collection<String> namespacePrefixes
    ) {
        if (existingIds == null || existingIds.isEmpty()
            || namespacePrefixes == null || namespacePrefixes.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> newIdSet = (newIds instanceof Set)
            ? (Set<String>) newIds
            : new HashSet<>(newIds != null ? newIds : Collections.emptySet());
        return existingIds.stream()
            .filter(id -> id != null
                && namespacePrefixes.stream().anyMatch(id::startsWith)
                && !newIdSet.contains(id))
            .collect(Collectors.toSet());
    }

    /**
     * Derives a stable spec key from an OpenAPI title. The title is lowercased
     * and every non-alphanumeric character is replaced with {@code '_'}.
     *
     * @param title the parsed {@code openAPI.getInfo().getTitle()}, may be null/blank
     * @return sanitized key, or {@code null} if the title is blank
     */
    public static String specKeyFromTitle(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        return title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "_");
    }

    /**
     * Derives a stable spec key by hashing the spec payload/URL.
     * Returns a short (8-char) hex hash suitable for use as a namespace token.
     *
     * @param specUrlOrPayload the raw spec URL or inline payload
     * @return an 8-character lowercase hex string
     */
    public static String specKeyFromHash(String specUrlOrPayload) {
        if (specUrlOrPayload == null) {
            return "00000000";
        }
        // Use the lower 32 bits of a stable hash
        int hash = specUrlOrPayload.hashCode();
        return String.format("%08x", hash);
    }

    /**
     * Builds the namespace prefix for a given spec key, i.e.
     * {@code "openapi:<specKey>:"}.
     *
     * @param specKey the sanitized spec key (from title or hash)
     * @return the namespace prefix string
     */
    public static String namespacePrefix(String specKey) {
        return OPENAPI_ID_PREFIX + specKey + ":";
    }
}
