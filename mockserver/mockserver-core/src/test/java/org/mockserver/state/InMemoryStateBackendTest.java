package org.mockserver.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

public class InMemoryStateBackendTest {

    private InMemoryStateBackend backend;

    @Before
    public void setUp() {
        backend = new InMemoryStateBackend(100);
    }

    @After
    public void tearDown() {
        backend.close();
    }

    @Test
    public void shouldReturnExpectationsStore() {
        assertNotNull(backend.expectations());
        assertThat(backend.expectations(), instanceOf(InMemoryExpectationKeyValueStore.class));
    }

    @Test
    public void shouldReturnScenarioStatesStore() {
        assertNotNull(backend.scenarioStates());
        backend.scenarioStates().put("scenario1", "Started");
        Optional<Versioned<String>> result = backend.scenarioStates().get("scenario1");
        assertTrue(result.isPresent());
        assertThat(result.get().getValue(), is("Started"));
    }

    @Test
    public void shouldReturnCrudEntitiesStore() {
        KeyValueStore<ObjectNode> store1 = backend.crudEntities("ns1");
        KeyValueStore<ObjectNode> store2 = backend.crudEntities("ns2");
        assertNotNull(store1);
        assertNotNull(store2);
        // Different namespaces should return different stores
        ObjectNode node = new ObjectMapper().createObjectNode().put("id", "1");
        store1.put("item1", node);
        assertFalse(store2.get("item1").isPresent());
        assertTrue(store1.get("item1").isPresent());
    }

    @Test
    public void shouldReturnSameStoreForSameNamespace() {
        KeyValueStore<ObjectNode> store1 = backend.crudEntities("ns1");
        KeyValueStore<ObjectNode> store2 = backend.crudEntities("ns1");
        assertSame(store1, store2);
    }

    @Test
    public void shouldReturnBlobStore() {
        assertNotNull(backend.blobs());
        assertThat(backend.blobs(), instanceOf(InMemoryBlobStore.class));
    }

    @Test
    public void shouldReturnNodeId() {
        assertNotNull(backend.nodeId());
        assertFalse(backend.nodeId().isEmpty());
    }

    @Test
    public void shouldCloseWithoutError() {
        // close is no-op for in-memory, but should not throw
        backend.close();
    }
}
