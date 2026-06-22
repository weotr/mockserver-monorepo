package org.mockserver.openapi;

import org.junit.Test;

import java.util.*;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.core.Is.is;
import static org.mockserver.openapi.OpenApiSyncPlanner.*;

public class OpenApiSyncPlannerTest {

    // ---- idsToPrune tests ----

    @Test
    public void shouldPruneRemovedOperationInSameNamespace() {
        // existing has listPets and deletePets; new import only has listPets
        Set<String> existing = Set.of(
            "openapi:petstore:listPets",
            "openapi:petstore:deletePets"
        );
        Set<String> newIds = Set.of("openapi:petstore:listPets");
        Set<String> prefixes = Set.of("openapi:petstore:");

        Set<String> pruned = idsToPrune(existing, newIds, prefixes);

        assertThat(pruned, is(Set.of("openapi:petstore:deletePets")));
    }

    @Test
    public void shouldNotPruneExpectationsInDifferentNamespace() {
        Set<String> existing = Set.of(
            "openapi:petstore:listPets",
            "openapi:billing:getInvoice",
            "some-manual-uuid-id"
        );
        Set<String> newIds = Set.of("openapi:petstore:listPets");
        Set<String> prefixes = Set.of("openapi:petstore:");

        Set<String> pruned = idsToPrune(existing, newIds, prefixes);

        assertThat(pruned, is(empty()));
    }

    @Test
    public void shouldNotPruneManualExpectations() {
        Set<String> existing = Set.of(
            "openapi:petstore:listPets",
            "custom-id-123"
        );
        // re-import with same operations
        Set<String> newIds = Set.of("openapi:petstore:listPets");
        Set<String> prefixes = Set.of("openapi:petstore:");

        Set<String> pruned = idsToPrune(existing, newIds, prefixes);

        assertThat(pruned, is(empty()));
    }

    @Test
    public void shouldPruneFromMultipleNamespacesAtOnce() {
        Set<String> existing = Set.of(
            "openapi:petstore:listPets",
            "openapi:petstore:deletePets",
            "openapi:billing:getInvoice",
            "openapi:billing:deleteInvoice"
        );
        Set<String> newIds = Set.of(
            "openapi:petstore:listPets",
            "openapi:billing:getInvoice"
        );
        Set<String> prefixes = Set.of("openapi:petstore:", "openapi:billing:");

        Set<String> pruned = idsToPrune(existing, newIds, prefixes);

        assertThat(pruned, containsInAnyOrder(
            "openapi:petstore:deletePets",
            "openapi:billing:deleteInvoice"
        ));
    }

    @Test
    public void shouldReturnEmptyWhenExistingIdsAreEmpty() {
        Set<String> pruned = idsToPrune(
            Collections.emptySet(),
            Set.of("openapi:petstore:listPets"),
            Set.of("openapi:petstore:")
        );

        assertThat(pruned, is(empty()));
    }

    @Test
    public void shouldReturnEmptyWhenExistingIdsAreNull() {
        Set<String> pruned = idsToPrune(
            null,
            Set.of("openapi:petstore:listPets"),
            Set.of("openapi:petstore:")
        );

        assertThat(pruned, is(empty()));
    }

    @Test
    public void shouldReturnEmptyWhenNamespacePrefixesAreEmpty() {
        Set<String> pruned = idsToPrune(
            Set.of("openapi:petstore:deletePets"),
            Set.of("openapi:petstore:listPets"),
            Collections.emptySet()
        );

        assertThat(pruned, is(empty()));
    }

    @Test
    public void shouldReturnEmptyWhenNamespacePrefixesAreNull() {
        Set<String> pruned = idsToPrune(
            Set.of("openapi:petstore:deletePets"),
            Set.of("openapi:petstore:listPets"),
            null
        );

        assertThat(pruned, is(empty()));
    }

    @Test
    public void shouldHandleNewIdsBeingNull() {
        // All existing ids in the namespace should be pruned
        Set<String> pruned = idsToPrune(
            Set.of("openapi:petstore:listPets"),
            null,
            Set.of("openapi:petstore:")
        );

        assertThat(pruned, is(Set.of("openapi:petstore:listPets")));
    }

    @Test
    public void shouldHandleDisambiguatedIds() {
        // When the same operationId is used for multiple responses, ids get ":2", ":3" etc.
        Set<String> existing = Set.of(
            "openapi:petstore:listPets",
            "openapi:petstore:listPets:2",
            "openapi:petstore:listPets:3"
        );
        // New import only has listPets (single) and listPets:2
        Set<String> newIds = Set.of(
            "openapi:petstore:listPets",
            "openapi:petstore:listPets:2"
        );
        Set<String> prefixes = Set.of("openapi:petstore:");

        Set<String> pruned = idsToPrune(existing, newIds, prefixes);

        assertThat(pruned, is(Set.of("openapi:petstore:listPets:3")));
    }

