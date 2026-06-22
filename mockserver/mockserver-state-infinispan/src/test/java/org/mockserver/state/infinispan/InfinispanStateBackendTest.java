package org.mockserver.state.infinispan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockserver.state.*;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link InfinispanStateBackend} in single-node LOCAL mode.
 * Verifies that all stores are operational and that no JGroups network
 * transport is started (the test would fail with bind exceptions if
 * JGroups tried to open a network socket).
 */
class InfinispanStateBackendTest {

    private InfinispanStateBackend backend;

    @BeforeEach
    void setUp() {
        backend = new InfinispanStateBackend(100);
    }

    @AfterEach
    void tearDown() {
        backend.close();
    }

    @Test
    void shouldReturnExpectationsStore() {
        assertNotNull(backend.expectations());
        assertThat(backend.expectations(), instanceOf(InfinispanKeyValueStore.class));
    }

    @Test
    void shouldReturnScenarioStatesStore() {
        assertNotNull(backend.scenarioStates());
        backend.scenarioStates().put("scenario1", "Started");
        Optional<Versioned<String>> result = backend.scenarioStates().get("scenario1");
        assertTrue(result.isPresent());
        assertThat(result.get().getValue(), is("Started"));
    }

    @Test
    void shouldReturnCrudEntitiesStore() {
        KeyValueStore<ObjectNode> store1 = backend.crudEntities("ns1");
        KeyValueStore<ObjectNode> store2 = backend.crudEntities("ns2");
        assertNotNull(store1);
        assertNotNull(store2);
        ObjectNode node = new ObjectMapper().createObjectNode().put("id", "1");
        store1.put("item1", node);
        assertFalse(store2.get("item1").isPresent());
        assertTrue(store1.get("item1").isPresent());
    }

    @Test
    void shouldReturnSameStoreForSameNamespace() {
        KeyValueStore<ObjectNode> store1 = backend.crudEntities("ns1");
        KeyValueStore<ObjectNode> store2 = backend.crudEntities("ns1");
        assertSame(store1, store2);
    }

    @Test
    void shouldReturnBlobStore() {
        assertNotNull(backend.blobs());
        assertThat(backend.blobs(), instanceOf(InfinispanBlobStore.class));
    }

    @Test
    void shouldStoreAndRetrieveBlobs() {
        backend.blobs().put("test/blob1", new byte[]{1, 2, 3}, java.util.Collections.emptyMap());
        Optional<Blob> blob = backend.blobs().get("test/blob1");
        assertTrue(blob.isPresent());
        assertThat(blob.get().getData(), is(new byte[]{1, 2, 3}));
    }

    @Test
    void shouldReturnNodeId() {
        assertNotNull(backend.nodeId());
        assertFalse(backend.nodeId().isEmpty());
    }

    @Test
    void shouldReportSingleNodeClusterInfoInLocalMode() {
        // LOCAL mode has no JGroups transport, so clusterInfo() falls back to
        // the degenerate single-node snapshot (this node only, not clustered).
        ClusterInfo info = backend.clusterInfo();
        assertNotNull(info);
        assertFalse(info.clustered(), "LOCAL mode is not clustered");
        assertThat(info.nodeId(), is(backend.nodeId()));
        assertThat(info.coordinator(), is(backend.nodeId()));
        assertThat(info.members(), hasSize(1));
        ClusterInfo.Member member = info.members().get(0);
        assertThat(member.id(), is(backend.nodeId()));
        assertTrue(member.coordinator(), "the single member is the coordinator");
        assertTrue(member.local(), "the single member is local");
    }

    @Test
    void shouldCloseWithoutError() {
        backend.close();
        // second close should not throw
        backend.close();
    }

    @Test
    void shouldFireInvalidationListenerOnExpectationChange() {
        AtomicInteger changedCount = new AtomicInteger(0);
        backend.addInvalidationListener(new InvalidationListener() {
            @Override
            public void onChanged(String key) {
                changedCount.incrementAndGet();
            }

            @Override
            public void onCleared() {
            }
        });

        backend.scenarioStates().put("s1", "state1");
        assertThat(changedCount.get(), is(1));
    }
}
