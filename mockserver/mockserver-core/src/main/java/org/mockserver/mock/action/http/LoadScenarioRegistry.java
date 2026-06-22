package org.mockserver.mock.action.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.state.KeyValueStore;
import org.mockserver.state.StateBackend;
import org.mockserver.state.Versioned;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Persisted, named registry of load scenario <em>definitions</em>.
 *
 * <p>A scenario is <em>loaded</em> (registered) here under its {@link org.mockserver.load.LoadScenario#getName() name}
 * — the unique registry key — by {@code PUT /mockserver/loadScenario}. Loading stores the definition
 * but does not run anything; an explicit {@code PUT /mockserver/loadScenario/start} triggers one or
 * more registered scenarios to run. This mirrors the saved-chaos-profile library
 * ({@link ChaosProfileLibrary}): a template store, not the active-run store.
 *
 * <p><b>Storage:</b> definitions live in the {@link StateBackend}'s
 * {@code crudEntities("load-scenarios")} key-value store, keyed by scenario name. This gives:
 * <ul>
 *   <li><b>Survives reset</b> — {@code HttpState.reset()} stops active runs but does not clear the
 *       registry, so loaded scenarios outlive a reset and can be re-triggered.</li>
 *   <li><b>Cluster-correct</b> — when the backend is clustered, loads/deletes replicate across the
 *       fleet via the same CRUD-entity replication used by the chaos registries.</li>
 *   <li><b>Preloadable</b> — startup preloading writes definitions here so a node can boot with
 *       scenarios staged in the LOADED state, ready to trigger.</li>
 * </ul>
 *
 * <p>Each stored value is the scenario's JSON {@link ObjectNode} (the same shape accepted by the PUT
 * endpoint); the registry key (the store key) overrides any {@code name} inside the body so the
 * loaded-under name wins. Loading the same name replaces the prior definition.
 */
public class LoadScenarioRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(LoadScenarioRegistry.class);

    /** CRUD-entity namespace under which loaded scenario definitions are stored. */
    public static final String BACKEND_NAMESPACE = "load-scenarios";

    private final KeyValueStore<ObjectNode> store;

    public LoadScenarioRegistry(StateBackend backend) {
        this.store = backend.crudEntities(BACKEND_NAMESPACE);
    }

    /**
     * Loads (or replaces) a scenario definition under {@code name}. The {@code definition} is the
     * scenario JSON — the same shape accepted by {@code PUT /mockserver/loadScenario}. The stored
     * copy's {@code name} field is normalised to {@code name} so the loaded-under name wins.
     *
     * @throws IllegalArgumentException if the name is blank or the definition is not a JSON object
     */
    public void load(String name, JsonNode definition) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("scenario name is required");
        }
        if (definition == null || !definition.isObject()) {
            throw new IllegalArgumentException("scenario body must be a JSON object load scenario definition");
        }
        ObjectNode copy = definition.deepCopy();
        copy.put("name", name);
        store.put(name, copy);
    }

    /**
     * Returns the loaded scenario definition for {@code name}, or {@code Optional.empty()} if no such
     * scenario is registered.
     */
    public Optional<ObjectNode> get(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return store.get(name).map(Versioned::getValue);
    }

    /** Returns the names of all registered scenarios, in ascending name order. */
    public List<String> list() {
        List<String> names = new ArrayList<>();
        store.entries().forEach(entry -> names.add(entry.getKey()));
        names.sort(String::compareTo);
        return names;
    }

    /** True if a scenario is registered under {@code name}. */
    public boolean contains(String name) {
        return name != null && store.get(name).isPresent();
    }

    /**
     * Removes the registered scenario {@code name}. Returns {@code true} if a scenario was present and
     * removed, {@code false} if no such scenario existed.
     */
    public boolean delete(String name) {
        if (name == null) {
            return false;
        }
        return store.remove(name);
    }

    /** Removes all registered scenarios. */
    public void clear() {
        store.clear();
    }

    /** Convenience for tests / preload: parse a JSON string into an ObjectNode (or null on failure). */
    static ObjectNode parse(String json) {
        try {
            JsonNode node = ObjectMapperFactory.createObjectMapper().readTree(json);
            return node.isObject() ? (ObjectNode) node : null;
        } catch (Exception e) {
            LOG.warn("failed to parse load scenario json", e);
            return null;
        }
    }
}
