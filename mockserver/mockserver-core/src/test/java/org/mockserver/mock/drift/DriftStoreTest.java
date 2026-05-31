package org.mockserver.mock.drift;

import org.junit.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.core.Is.is;

public class DriftStoreTest {

    @Test
    public void storesAndRetrievesRecords() {
        DriftStore store = new DriftStore(10);
        store.add(new DriftRecord().setExpectationId("exp1").setDriftType(DriftType.STATUS));
        assertThat(store.getRecent(10), hasSize(1));
        assertThat(store.size(), is(1));
    }

    @Test
    public void evictsOldestWhenFull() {
        DriftStore store = new DriftStore(3);
        for (int i = 0; i < 5; i++) {
            store.add(new DriftRecord().setExpectationId("exp" + i).setEpochTimeMs(i));
        }
        assertThat(store.size(), is(3));
        // Most recent 3 should be exp4, exp3, exp2 (descending)
        List<DriftRecord> recent = store.getRecent(3);
        assertThat(recent, hasSize(3));
        assertThat(recent.get(0).getExpectationId(), is("exp4"));
        assertThat(recent.get(1).getExpectationId(), is("exp3"));
        assertThat(recent.get(2).getExpectationId(), is("exp2"));
    }

    @Test
    public void filtersByExpectationId() {
        DriftStore store = new DriftStore(100);
        store.add(new DriftRecord().setExpectationId("exp1").setDriftType(DriftType.STATUS));
        store.add(new DriftRecord().setExpectationId("exp2").setDriftType(DriftType.SCHEMA_FIELD_ADDED));
        store.add(new DriftRecord().setExpectationId("exp1").setDriftType(DriftType.HEADER_ADDED));
        assertThat(store.getByExpectationId("exp1"), hasSize(2));
        assertThat(store.getByExpectationId("exp2"), hasSize(1));
        assertThat(store.getByExpectationId("nonexistent"), is(empty()));
    }

    @Test
    public void clearRemovesAll() {
        DriftStore store = new DriftStore(100);
        store.add(new DriftRecord().setExpectationId("e1").setDriftType(DriftType.STATUS));
        store.add(new DriftRecord().setExpectationId("e2").setDriftType(DriftType.HEADER_REMOVED));
        store.clear();
        assertThat(store.size(), is(0));
        assertThat(store.getRecent(10), is(empty()));
    }

    @Test
    public void getRecentRespectsLimit() {
        DriftStore store = new DriftStore(100);
        for (int i = 0; i < 10; i++) {
            store.add(new DriftRecord().setExpectationId("exp" + i));
        }
        assertThat(store.getRecent(3), hasSize(3));
        assertThat(store.getRecent(100), hasSize(10));
    }
}
