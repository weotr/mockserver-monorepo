package org.mockserver.async.subscribe;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Tests for {@link BoundedMessageStore}.
 */
public class BoundedMessageStoreTest {

    @Test
    public void shouldStoreMessagesUpToCapacity() {
        BoundedMessageStore store = new BoundedMessageStore(3);

        store.add(msg("ch", "p0"));
        store.add(msg("ch", "p1"));
        store.add(msg("ch", "p2"));

        assertThat(store.size(), is(3));
        List<RecordedMessage> snapshot = store.snapshot();
        assertThat(snapshot, hasSize(3));
        assertThat(snapshot.get(0).getPayload(), is("p0"));
        assertThat(snapshot.get(2).getPayload(), is("p2"));
    }

    @Test
    public void shouldEvictOldestWhenCapReached() {
        BoundedMessageStore store = new BoundedMessageStore(3);

        store.add(msg("ch", "p0"));
        store.add(msg("ch", "p1"));
        store.add(msg("ch", "p2"));
        store.add(msg("ch", "p3")); // evicts p0
        store.add(msg("ch", "p4")); // evicts p1

        assertThat(store.size(), is(3));
        List<RecordedMessage> snapshot = store.snapshot();
        assertThat(snapshot.get(0).getPayload(), is("p2"));
        assertThat(snapshot.get(1).getPayload(), is("p3"));
        assertThat(snapshot.get(2).getPayload(), is("p4"));
    }

    @Test
    public void shouldReturnEmptySnapshotWhenEmpty() {
        BoundedMessageStore store = new BoundedMessageStore(10);
        assertThat(store.snapshot(), is(empty()));
        assertThat(store.size(), is(0));
    }

    @Test
    public void shouldClearAllMessages() {
        BoundedMessageStore store = new BoundedMessageStore(10);
        store.add(msg("ch", "p0"));
        store.add(msg("ch", "p1"));

        store.clear();

        assertThat(store.size(), is(0));
        assertThat(store.snapshot(), is(empty()));
    }

    @Test
    public void snapshotShouldBeIndependentOfStore() {
        BoundedMessageStore store = new BoundedMessageStore(10);
        store.add(msg("ch", "p0"));

        List<RecordedMessage> snapshot = store.snapshot();
        store.add(msg("ch", "p1"));

        // Snapshot should not reflect the newly added message
        assertThat(snapshot, hasSize(1));
        assertThat(store.size(), is(2));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectZeroMaxSize() {
        new BoundedMessageStore(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectNegativeMaxSize() {
        new BoundedMessageStore(-1);
    }

    @Test
    public void shouldWorkWithCapacityOfOne() {
        BoundedMessageStore store = new BoundedMessageStore(1);

        store.add(msg("ch", "p0"));
        assertThat(store.snapshot().get(0).getPayload(), is("p0"));

        store.add(msg("ch", "p1")); // evicts p0
        assertThat(store.size(), is(1));
        assertThat(store.snapshot().get(0).getPayload(), is("p1"));
    }

    @Test
    public void shouldUseDefaultCapacity() {
        BoundedMessageStore store = new BoundedMessageStore();
        // Just verify it doesn't throw and has the expected default
        for (int i = 0; i < BoundedMessageStore.DEFAULT_MAX_RECORDED_MESSAGES + 10; i++) {
            store.add(msg("ch", "p" + i));
        }
        assertThat(store.size(), is(BoundedMessageStore.DEFAULT_MAX_RECORDED_MESSAGES));
        // Oldest 10 should have been evicted
        List<RecordedMessage> snapshot = store.snapshot();
        assertThat(snapshot.get(0).getPayload(), is("p10"));
    }

    private static RecordedMessage msg(String channel, String payload) {
        return new RecordedMessage(channel, null, payload, Collections.emptyMap());
    }
}
