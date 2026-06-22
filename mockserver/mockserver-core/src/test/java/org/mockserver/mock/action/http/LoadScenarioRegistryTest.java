package org.mockserver.mock.action.http;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.state.StateBackend;
import org.mockserver.state.StateBackendFactory;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Unit tests for {@link LoadScenarioRegistry}, the persisted named registry of load scenario
 * definitions backed by the state backend's CRUD-entity store (mirrors {@code ChaosProfileLibrary}).
 */
public class LoadScenarioRegistryTest {

    private LoadScenarioRegistry registry;

    @Before
    public void setUp() {
        StateBackend backend = StateBackendFactory.create(Configuration.configuration());
        registry = new LoadScenarioRegistry(backend);
    }

    private static ObjectNode def(String name) {
        ObjectNode node = ObjectMapperFactory.createObjectMapper().createObjectNode();
        node.put("name", name);
        node.putObject("profile");
        return node;
    }

    @Test
    public void loadThenGetReturnsDefinition() {
        registry.load("alpha", def("alpha"));
        Optional<ObjectNode> got = registry.get("alpha");
        assertThat(got.isPresent(), is(true));
        assertThat(got.get().get("name").asText(), is("alpha"));
    }

    @Test
    public void listReturnsNamesSorted() {
        registry.load("zeta", def("zeta"));
        registry.load("alpha", def("alpha"));
        List<String> names = registry.list();
        assertThat(names, contains("alpha", "zeta"));
    }

    @Test
    public void loadSameNameReplaces() {
        registry.load("dup", def("dup"));
        ObjectNode replacement = def("dup");
        replacement.put("marker", "v2");
        registry.load("dup", replacement);
        assertThat(registry.list().size(), is(1));
        assertThat(registry.get("dup").get().get("marker").asText(), is("v2"));
    }

    @Test
    public void nameInBodyIsNormalisedToKey() {
        ObjectNode mismatched = def("inner-name");
        registry.load("outer-name", mismatched);
        assertThat(registry.get("outer-name").get().get("name").asText(), is("outer-name"));
    }

    @Test
    public void deleteRemoves() {
        registry.load("gone", def("gone"));
        assertThat(registry.delete("gone"), is(true));
        assertThat(registry.contains("gone"), is(false));
        assertThat(registry.delete("gone"), is(false));
    }

    @Test
    public void clearRemovesAll() {
        registry.load("a", def("a"));
        registry.load("b", def("b"));
        registry.clear();
        assertThat(registry.list(), is(empty()));
    }

    @Test
    public void blankNameRejected() {
        try {
            registry.load("  ", def("x"));
            throw new AssertionError("expected rejection of blank name");
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage(), containsString("name is required"));
        }
    }
}
