package org.mockserver.state;

import org.junit.Before;
import org.junit.Test;
import org.mockserver.collections.CircularPriorityQueue;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.SortableExpectationId;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static org.mockserver.mock.SortableExpectationId.EXPECTATION_SORTABLE_PRIORITY_COMPARATOR;

public class InMemoryExpectationKeyValueStoreTest {

    private InMemoryExpectationKeyValueStore store;

    @Before
    public void setUp() {
        store = new InMemoryExpectationKeyValueStore(5);
    }

    private Expectation expectation(String id, int priority) {
        return Expectation.when(HttpRequest.request("/path-" + id))
            .withId(id)
            .withPriority(priority)
            .thenRespond(HttpResponse.response().withBody("body-" + id));
    }

    @Test
    public void shouldPutAndGet() {
        Expectation exp = expectation("e1", 0);
        ExpectationEntry entry = new ExpectationEntry(exp);

        long version = store.put("e1", entry);
        assertThat(version, is(1L));

        Optional<Versioned<ExpectationEntry>> result = store.get("e1");
        assertTrue(result.isPresent());
        assertThat(result.get().getValue().getId(), is("e1"));
        assertThat(result.get().getVersion(), is(1L));
    }

    @Test
    public void shouldUpdateExistingEntry() {
        Expectation exp1 = expectation("e1", 0);
        store.put("e1", new ExpectationEntry(exp1));

        Expectation exp2 = expectation("e1", 5);
        long ver = store.put("e1", new ExpectationEntry(exp2));
        assertThat(ver, is(2L));
        assertThat(store.size(), is(1));
        assertThat(store.get("e1").get().getValue().getPriority(), is(5));
    }

    @Test
    public void shouldRemoveEntry() {
        store.put("e1", new ExpectationEntry(expectation("e1", 0)));
        assertTrue(store.remove("e1"));
        assertFalse(store.get("e1").isPresent());
        assertThat(store.size(), is(0));
    }

    @Test
    public void shouldCompareAndSetSuccessfully() {
        store.put("e1", new ExpectationEntry(expectation("e1", 0)));
        boolean ok = store.compareAndSet("e1", 1L, new ExpectationEntry(expectation("e1", 10)));
        assertTrue(ok);
        assertThat(store.get("e1").get().getValue().getPriority(), is(10));
        assertThat(store.get("e1").get().getVersion(), is(2L));
    }

    @Test
    public void shouldFailCompareAndSetWithWrongVersion() {
        store.put("e1", new ExpectationEntry(expectation("e1", 0)));
        boolean ok = store.compareAndSet("e1", 999L, new ExpectationEntry(expectation("e1", 10)));
        assertFalse(ok);
        assertThat(store.get("e1").get().getValue().getPriority(), is(0));
    }

    @Test
    public void shouldCompareAndRemoveSuccessfully() {
        store.put("e1", new ExpectationEntry(expectation("e1", 0)));
        assertTrue(store.compareAndRemove("e1", 1L));
        assertFalse(store.get("e1").isPresent());
    }

    /**
     * Verifies that the KV store's sorted iteration matches a standalone
     * CircularPriorityQueue for the same insertions -- proving identical
     * ordering (priority DESC, created ASC, id ASC).
     */
    @Test
    public void shouldMatchCircularPriorityQueueSortOrder() {
        // Create expectations with varying priorities
        Expectation e1 = expectation("e1", 0);
        Expectation e2 = expectation("e2", 5);
        Expectation e3 = expectation("e3", 0);
        Expectation e4 = expectation("e4", 10);

        // Insert into the KV store
        store.put("e1", new ExpectationEntry(e1));
        store.put("e2", new ExpectationEntry(e2));
        store.put("e3", new ExpectationEntry(e3));
        store.put("e4", new ExpectationEntry(e4));

        // Also insert into a standalone CPQ for comparison
        CircularPriorityQueue<String, ExpectationEntry, SortableExpectationId> cpq =
            new CircularPriorityQueue<>(
                5,
                EXPECTATION_SORTABLE_PRIORITY_COMPARATOR,
                entry -> new SortableExpectationId(entry.getId(), entry.getPriority(), entry.getCreated()),
                ExpectationEntry::getId
            );
        cpq.add(new ExpectationEntry(e1));
        cpq.add(new ExpectationEntry(e2));
        cpq.add(new ExpectationEntry(e3));
        cpq.add(new ExpectationEntry(e4));

        // Extract sorted IDs from both
        List<String> kvOrder = store.toSortedList().stream()
            .map(ExpectationEntry::getId)
            .collect(Collectors.toList());
        List<String> cpqOrder = cpq.toSortedList().stream()
            .map(ExpectationEntry::getId)
            .collect(Collectors.toList());

        assertThat(kvOrder, is(cpqOrder));
        // Priority 10 first, then 5, then 0s in created/id order
        assertThat(kvOrder.get(0), is("e4"));
        assertThat(kvOrder.get(1), is("e2"));
    }

