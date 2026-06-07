package org.mockserver.mock;

import org.mockserver.model.CrossProtocolScenario;
import org.mockserver.model.CrossProtocolTrigger;

import java.util.List;
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
 */
public class CrossProtocolEventBus {

    private static final CrossProtocolEventBus INSTANCE = new CrossProtocolEventBus();

    private final ConcurrentHashMap<CrossProtocolTrigger, List<CrossProtocolScenario>> listeners =
        new ConcurrentHashMap<>();
    private volatile ScenarioManager scenarioManager;

    /**
     * Package-private constructor for testing (creates a fresh, non-singleton instance).
     */
    CrossProtocolEventBus() {
    }

    public static CrossProtocolEventBus getInstance() {
        return INSTANCE;
    }

    public void setScenarioManager(ScenarioManager manager) {
        this.scenarioManager = manager;
    }

    public void register(CrossProtocolScenario scenario) {
        if (scenario == null || scenario.getTrigger() == null) {
            return;
        }
        listeners.computeIfAbsent(scenario.getTrigger(), k -> new CopyOnWriteArrayList<>()).add(scenario);
    }

    public void unregister(CrossProtocolScenario scenario) {
        if (scenario == null || scenario.getTrigger() == null) {
            return;
        }
        List<CrossProtocolScenario> list = listeners.get(scenario.getTrigger());
        if (list != null) {
            list.remove(scenario);
        }
    }

    /**
     * Fire an event. For each matching registered scenario, calls
     * {@link ScenarioManager#setState(String, String)}.
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
    }
}
