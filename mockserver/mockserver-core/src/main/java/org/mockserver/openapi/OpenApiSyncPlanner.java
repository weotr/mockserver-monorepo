package org.mockserver.openapi;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
     * Derives a stable, collision-resistant spec key for the OpenAPI namespace.
     *
     * <p>The key is {@code <sanitizedTitle>_<shortHash>} (or just {@code <shortHash>}
     * when the title is blank), where the hash is taken over the spec <em>source
     * identity</em> rather than the human title alone. This is the critical
     * correctness property: two <em>different</em> specs that happen to share the
     * same {@code info.title} must never collide into the same namespace, otherwise
     * importing one would prune/overwrite the other's expectations (cross-spec
     * data loss).
     *
     * <p><b>Identity semantics by source kind</b>
     * <ul>
     *   <li><b>URL / file reference</b> ({@link OpenAPIParser#isSpecUrl} is true):
     *       the source <em>reference string</em> is the stable identity. Re-importing
     *       the same URL yields the same key (so incremental sync keeps pruning
     *       removed operations) while a different URL yields a different key.</li>
     *   <li><b>Inline payload</b>: the payload <em>content</em> is the identity.
     *       Re-importing the byte-identical payload yields the same key (incremental
     *       sync still works for the unchanged-payload case); editing the payload
     *       changes the key, so the previous version's stale operations are NOT
     *       pruned on edit — they simply become orphaned under the old namespace.
     *       This trade-off is deliberate: collision-resistance (no cross-spec
     *       deletion) outranks pruning-on-inline-edit. Callers that need clean
     *       incremental sync of an evolving spec should reference it by URL/file.</li>
     * </ul>
     *
     * @param title            the parsed {@code openAPI.getInfo().getTitle()}, may be null/blank
     * @param specUrlOrPayload the raw spec URL/file reference or inline payload (the source identity)
     * @return a non-null, collision-resistant spec key safe for use as a namespace token
     */
    public static String deriveSpecKey(String title, String specUrlOrPayload) {
        String sanitizedTitle = specKeyFromTitle(title);
        String hash = specKeyFromHash(specUrlOrPayload);
        return sanitizedTitle == null ? hash : sanitizedTitle + "_" + hash;
    }

    /**
     * Derives the human-readable portion of a spec key from an OpenAPI title. The
     * title is lowercased and every non-alphanumeric character is replaced with
     * {@code '_'}.
     *
     * <p>NOTE: a title alone is NOT a safe namespace — distinct specs can share a
     * title. Use {@link #deriveSpecKey(String, String)} for the full collision-resistant key.
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
     * Derives a stable spec key by hashing the spec payload/URL source identity.
     * Returns a short 16-character hex hash suitable for use as a namespace token.
     *
     * <p>The same input always yields the same hash (so re-importing the same
     * source re-targets the same namespace); different inputs yield different
     * hashes with high probability (so distinct sources get distinct namespaces).
     *
     * @param specUrlOrPayload the raw spec URL or inline payload
     * @return a 16-character lowercase hex string
     */
    public static String specKeyFromHash(String specUrlOrPayload) {
        if (specUrlOrPayload == null) {
            return "0000000000000000";
        }
        // SHA-256 (truncated) rather than String.hashCode(): a 32-bit hash is too
        // collision-prone for a token that guards against cross-spec deletion.
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(specUrlOrPayload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS to be present on every JVM; fall back defensively.
            return String.format("%016x", (long) specUrlOrPayload.hashCode());
        }
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