    @Test
    public void shouldAddNewOperationWithoutPruning() {
        Set<String> existing = Set.of("openapi:petstore:listPets");
        Set<String> newIds = Set.of(
            "openapi:petstore:listPets",
            "openapi:petstore:createPets"
        );
        Set<String> prefixes = Set.of("openapi:petstore:");

        Set<String> pruned = idsToPrune(existing, newIds, prefixes);

        assertThat(pruned, is(empty()));
    }

    // ---- specKeyFromTitle tests ----

    @Test
    public void shouldSanitizeTitleToSpecKey() {
        assertThat(specKeyFromTitle("Swagger Petstore"), is("swagger_petstore"));
    }

    @Test
    public void shouldHandleTitleWithSpecialCharacters() {
        assertThat(specKeyFromTitle("My API (v2.1)"), is("my_api__v2_1_"));
    }

    @Test
    public void shouldReturnNullForBlankTitle() {
        assertThat(specKeyFromTitle(""), is(nullValue()));
        assertThat(specKeyFromTitle(null), is(nullValue()));
        assertThat(specKeyFromTitle("   "), is(nullValue()));
    }

    @Test
    public void shouldLowercaseTitle() {
        assertThat(specKeyFromTitle("ABC"), is("abc"));
    }

    // ---- specKeyFromHash tests ----

    @Test
    public void shouldReturnSixteenCharHexHash() {
        String hash = specKeyFromHash("some-payload");
        assertThat(hash.length(), is(16));
        assertThat(hash, matchesPattern("[0-9a-f]{16}"));
    }

    @Test
    public void shouldReturnSameHashForSameInput() {
        assertThat(specKeyFromHash("hello"), is(specKeyFromHash("hello")));
    }

    @Test
    public void shouldReturnDifferentHashForDifferentInput() {
        assertThat(specKeyFromHash("hello"), is(not(specKeyFromHash("world"))));
    }

    @Test
    public void shouldHandleNullPayload() {
        assertThat(specKeyFromHash(null), is("0000000000000000"));
    }

    // ---- namespacePrefix tests ----

    @Test
    public void shouldBuildNamespacePrefix() {
        assertThat(namespacePrefix("petstore"), is("openapi:petstore:"));
    }

    // ---- deriveSpecKey tests (collision-resistant identity) ----

    @Test
    public void shouldCombineTitleAndSourceHash() {
        String key = deriveSpecKey("Swagger Petstore", "some-payload");
        assertThat(key, startsWith("swagger_petstore_"));
        assertThat(key, matchesPattern("swagger_petstore_[0-9a-f]{16}"));
    }

    @Test
    public void shouldFallBackToHashOnlyWhenTitleBlank() {
        String key = deriveSpecKey(null, "some-payload");
        assertThat(key, matchesPattern("[0-9a-f]{16}"));
        assertThat(deriveSpecKey("   ", "some-payload"), is(key));
    }

    @Test
    public void shouldDeriveSameKeyForSameSource() {
        // invariant 3 at the identity level: same source -> same namespace -> incremental sync still prunes
        assertThat(deriveSpecKey("Foo", "payload-1"), is(deriveSpecKey("Foo", "payload-1")));
    }

    @Test
    public void shouldDeriveDistinctKeysForDifferentSourcesWithSameTitle() {
        // invariant 1 at the identity level: distinct specs sharing a title must NOT collide
        String keyA = deriveSpecKey("Shared Title", "payload-A");
        String keyB = deriveSpecKey("Shared Title", "payload-B");
        assertThat(keyA, startsWith("shared_title_"));
        assertThat(keyB, startsWith("shared_title_"));
        assertThat(keyA, is(not(keyB)));
    }

    @Test
    public void shouldDeriveDistinctKeysForDifferentUrlsWithSameTitle() {
        String keyA = deriveSpecKey("Shared Title", "https://example.com/a.json");
        String keyB = deriveSpecKey("Shared Title", "https://example.com/b.json");
        assertThat(keyA, is(not(keyB)));
    }

    // ---- cross-spec prune safety (invariant 1) at the planner level ----

    @Test
    public void shouldNotPruneOtherSpecWhenItSharesSanitizedTitleButNotHash() {
        // Two specs share the sanitized title "shared_title" but have distinct source hashes.
        String specAKey = deriveSpecKey("Shared Title", "payload-A");
        String specBKey = deriveSpecKey("Shared Title", "payload-B");
        String specAOp = namespacePrefix(specAKey) + "opA";
        String specBOp = namespacePrefix(specBKey) + "opB";

        // Importing spec B: existing has both A's and B's ops; new import is only B's namespace.
        Set<String> existing = Set.of(specAOp, specBOp);
        Set<String> newIds = Set.of(specBOp);
        Set<String> prefixes = Set.of(namespacePrefix(specBKey));

        Set<String> pruned = idsToPrune(existing, newIds, prefixes);

        // spec A's expectation must survive
        assertThat(pruned, not(hasItem(specAOp)));
        assertThat(pruned, is(empty()));
    }
}
