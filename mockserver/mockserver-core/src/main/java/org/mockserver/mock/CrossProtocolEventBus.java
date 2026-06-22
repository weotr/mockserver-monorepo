package org.mockserver.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.model.CrossProtocolScenario;
import org.mockserver.model.CrossProtocolTrigger;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.state.KeyValueStore;
import org.mockserver.state.StateBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-process event bus that bridges protocol events to scenario state
 * transitions. Listeners (registered via {@link #register}) associate a
 * {@link CrossProtocolTrigger} + optional match pattern with a scenario
 * name and target state. When an event is {@link #fire}d, every matching
 * listener triggers a {@link ScenarioManager#setState} call.
 * <p>
 * Thread-safe: uses {@link ConcurrentHashMap} and {@link CopyOnWriteArrayList}.
 * <p>
 * <b>Fleet-awareness (G11 follow-up):</b> when a clustered {@link StateBackend}
 * is wired via {@link #setStateBackend(StateBackend)}, registrations (trigger-to-scenario
 * mappings) are replicated via the backend's {@code crudEntities("cross-protocol-bus")}
 * store: register/unregister/reset write-through to the backend, and a separate
 * {@link org.mockserver.state.InvalidationListener} rebuilds the node-local bus
 * from the backend on remote writes. The {@link #fire} path remains purely
 * node-local for zero-overhead event dispatching. When no backend is set or the
 * backend is not clustered, behaviour is identical to the pre-clustering
 * node-local-only bus.
 */
public class CrossProtocolEventBus {

    private static final Logger LOG = LoggerFactory.getLogger(CrossProtocolEventBus.class);
    static final String BACKEND_NAMESPACE = "cross-protocol-bus";

    private static final CrossProtocolEventBus INSTANCE = new CrossProtocolEventBus();

    private final ConcurrentHashMap<CrossProtocolTrigger, List<CrossProtocolScenario>> listeners =
        new ConcurrentHashMap<>();
    private volatile ScenarioManager scenarioManager;

    // G11 follow-up: optional clustered backend for fleet replication
    private volatile KeyValueStore<ObjectNode> backendStore;

    /**
     * Creates a fresh, non-singleton instance. Public for testing (e.g.
     * per-test isolation in the clustered 2-node integration test).
     * <p>
     * <b>Singleton-coupling note:</b> the {@link org.mockserver.state.InvalidationListener}
     * registered by {@link org.mockserver.mock.HttpState} targets
     * {@link #getInstance()} (the static singleton). A non-singleton bus
     * (as used by tests) must register its own invalidation listener on
     * the backend to receive reconciliation callbacks.
     */
    public CrossProtocolEventBus() {
    }

    public static CrossProtocolEventBus getInstance() {
        return INSTANCE;
    }

    public void setScenarioManager(ScenarioManager manager) {
        this.scenarioManager = manager;
    }

    /**
     * Returns the live {@link ScenarioManager} wired by {@link HttpState},
     * or {@code null} if no server has been initialised yet. Used by the
     * template engines (via the {@code scenario} template helper) to read
     * and write scenario state from response templates, so a value captured
     * in one request can drive a later response. Returning the live instance
     * (not a copy) ensures template writes are immediately visible to
     * subsequent matcher {@link ScenarioManager#matchesState} checks and
     * vice-versa.
     */
    public ScenarioManager getScenarioManager() {
        return scenarioManager;
    }

    /**
     * Wires the clustered state backend for fleet-wide registration replication.
     * When the backend {@link StateBackend#isClustered() isClustered()},
     * registrations are replicated via the backend's CRUD entity store, and
     * an {@link org.mockserver.state.InvalidationListener} is registered to
     * rebuild the node-local bus on remote writes. When the backend is not
     * clustered, this method is a no-op -- the bus stays purely node-local.
     */
    public void setStateBackend(StateBackend backend) {
        if (backend != null && backend.isClustered()) {
            this.backendStore = backend.crudEntities(BACKEND_NAMESPACE);
        }
    }

    public void register(CrossProtocolScenario scenario) {
        if (scenario == null || scenario.getTrigger() == null) {
            return;
        }
        listeners.computeIfAbsent(scenario.getTrigger(), k -> new CopyOnWriteArrayList<>()).add(scenario);
        writeToBackend(scenario);
    }

    public void unregister(CrossProtocolScenario scenario) {
        if (scenario == null || scenario.getTrigger() == null) {
            return;
        }
        List<CrossProtocolScenario> list = listeners.get(scenario.getTrigger());
        if (list != null) {
            list.remove(scenario);
        }
        removeFromBackend(scenario);
    }

    /**
     * Fire an event. For each matching registered scenario, calls
     * {@link ScenarioManager#setState(String, String)}.
     * <p>
     * This path is purely node-local -- no backend round-trip. In a clustered
     * deployment, each node fires from its own replicated copy of the
     * registration set.
     *
     * @param trigger    the event type
     * @param identifier the relevant identifier (e.g. DNS query name,
     *                   gRPC service name, HTTP path, WebSocket URL)
     */
    public void fire(CrossProtocolTrigger trigger, String identifier) {
        if (scenarioManager == null) {
            return;
        }
        List<CrossProtocolScenario> scenarios = listeners.get(trigger);
        if (scenarios == null || scenarios.isEmpty()) {
            return;
        }
        for (CrossProtocolScenario scenario : scenarios) {
            if (matches(scenario, identifier)) {
                try {
                    scenarioManager.setState(scenario.getScenarioName(), scenario.getTargetState());
                } catch (Exception ignored) {
                    // swallow to avoid event-bus failures disrupting request processing
                }
            }
        }
    }

    private boolean matches(CrossProtocolScenario scenario, String identifier) {
        String pattern = scenario.getMatchPattern();
        if (pattern == null || pattern.isEmpty()) {
            return true;
        }
        if (identifier == null) {
            return false;
        }
        return identifier.contains(pattern);
    }

    public void reset() {
        listeners.clear();
        clearBackend();
    }

    // --- G11 follow-up: backend write-through and reconciliation ---

    /**
     * Generates a unique backend key for a registration. The key encodes
     * the trigger, scenario name, target state, and match pattern so that
     * each distinct registration has its own slot in the KV store.
     * <p>
     * Uses a length-prefixed encoding for the three user-supplied components
     * (scenarioName, targetState, matchPattern) to avoid delimiter collisions.
     * The trigger is an enum name (safe, no user-controlled characters).
     * <p>
     * Format: {@code <trigger>|<len>:<scenarioName>:<len>:<targetState>:<len>:<matchPattern>}
     * where null values are encoded as the empty string (length 0). The key
     * need not be parseable -- it is only used for put/remove identity in the
     * KV store. Length-prefixing makes the encoding provably collision-free
     * for all possible user-supplied strings, including those containing
     * {@code |} (regex alternation) or {@code :} (colons).
     * <p>
     * Mirrors the lossless encoding in {@link ScenarioManager.ScenarioKey#toString()}.
     */
    static String backendKey(CrossProtocolScenario scenario) {
        String name = scenario.getScenarioName() != null ? scenario.getScenarioName() : "";
        String state = scenario.getTargetState() != null ? scenario.getTargetState() : "";
        String pattern = scenario.getMatchPattern() != null ? scenario.getMatchPattern() : "";
        return scenario.getTrigger().name()
            + "|" + name.length() + ":" + name
            + ":" + state.length() + ":" + state
            + ":" + pattern.length() + ":" + pattern;
    }

    /**
     * Writes a registration to the clustered backend store.
     * No-op when no clustered backend is configured.
     */
    private void writeToBackend(CrossProtocolScenario scenario) {
        KeyValueStore<ObjectNode> store = this.backendStore;
        if (store == null) {
            return;
        }
        try {
            ObjectMapper mapper = ObjectMapperFactory.createObjectMapper();
            ObjectNode node = mapper.createObjectNode();
            node.put("trigger", scenario.getTrigger().name());
            node.put("scenarioName", scenario.getScenarioName());
            if (scenario.getTargetState() != null) {
                node.put("targetState", scenario.getTargetState());
            }
            if (scenario.getMatchPattern() != null) {
                node.put("matchPattern", scenario.getMatchPattern());
            }
            store.put(backendKey(scenario), node);
        } catch (Exception e) {
            LOG.warn("failed to write cross-protocol registration to backend for scenario={}",
                scenario.getScenarioName(), e);
        }
    }

    /**
     * Removes a registration from the clustered backend store.
     * No-op when no clustered backend is configured.
     */
    private void removeFromBackend(CrossProtocolScenario scenario) {
        KeyValueStore<ObjectNode> store = this.backendStore;
        if (store == null) {
            return;
        }
        try {
            store.remove(backendKey(scenario));
        } catch (Exception e) {
            LOG.warn("failed to remove cross-protocol registration from backend for scenario={}",
                scenario.getScenarioName(), e);
        }
    }

    /**
     * Clears all registrations from the clustered backend store.
     * No-op when no clustered backend is configured.
     */
    private void clearBackend() {
        KeyValueStore<ObjectNode> store = this.backendStore;
        if (store == null) {
            return;
        }
        try {
            store.clear();
        } catch (Exception e) {
            LOG.warn("failed to clear cross-protocol bus backend", e);
        }
    }

    /**
     * Rebuilds the node-local listener map from the backend store. Called by
     * the {@link org.mockserver.state.InvalidationListener} when a remote
     * write is detected. Thread-safe but <b>weakly-consistent</b>: the
     * removeIf + put loop is NOT atomic, so a concurrent {@link #fire} call
     * may miss a registration for one cycle. This matches the existing
     * {@link java.util.concurrent.ConcurrentHashMap} weakly-consistent
     * iteration semantics and is acceptable for event-bus registration
     * convergence.
     */
    public void reconcileFromBackend() {
        KeyValueStore<ObjectNode> store = this.backendStore;
        if (store == null) {
            return;
        }
        try {
            // Build new trigger-to-scenarios map from backend
            Map<CrossProtocolTrigger, List<CrossProtocolScenario>> newListeners = new HashMap<>();
            store.entries().forEach(entry -> {
                try {
                    ObjectNode node = entry.getValue();
                    CrossProtocolTrigger trigger = CrossProtocolTrigger.valueOf(node.get("trigger").asText());
                    CrossProtocolScenario scenario = CrossProtocolScenario.crossProtocolScenario()
                        .withTrigger(trigger)
                        .withScenarioName(node.get("scenarioName").asText());
                    if (node.has("targetState")) {
                        scenario.withTargetState(node.get("targetState").asText());
                    }
                    if (node.has("matchPattern")) {
                        scenario.withMatchPattern(node.get("matchPattern").asText());
                    }
                    newListeners.computeIfAbsent(trigger, k -> new CopyOnWriteArrayList<>()).add(scenario);
                } catch (Exception e) {
                    LOG.warn("failed to deserialize cross-protocol registration key={}", entry.getKey(), e);
                }
            });
            // Replace local map: remove triggers not in backend, add/update from backend
            listeners.keySet().removeIf(k -> !newListeners.containsKey(k));
            for (Map.Entry<CrossProtocolTrigger, List<CrossProtocolScenario>> e : newListeners.entrySet()) {
                listeners.put(e.getKey(), e.getValue());
            }
        } catch (Exception e) {
            LOG.warn("failed to reconcile cross-protocol bus from backend", e);
        }
    }
}
