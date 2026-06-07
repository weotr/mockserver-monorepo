package org.mockserver.mock;

import org.mockserver.model.Delay;
import org.mockserver.model.TimedScenarioTransition;
import org.mockserver.scheduler.Scheduler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class ScenarioManager {

    public static final String STARTED = "Started";
    // TODO(G10-clustering): this node-local ConcurrentHashMap is NOT replicated
    // across cluster nodes. Although the StateBackend's scenarioStates KV store
    // IS replicated, ScenarioManager maintains its own in-memory map for
    // matchesAndTransition() / transitionState() which bypasses the backend.
    // Expectations using scenario sequencing (scenarioName + scenarioState /
    // newScenarioState) should NOT rely on cross-node state consistency until
    // this is wired through the clustered backend (planned follow-up).
    private final ConcurrentHashMap<ScenarioKey, String> scenarioStates = new ConcurrentHashMap<>();

    // --- Composite key ---

    static final class ScenarioKey {
        private final String scenarioName;
        private final String isolation; // null = legacy single-key behaviour

        ScenarioKey(String scenarioName, String isolation) {
            this.scenarioName = scenarioName;
            this.isolation = isolation;
        }

        String getScenarioName() {
            return scenarioName;
        }

        String getIsolation() {
            return isolation;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            ScenarioKey that = (ScenarioKey) o;
            return Objects.equals(scenarioName, that.scenarioName) &&
                Objects.equals(isolation, that.isolation);
        }

        @Override
        public int hashCode() {
            return Objects.hash(scenarioName, isolation);
        }

        @Override
        public String toString() {
            if (isolation == null) {
                return scenarioName;
            }
            return scenarioName + "[" + isolation + "]";
        }
    }

    // --- Legacy single-arg methods (backward-compatible, delegation to composite) ---

    public String getState(String scenarioName) {
        return getState(scenarioName, null);
    }

    public void setState(String scenarioName, String state) {
        setState(scenarioName, null, state);
    }

    public boolean matchesState(String scenarioName, String requiredState) {
        return matchesState(scenarioName, null, requiredState);
    }

    public boolean matchesAndTransition(String scenarioName, String requiredState, String newState) {
        return matchesAndTransition(scenarioName, null, requiredState, newState);
    }

    public void transitionState(String scenarioName, String newState) {
        transitionState(scenarioName, null, newState);
    }

    // --- Composite-key overloads ---

    public String getState(String scenarioName, String isolation) {
        if (scenarioName == null) {
            return STARTED;
        }
        return scenarioStates.getOrDefault(new ScenarioKey(scenarioName, isolation), STARTED);
    }

    public void setState(String scenarioName, String isolation, String state) {
        if (scenarioName == null || state == null) {
            return;
        }
        scenarioStates.put(new ScenarioKey(scenarioName, isolation), state);
    }

    public boolean matchesState(String scenarioName, String isolation, String requiredState) {
        if (scenarioName == null || requiredState == null) {
            return true;
        }
        return requiredState.equals(getState(scenarioName, isolation));
    }

    public boolean matchesAndTransition(String scenarioName, String isolation, String requiredState, String newState) {
        if (scenarioName == null || requiredState == null) {
            return true;
        }
        ScenarioKey key = new ScenarioKey(scenarioName, isolation);
        final boolean[] matched = {false};
        scenarioStates.compute(key, (k, currentState) -> {
            String effective = currentState != null ? currentState : STARTED;
            if (requiredState.equals(effective)) {
                matched[0] = true;
                return newState != null ? newState : effective;
            }
            matched[0] = false;
            return currentState;
        });
        return matched[0];
    }

    public void transitionState(String scenarioName, String isolation, String newState) {
        if (scenarioName != null && newState != null) {
            scenarioStates.put(new ScenarioKey(scenarioName, isolation), newState);
        }
    }

    /**
     * Clears ALL isolation variants of the given scenario name.
     * Both the legacy null-isolation key and any composite keys with
     * the same scenario name are removed.
     */
    public void clear(String scenarioName) {
        if (scenarioName != null) {
            scenarioStates.keySet().removeIf(k -> scenarioName.equals(k.getScenarioName()));
        }
    }

    public void reset() {
        scenarioStates.clear();
    }

    /**
     * Returns all states as a flat map of scenario name (or composite key toString) to state.
     * For backward compatibility, entries with null isolation return just the scenario name as key.
     * <p>
     * <strong>Warning:</strong> The string format for composite-isolated keys
     * ({@code "name[isolation]"}) is NOT a stable API — it is intended for display
     * and logging only. For programmatic access, use {@link #getAllStatesStructured()}.
     */
    public Map<String, String> getAllStates() {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<ScenarioKey, String> entry : scenarioStates.entrySet()) {
            result.put(entry.getKey().toString(), entry.getValue());
        }
        return result;
    }

    /**
     * Returns all states as a map of {@link ScenarioKey} to state string,
     * suitable for programmatic access without relying on the display-oriented
     * string format produced by {@link #getAllStates()}.
     */
    public Map<ScenarioKey, String> getAllStatesStructured() {
        return new LinkedHashMap<>(scenarioStates);
    }

    // --- Timed transitions ---

    /**
     * Generation counter per scenario name for logical cancellation of
     * pending timed transitions. When a new transition is scheduled (or an
     * explicit cancel is requested), the generation is bumped so any
     * previously submitted runnable becomes a no-op when it fires.
     */
    private final ConcurrentHashMap<String, Long> transitionGenerations = new ConcurrentHashMap<>();

    /**
     * Schedules a timed transition: after {@code transition.getTransitionAfterMs()} ms,
     * if the scenario is still in {@code transition.getCurrentState()}, it will be
     * advanced to {@code transition.getNextState()}.
     * <p>
     * Only one pending transition per scenario is logically active; scheduling a
     * new transition for the same scenario logically cancels any pending one
     * (the stale runnable becomes a no-op via generation counters).
     *
     * @param transition the transition descriptor (must have scenarioName, currentState, nextState, transitionAfterMs)
     * @param scheduler  the MockServer scheduler to use for delayed execution
     */
    public void scheduleTransition(TimedScenarioTransition transition, Scheduler scheduler) {
        String scenarioName = transition.getScenarioName();
        String currentState = transition.getCurrentState();
        String nextState = transition.getNextState();
        long delayMs = transition.getTransitionAfterMs();

        // bump generation to logically cancel any pending transition for this scenario
        long generation = transitionGenerations.merge(scenarioName, 1L, Long::sum);

        Delay delay = new Delay(MILLISECONDS, delayMs);
        scheduler.submitAsync(() -> {
            // only fire if this generation is still current (not cancelled)
            Long currentGen = transitionGenerations.get(scenarioName);
            if (currentGen != null && currentGen == generation) {
                matchesAndTransition(scenarioName, currentState, nextState);
            }
        }, delay);
    }

    /**
     * Cancels any pending timed transition for the given scenario name.
     */
    public void cancelPendingTransition(String scenarioName) {
        if (scenarioName != null) {
            // bump generation so any pending transition runnable becomes a no-op
            transitionGenerations.merge(scenarioName, 1L, Long::sum);
        }
    }

    /**
     * Cancels all pending timed transitions and clears generation counters.
     */
    public void cancelAllPendingTransitions() {
        transitionGenerations.clear();
    }
}
