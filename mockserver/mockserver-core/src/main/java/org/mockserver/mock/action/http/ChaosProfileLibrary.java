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
import java.util.regex.Pattern;

/**
 * Persisted, named library of reusable chaos experiment <em>profiles</em>.
 *
 * <p>A "profile" here is simply a saved chaos experiment definition — the exact
 * same JSON shape that {@code PUT /mockserver/chaosExperiment} accepts — stored
 * under a human-chosen name so it can be re-applied later without re-authoring
 * the JSON. The library is a template store, not the active-experiment store:
 * saving a profile does not start it; {@code apply(name)} starts it.
 *
 * <p><b>Storage:</b> profiles live in the {@link StateBackend}'s
 * {@code crudEntities("chaos-profiles")} key-value store, keyed by profile name.
 * This gives two properties for free:
 * <ul>
 *   <li><b>Survives server reset</b> — {@code HttpState.reset()} clears active
 *       chaos (registries, the running experiment) but intentionally does NOT
 *       clear this template store, so saved profiles outlive a reset. Unlike the
 *       chaos <em>registries</em> (which only attach a backend when clustered),
 *       the library always uses the backend store, so the single-node default
 *       backend persists profiles across resets too.</li>
 *   <li><b>Cluster-correct</b> — when the backend is clustered, profile
 *       saves/deletes replicate across the fleet via the same CRUD-entity
 *       replication used by the chaos registries.</li>
 * </ul>
 *
 * <p>Each stored value is the raw experiment-definition {@link ObjectNode}; the
 * profile name (the store key) overrides any {@code name} inside the body so the
 * saved-under name and the experiment name stay consistent.
 */
public class ChaosProfileLibrary {

    private static final Logger LOG = LoggerFactory.getLogger(ChaosProfileLibrary.class);

    /** CRUD-entity namespace under which saved profiles are stored. */
    public static final String BACKEND_NAMESPACE = "chaos-profiles";

    /**
     * Permitted profile-name characters. Names are used as storage keys and in
     * URL paths (percent-encoded by clients), so they are constrained to a safe,
     * unambiguous set: letters, digits, space, dot, underscore and hyphen. A
     * leading/trailing space is rejected so names round-trip cleanly. The set
     * intentionally allows spaces because the dashboard experiment-name field
     * (which doubles as the profile name) commonly contains them.
     */
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9._-]([A-Za-z0-9._ -]{0,126}[A-Za-z0-9._-])?");

    private final KeyValueStore<ObjectNode> store;

    public ChaosProfileLibrary(StateBackend backend) {
        this.store = backend.crudEntities(BACKEND_NAMESPACE);
    }

    /**
     * Validates a profile name. Returns {@code true} when the name is non-null
     * and made up only of letters, digits, space, dot, underscore and hyphen
     * (1–128 characters), with no leading or trailing space.
     */
    public static boolean isValidName(String name) {
        return name != null && VALID_NAME.matcher(name).matches();
    }

    /**
     * Saves (or replaces) a profile under {@code name}. The {@code definition}
     * is the experiment-definition JSON — the same shape accepted by
     * {@code PUT /mockserver/chaosExperiment}. The stored copy's {@code name}
     * field is normalised to {@code name} so the saved-under name wins.
     *
     * @throws IllegalArgumentException if the name is invalid or the definition is null
     */
    public void save(String name, JsonNode definition) {
        if (!isValidName(name)) {
            throw new IllegalArgumentException(
                "profile name must be 1-128 characters of letters, digits, space, '.', '_' or '-' "
                    + "(no leading or trailing space)");
        }
        if (definition == null || !definition.isObject()) {
            throw new IllegalArgumentException("profile body must be a JSON object experiment definition");
        }
        ObjectNode copy = definition.deepCopy();
        copy.put("name", name);
        store.put(name, copy);
    }

    /**
     * Returns the saved profile definition for {@code name}, or
     * {@code Optional.empty()} if no such profile exists.
     */
    public Optional<ObjectNode> get(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return store.get(name).map(Versioned::getValue);
    }

    /**
     * Returns the names of all saved profiles, in ascending name order.
     */
    public List<String> list() {
        List<String> names = new ArrayList<>();
        store.entries().forEach(entry -> names.add(entry.getKey()));
        names.sort(String::compareTo);
        return names;
    }

    /**
     * Removes the saved profile {@code name}. Returns {@code true} if a profile
     * was present and removed, {@code false} if no such profile existed.
     */
    public boolean delete(String name) {
        if (name == null) {
            return false;
        }
        return store.remove(name);
    }

    /**
     * Convenience for tests / callers that want a fresh ObjectMapper-parsed copy
     * of a stored definition. Not used on the request path.
     */
    static ObjectNode parse(String json) {
        try {
            JsonNode node = ObjectMapperFactory.createObjectMapper().readTree(json);
            return node.isObject() ? (ObjectNode) node : null;
        } catch (Exception e) {
            LOG.warn("failed to parse chaos profile json", e);
            return null;
        }
    }
}