    /**
     * Verifies that insertion-order eviction at a cap matches a standalone
     * CircularPriorityQueue's eviction -- when maxSize is exceeded, the
     * oldest insertions are evicted first.
     */
    @Test
    public void shouldEvictOldestWhenCapExceeded() {
        InMemoryExpectationKeyValueStore small = new InMemoryExpectationKeyValueStore(3);

        small.put("e1", new ExpectationEntry(expectation("e1", 0)));
        small.put("e2", new ExpectationEntry(expectation("e2", 0)));
        small.put("e3", new ExpectationEntry(expectation("e3", 0)));
        assertThat(small.size(), is(3));

        // Adding a 4th should evict e1 (oldest)
        small.put("e4", new ExpectationEntry(expectation("e4", 0)));
        assertThat(small.size(), is(3));
        assertFalse(small.get("e1").isPresent());
        assertTrue(small.get("e2").isPresent());
        assertTrue(small.get("e3").isPresent());
        assertTrue(small.get("e4").isPresent());
    }

    /**
     * Core leak proof: registering far more than maxSize entries must NOT
     * grow the internal versions map without bound. Before the eviction-
     * listener fix, every overflow-evicted key leaked its AtomicLong version
     * entry forever; the versions map must now stay bounded by maxSize.
     */
    @Test
    public void shouldNotLeakVersionsForOverflowEvictedEntries() throws Exception {
        int maxSize = 4;
        InMemoryExpectationKeyValueStore small = new InMemoryExpectationKeyValueStore(maxSize);

        // register 200 distinct entries through a maxSize-4 store
        for (int i = 0; i < 200; i++) {
            String id = "e" + i;
            small.put(id, new ExpectationEntry(expectation(id, 0)));
        }

        // the live queue is capped
        assertThat(small.size(), is(maxSize));
        // and the versions map must be bounded too (no leak of evicted keys)
        assertThat(versionsSize(small), is(maxSize));
        // and it must contain EXACTLY the last maxSize inserted ids — proving
        // eviction pruned the right (oldest) versions entries, not just that
        // the count happens to equal maxSize
        assertThat(versionsKeys(small), containsInAnyOrder("e196", "e197", "e198", "e199"));

        // also via putIfAbsent churn the map stays bounded
        for (int i = 200; i < 400; i++) {
            String id = "e" + i;
            small.putIfAbsent(id, new ExpectationEntry(expectation(id, 0)));
        }
        assertThat(small.size(), is(maxSize));
        assertThat(versionsSize(small), is(maxSize));
        assertThat(versionsKeys(small), containsInAnyOrder("e396", "e397", "e398", "e399"));
    }

    /**
     * Explicit remove must still prune the versions entry (regression guard:
     * the eviction listener only handles overflow, not explicit remove).
     */
    @Test
    public void shouldPruneVersionsOnExplicitRemove() throws Exception {
        store.put("e1", new ExpectationEntry(expectation("e1", 0)));
        store.put("e2", new ExpectationEntry(expectation("e2", 0)));
        assertThat(versionsSize(store), is(2));
        store.remove("e1");
        assertThat(versionsSize(store), is(1));
    }

    private static int versionsSize(InMemoryExpectationKeyValueStore target) throws Exception {
        return versionsMap(target).size();
    }

    private static java.util.Set<String> versionsKeys(InMemoryExpectationKeyValueStore target) throws Exception {
        return new java.util.HashSet<>(versionsMap(target).keySet());
    }

    // White-box access to the internal versions map to prove the eviction
    // leak fix. The field name "versions" must stay in sync with the
    // InMemoryExpectationKeyValueStore field if it is ever renamed.
    @SuppressWarnings("unchecked")
    private static java.util.Map<String, ?> versionsMap(InMemoryExpectationKeyValueStore target) throws Exception {
        java.lang.reflect.Field field = InMemoryExpectationKeyValueStore.class.getDeclaredField("versions");
        field.setAccessible(true);
        return (java.util.Map<String, ?>) field.get(target);
    }

    @Test
    public void shouldClearAllEntries() {
        store.put("e1", new ExpectationEntry(expectation("e1", 0)));
        store.put("e2", new ExpectationEntry(expectation("e2", 0)));
        store.clear();
        assertThat(store.size(), is(0));
    }

    @Test
    public void shouldStreamEntries() {
        store.put("e1", new ExpectationEntry(expectation("e1", 0)));
        store.put("e2", new ExpectationEntry(expectation("e2", 0)));
        store.put("e3", new ExpectationEntry(expectation("e3", 0)));

        List<String> keys = store.entries()
            .map(KeyValueStore.Entry::getKey)
            .sorted()
            .collect(Collectors.toList());
        assertThat(keys, containsInAnyOrder("e1", "e2", "e3"));
    }
}
