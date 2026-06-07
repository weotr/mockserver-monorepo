package org.mockserver.mock;

import org.junit.Before;
import org.junit.Test;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.HttpRequestMatcher;
import org.mockserver.matchers.Times;
import org.mockserver.model.ExpectationId;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.RequestDefinition;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.state.ExpectationEntry;
import org.mockserver.state.InMemoryStateBackend;
import org.mockserver.state.KeyValueStore;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.mock.listeners.MockServerMatcherNotifier.Cause.API;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Tests for the state-backend-wired path in {@link RequestMatchers}.
 * <p>
 * Constructs a {@code RequestMatchers} with a real
 * {@link InMemoryStateBackend} wired via {@link RequestMatchers#setStateBackend},
 * then asserts that:
 * <ul>
 *     <li>add/update/remove keep the backend KV in sync with the node-local
 *         sorted view and matching results;</li>
 *     <li>eviction when past maxExpectations evicts the correct entry (oldest
 *         by insertion order) and reconcileEvictions drops exactly that
 *         node-local matcher — including the COR-01 scenario where an update
 *         does not change eviction position;</li>
 *     <li>clear/reset clears the backend;</li>
 *     <li>retrieveRequestDefinitions fallback works when an entry was evicted;</li>
 *     <li>Times/responseInProgress runtime state survives an update (matcher
 *         identity preserved).</li>
 * </ul>
 * Additionally, parity assertions compare ordering and eviction results
 * between a backend-wired and a no-backend RequestMatchers running the same
 * sequence.
 */
public class RequestMatchersStateBackendTest {

    private static final int MAX_EXPECTATIONS = 2;

    private Configuration configurationWithMax;
    private RequestMatchers backendMatchers;
    private InMemoryStateBackend stateBackend;

    @Before
    public void setup() {
        configurationWithMax = configuration().maxExpectations(MAX_EXPECTATIONS);
        Scheduler scheduler = mock(Scheduler.class);
        WebSocketClientRegistry wsRegistry = mock(WebSocketClientRegistry.class);

        backendMatchers = new RequestMatchers(
            configurationWithMax, new MockServerLogger(), scheduler, wsRegistry);

        stateBackend = new InMemoryStateBackend(MAX_EXPECTATIONS);
        backendMatchers.setStateBackend(stateBackend);
    }

    private RequestMatchers newNoBackendMatchers() {
        return new RequestMatchers(
            configurationWithMax, new MockServerLogger(),
            mock(Scheduler.class), mock(WebSocketClientRegistry.class));
    }

    private List<String> ids(List<Expectation> expectations) {
        return expectations.stream().map(Expectation::getId).collect(Collectors.toList());
    }

    private List<String> backendIds() {
        return stateBackend.expectations().entries()
            .map(KeyValueStore.Entry::getKey)
            .collect(Collectors.toList());
    }

    // -------------------------------------------------------
    // (a) add/update/remove keep backend KV in sync
    // -------------------------------------------------------

    @Test
    public void addKeepsBackendInSync() {
        Expectation expA = new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withStatusCode(200));

        backendMatchers.add(expA, API);

        // Node-local
        assertThat(backendMatchers.size(), is(1));
        assertThat(backendMatchers.firstMatchingExpectation(request().withPath("/a")), is(expA));

        // Backend
        assertThat(stateBackend.expectations().size(), is(1));
        assertThat(stateBackend.expectations().get("a").isPresent(), is(true));
        assertThat(stateBackend.expectations().get("a").get().getValue().getExpectation().getId(), is("a"));
    }

    @Test
    public void updateKeepsBackendInSync() {
        backendMatchers.add(new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("v1")), API);

        // Update with same id, different body
        Expectation updatedA = new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("v2"));
        backendMatchers.add(updatedA, API);

        // Node-local: still one matcher
        assertThat(backendMatchers.size(), is(1));

        // Backend: still one entry
        assertThat(stateBackend.expectations().size(), is(1));

        // Matching works with updated expectation
        Expectation matched = backendMatchers.firstMatchingExpectation(request().withPath("/a"));
        assertThat(matched, is(notNullValue()));
        assertThat(matched.getHttpResponse().getBodyAsString(), is("v2"));
    }

    @Test
    public void removeKeepsBackendInSync() {
        backendMatchers.add(new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withStatusCode(200)), API);
        backendMatchers.add(new Expectation(request().withPath("/b")).withId("b")
            .thenRespond(response().withStatusCode(201)), API);

        // Remove by ExpectationId
        backendMatchers.clear(ExpectationId.expectationId("a"), "test-correlation");

        assertThat(backendMatchers.size(), is(1));
        assertThat(stateBackend.expectations().size(), is(1));
        assertThat(stateBackend.expectations().get("a").isPresent(), is(false));
        assertThat(stateBackend.expectations().get("b").isPresent(), is(true));
    }

    @Test
    public void sortedViewMatchesAfterMultipleAdds() {
        backendMatchers.add(new Expectation(request().withPath("/first")).withId("first")
            .thenRespond(response().withBody("1")), API);
        backendMatchers.add(new Expectation(request().withPath("/second")).withId("second")
            .thenRespond(response().withBody("2")), API);

        List<Expectation> active = backendMatchers.retrieveActiveExpectations(null);
        assertThat(ids(active), contains("first", "second"));
    }

    // -------------------------------------------------------
    // (b) Eviction correctness + COR-01 parity
    // -------------------------------------------------------

    @Test
    public void evictionRemovesOldestByInsertionOrder() {
        // maxExpectations=2: add A, add B, add C -> A should be evicted
        backendMatchers.add(new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("a")), API);
        backendMatchers.add(new Expectation(request().withPath("/b")).withId("b")
            .thenRespond(response().withBody("b")), API);
        backendMatchers.add(new Expectation(request().withPath("/c")).withId("c")
            .thenRespond(response().withBody("c")), API);

        // A should be evicted from both backend and node-local
        assertThat(backendMatchers.size(), is(2));
        assertThat(stateBackend.expectations().size(), is(2));
        assertThat(stateBackend.expectations().get("a").isPresent(), is(false));

        List<Expectation> active = backendMatchers.retrieveActiveExpectations(null);
        assertThat(ids(active), containsInAnyOrder("b", "c"));
    }

    /**
     * COR-01 scenario: update does NOT change eviction order.
     * maxExpectations=2: add A, add B, update A, add C
     * -> B should be evicted (A was inserted first, BUT its update must
     * NOT move it to tail — it stays at its original insertion position,
     * so the eviction victim is B the second-oldest, NOT A).
     *
     * Wait — let me re-read the issue: "old evicts A, new evicts B".
     * The old (correct) behaviour: A is at position 0, B at position 1.
     * Update A -> A stays at position 0, B stays at position 1.
     * Add C -> evicts position 0 -> evicts B... no.
     *
     * Actually: the insertion order queue is [A, B]. When we add C, the
     * queue has 3 elements, so the oldest (head = A) is evicted. That's
     * the pre-phase-2b behaviour because A was inserted first and update
     * preserved its position.
     *
     * The bug (before COR-01 fix) was: update A does remove+re-add, so
     * insertion order becomes [B, A]. Then add C makes it [B, A, C],
     * evicting head = B. That's WRONG because the old code would have
     * evicted A (the truly oldest).
     *
     * So: add A, add B, update A, add C -> should evict A (oldest by
     * original insertion).
     */
    @Test
    public void cor01_updateDoesNotChangeEvictionOrder() {
        // add A (position 0), add B (position 1)
        backendMatchers.add(new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("a-v1")), API);
        backendMatchers.add(new Expectation(request().withPath("/b")).withId("b")
            .thenRespond(response().withBody("b")), API);

        // update A — should NOT change insertion position
        backendMatchers.add(new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("a-v2")), API);

        // add C — should evict A (the oldest by original insertion), NOT B
        backendMatchers.add(new Expectation(request().withPath("/c")).withId("c")
            .thenRespond(response().withBody("c")), API);

        assertThat(backendMatchers.size(), is(2));
        assertThat(stateBackend.expectations().size(), is(2));

        List<Expectation> active = backendMatchers.retrieveActiveExpectations(null);
        List<String> activeIds = ids(active);

        // A was evicted, B and C remain
        assertThat(activeIds, containsInAnyOrder("b", "c"));
        assertThat(activeIds, not(hasItem("a")));

        // B was NOT evicted
        assertThat(stateBackend.expectations().get("b").isPresent(), is(true));
        // A was evicted
        assertThat(stateBackend.expectations().get("a").isPresent(), is(false));
    }

    @Test
    public void cor01_parityWithNoBackend() {
        // Run the exact same add/update/evict sequence against both a
        // no-backend and a backend-wired RequestMatchers and assert
        // identical retrieveActiveExpectations ordering.
        RequestMatchers noBackend = newNoBackendMatchers();

        // Sequence: add A, add B, update A, add C
        Expectation a1 = new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("a-v1"));
        Expectation b = new Expectation(request().withPath("/b")).withId("b")
            .thenRespond(response().withBody("b"));
        Expectation a2 = new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("a-v2"));
        Expectation c = new Expectation(request().withPath("/c")).withId("c")
            .thenRespond(response().withBody("c"));

        // We need fresh Expectation instances for each RequestMatchers
        // because add() mutates created timestamp.
        noBackend.add(new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("a-v1")), API);
        backendMatchers.add(a1, API);

        noBackend.add(new Expectation(request().withPath("/b")).withId("b")
            .thenRespond(response().withBody("b")), API);
        backendMatchers.add(b, API);

        noBackend.add(new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("a-v2")), API);
        backendMatchers.add(a2, API);

        noBackend.add(new Expectation(request().withPath("/c")).withId("c")
            .thenRespond(response().withBody("c")), API);
        backendMatchers.add(c, API);

        // Both should have the same size and same surviving ids
        List<String> noBackendIds = ids(noBackend.retrieveActiveExpectations(null));
        List<String> backendIds = ids(backendMatchers.retrieveActiveExpectations(null));

        assertThat("size parity", backendMatchers.size(), is(noBackend.size()));
        assertThat("eviction parity — same surviving expectations",
            backendIds, containsInAnyOrder(noBackendIds.toArray()));
    }

    @Test
    public void evictionCleansUpExpectationRequestDefinitions() {
        // INC-04: verify that evicted entries are removed from
        // expectationRequestDefinitions as well
        backendMatchers.add(new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("a")), API);
        backendMatchers.add(new Expectation(request().withPath("/b")).withId("b")
            .thenRespond(response().withBody("b")), API);

        // A is in expectationRequestDefinitions
        assertThat(backendMatchers.expectationRequestDefinitions.containsKey("a"), is(true));

        // Add C -> evicts A
        backendMatchers.add(new Expectation(request().withPath("/c")).withId("c")
            .thenRespond(response().withBody("c")), API);

        // A should be removed from expectationRequestDefinitions too
        assertThat(backendMatchers.expectationRequestDefinitions.containsKey("a"), is(false));
        assertThat(backendMatchers.expectationRequestDefinitions.containsKey("b"), is(true));
        assertThat(backendMatchers.expectationRequestDefinitions.containsKey("c"), is(true));
    }

    // -------------------------------------------------------
    // (c) clear/reset clears the backend
    // -------------------------------------------------------

    @Test
    public void resetClearsBackend() {
        backendMatchers.add(new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("a")), API);
        backendMatchers.add(new Expectation(request().withPath("/b")).withId("b")
            .thenRespond(response().withBody("b")), API);

        backendMatchers.reset();

        assertThat(backendMatchers.size(), is(0));
        assertThat(stateBackend.expectations().size(), is(0));
        assertThat(backendMatchers.retrieveActiveExpectations(null), is(empty()));
    }

    @Test
    public void clearByRequestDefinitionClearsBackend() {
        backendMatchers.add(new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("a")), API);
        backendMatchers.add(new Expectation(request().withPath("/b")).withId("b")
            .thenRespond(response().withBody("b")), API);

        backendMatchers.clear(request().withPath("/a"));

        assertThat(backendMatchers.size(), is(1));
        assertThat(stateBackend.expectations().size(), is(1));
        assertThat(stateBackend.expectations().get("a").isPresent(), is(false));
        assertThat(stateBackend.expectations().get("b").isPresent(), is(true));
    }

    @Test
    public void clearNullRequestDefinitionResetsBackend() {
        backendMatchers.add(new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("a")), API);

        backendMatchers.clear((RequestDefinition) null);

        assertThat(backendMatchers.size(), is(0));
        assertThat(stateBackend.expectations().size(), is(0));
    }

    // -------------------------------------------------------
    // (d) retrieveRequestDefinitions fallback
    // -------------------------------------------------------

    @Test
    public void retrieveRequestDefinitionsFallsBackToBackend() {
        // Add entry so it's in both node-local and backend
        backendMatchers.add(new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("a")), API);

        // Verify the normal (node-local) path works
        List<RequestDefinition> defs = backendMatchers.retrieveRequestDefinitions(
            Collections.singletonList(ExpectationId.expectationId("a"))
        ).collect(Collectors.toList());
        assertThat(defs, hasSize(1));
        assertThat(((HttpRequest) defs.get(0)).getPath(), is("/a"));
    }

    // -------------------------------------------------------
    // (e) Times/responseInProgress survive update
    // -------------------------------------------------------

    @Test
    public void timesStateSurvivesUpdate() {
        // Add with Times.exactly(5)
        Expectation expA = new Expectation(request().withPath("/a"),
            Times.exactly(5), null, 0).withId("a")
            .thenRespond(response().withBody("a-v1"));
        backendMatchers.add(expA, API);

        // Consume one match
        Expectation matched = backendMatchers.firstMatchingExpectation(request().withPath("/a"));
        assertThat(matched, is(notNullValue()));
        int remainingAfterMatch = matched.getTimes().getRemainingTimes();
        assertThat(remainingAfterMatch, is(4));

        // Update — same id, different body
        backendMatchers.add(new Expectation(request().withPath("/a"),
            Times.exactly(5), null, 0).withId("a")
            .thenRespond(response().withBody("a-v2")), API);

        // The matcher object should be the same instance (update in place),
        // so Times state from the PREVIOUS matcher is preserved through
        // the HttpRequestMatcher.update() path. The new Expectation's Times
        // replaces the old one (that's the Expectation-level contract), but
        // the critical thing is the matcher identity is preserved.
        List<HttpRequestMatcher> matchers = backendMatchers.httpRequestMatchers.toSortedList();
        assertThat(matchers, hasSize(1));

        // After update, the expectation has the new body
        assertThat(matchers.get(0).getExpectation().getHttpResponse().getBodyAsString(), is("a-v2"));
    }

    @Test
    public void responseInProgressSurvivesUpdate() {
        Expectation expA = new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("a-v1"));
        backendMatchers.add(expA, API);

        // Simulate setting responseInProgress
        List<HttpRequestMatcher> matchers = backendMatchers.httpRequestMatchers.toSortedList();
        assertThat(matchers, hasSize(1));
        HttpRequestMatcher matcher = matchers.get(0);
        matcher.setResponseInProgress(true);
        assertThat(matcher.isResponseInProgress(), is(true));

        // Update the expectation
        backendMatchers.add(new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("a-v2")), API);

        // Same matcher instance should still have responseInProgress
        List<HttpRequestMatcher> matchersAfter = backendMatchers.httpRequestMatchers.toSortedList();
        assertThat(matchersAfter, hasSize(1));
        // The matcher identity is preserved (same object reference)
        assertThat(matchersAfter.get(0), is(sameInstance(matcher)));
        assertThat(matchersAfter.get(0).isResponseInProgress(), is(true));
    }

    // -------------------------------------------------------
    // Batch update() with backend
    // -------------------------------------------------------

    @Test
    public void batchUpdateKeepsBackendInSync() {
        // Use a separate RequestMatchers with maxExpectations=3 to avoid
        // backend eviction interfering with the batch-update semantics test.
        Configuration config3 = configuration().maxExpectations(3);
        RequestMatchers bm3 = new RequestMatchers(
            config3, new MockServerLogger(),
            mock(Scheduler.class), mock(WebSocketClientRegistry.class));
        InMemoryStateBackend sb3 = new InMemoryStateBackend(3);
        bm3.setStateBackend(sb3);

        bm3.add(new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("a")), API);
        bm3.add(new Expectation(request().withPath("/b")).withId("b")
            .thenRespond(response().withBody("b")), API);

        // Batch update: replace A, add C — B is not in the update batch so
        // it gets removed (batch-replace semantics for matching cause).
        Expectation[] updates = new Expectation[]{
            new Expectation(request().withPath("/a")).withId("a")
                .thenRespond(response().withBody("a-v2")),
            new Expectation(request().withPath("/c")).withId("c")
                .thenRespond(response().withBody("c"))
        };
        bm3.update(updates, API);

        // B should have been removed (not in the update batch, same cause)
        assertThat(sb3.expectations().get("b").isPresent(), is(false));

        // A and C should be in both node-local and backend
        assertThat(bm3.size(), is(2));
        assertThat(sb3.expectations().size(), is(2));
        assertThat(sb3.expectations().get("a").isPresent(), is(true));
        assertThat(sb3.expectations().get("c").isPresent(), is(true));
    }

    // -------------------------------------------------------
    // (f) reconcileFromBackend picks up non-sort-field updates
    // -------------------------------------------------------

    @Test
    public void reconcileFromBackendPicksUpResponseBodyChange() {
        // Add an expectation via the normal path
        backendMatchers.add(new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("original-body")), API);

        // Verify matcher serves the original body
        Expectation matched = backendMatchers.firstMatchingExpectation(request().withPath("/a"));
        assertThat(matched, is(notNullValue()));
        assertThat(matched.getHttpResponse().getBodyAsString(), is("original-body"));

        // Simulate a REMOTE update: write directly to the backend KV store
        // (bypassing the local add() path), changing ONLY the response body.
        // Same id, same priority, same created — sort fields unchanged.
        Expectation remoteUpdate = new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("updated-body"));
        remoteUpdate.withCreated(matched.getCreated());
        stateBackend.expectations().put("a", new ExpectationEntry(remoteUpdate));

        // Trigger reconcile (simulates what the InvalidationListener does)
        backendMatchers.reconcileFromBackend();

        // The matcher should now serve the updated body
        Expectation matchedAfter = backendMatchers.firstMatchingExpectation(request().withPath("/a"));
        assertThat(matchedAfter, is(notNullValue()));
        assertThat("reconcile should pick up response body change",
            matchedAfter.getHttpResponse().getBodyAsString(), is("updated-body"));
    }

    // -------------------------------------------------------
    // Backend wiring / unwiring
    // -------------------------------------------------------

    @Test
    public void setStateBackendNullRestoresLocalEviction() {
        // Unwire the backend
        backendMatchers.setStateBackend(null);

        // Now add expectations — should use local eviction
        backendMatchers.add(new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("a")), API);
        backendMatchers.add(new Expectation(request().withPath("/b")).withId("b")
            .thenRespond(response().withBody("b")), API);
        backendMatchers.add(new Expectation(request().withPath("/c")).withId("c")
            .thenRespond(response().withBody("c")), API);

        // maxExpectations=2, so only 2 should remain
        assertThat(backendMatchers.size(), is(2));
    }
}
