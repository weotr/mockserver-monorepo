package org.mockserver.mock;

import org.mockserver.model.Delay;
import org.mockserver.model.TimedScenarioTransition;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.state.KeyValueStore;
import org.mockserver.state.Versioned;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class ScenarioManager {

    public static final String STARTED = "Started";

    /**
     * The scenario states store — either the backend's replicated
     * {@link KeyValueStore} (when a StateBackend is wired) or a
     * default in-process {@link org.mockserver.state.InMemoryKeyValueStore}.
     * <p>
     * All reads and writes go through this store, so a clustered
     * backend provides automatic cross-node state replication and
     * atomic transitions via {@link KeyValueStore#compareAndSet}.
     */
    private volatile KeyValueStore<String> scenarioStates;

    /**
     * Creates a ScenarioManager with the default in-memory KV store.
     * This preserves backward compatibility: ScenarioManager constructed
     * without arguments behaves identically to the pre-clustering version
     * (node-local ConcurrentHashMap-backed store, zero network I/O).
     */
    public ScenarioManager() {
        this(new org.mockserver.state.InMemoryKeyValueStore<>());
    }

    /**
     * Creates a ScenarioManager backed by the given {@link KeyValueStore}.
     * For single-node deployments, pass an {@link org.mockserver.state.InMemoryKeyValueStore}.
     * For clustered deployments, pass the replicated
     * {@link org.mockserver.state.StateBackend#scenarioStates()} store.
     *
     * @param scenarioStates the KV store for scenario state strings
     */
    public ScenarioManager(KeyValueStore<String> scenarioStates) {
        this.scenarioStates = Objects.requireNonNull(scenarioStates, "scenarioStates");
    }

    /**
     * Replaces the scenario states store. Called by
     * {@link RequestMatchers#setStateBackend} when a backend is wired
     * (or removed). Existing state in the old store is NOT migrated —
     * the caller is responsible for resetting if needed.
     *
     * @param scenarioStates the new KV store (must not be null)
     */
    public void setScenarioStates(KeyValueStore<String> scenarioStates) {
        this.scenarioStates = Objects.requireNonNull(scenarioStates, "scenarioStates");
    }

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

        /**
         * Serializes this key to an unambiguous length-prefixed string format.
         * <p>
         * Format: {@code <len(name)>:<name>:<flag>} for null isolation, or
         * {@code <len(name)>:<name>:V<len(isolation)>:<isolation>} for non-null isolation.
         * <p>
         * The flag character distinguishes null isolation ({@code N}) from a
         * non-null (possibly empty) isolation value ({@code V}). Length-prefixing
         * both components makes the encoding provably lossless for ALL strings,
         * including those containing colons, brackets, or any other character.
         * <p>
         * Examples:
         * <ul>
         *   <li>{@code ("test", null)} &rarr; {@code "4:test:N"}</li>
         *   <li>{@code ("test", "s1")} &rarr; {@code "4:test:V2:s1"}</li>
         *   <li>{@code ("test[1]", null)} &rarr; {@code "7:test[1]:N"}</li>
         *   <li>{@code ("test", "")} &rarr; {@code "4:test:V0:"}</li>
         * </ul>
         */
        @Override
        public String toString() {
            if (isolation == null) {
                return scenarioName.length() + ":" + scenarioName + ":N";
            }
            return scenarioName.length() + ":" + scenarioName + ":V" + isolation.length() + ":" + isolation;
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
        String key = new ScenarioKey(scenarioName, isolation).toString();
        return scenarioStates.get(key)
            .map(Versioned::getValue)
            .orElse(STARTED);
    }

    public void setState(String scenarioName, String isolation, String state) {
        if (scenarioName == null || state == null) {
            return;
        }
        String key = new ScenarioKey(scenarioName, isolation).toString();
        scenarioStates.put(key, state);
    }

    public boolean matchesState(String scenarioName, String isolation, String requiredState) {
        if (scenarioName == null || requiredState == null) {
            return true;
        }
        return requiredState.equals(getState(scenarioName, isolation));
    }

    /**
     * Atomically checks whether the scenario is in {@code requiredState}
     * and, if so, transitions it to {@code newState}. Uses
     * {@link KeyValueStore#compareAndSet} for cross-node atomicity when
     * backed by a replicated store.
     * <p>
     * For the in-memory default backend, this is equivalent to a
     * {@code ConcurrentHashMap.compute()} — identical single-node
     * behaviour with no performance overhead.
     * <p>
     * When the scenario key does not yet exist in the store (first access),
     * the implicit state is {@link #STARTED}. If {@code requiredState}
     * equals "Started", the key is created with a {@code put()} (since
     * there is no prior version to CAS against), then immediately CAS'd
     * to the new state to guard against concurrent first-access races.
     *
     * @param scenarioName  the scenario name (null = always matches)
     * @param isolation     the isolation key (null = legacy single-key)
     * @param requiredState the state that must be current for the transition
     * @param newState      the target state (null = no-op transition)
     * @return true if the current state matched {@code requiredState}
     *         (and the transition was applied if {@code newState != null})
     */
    public boolean matchesAndTransition(String scenarioName, String isolation, String requiredState, String newState) {
        if (scenarioName == null || requiredState == null) {
            return true;
        }
        String key = new ScenarioKey(scenarioName, isolation).toString();

        // CAS loop: retry on version conflict (rare under contention)
        for (int attempt = 0; attempt < 100; attempt++) {
            Optional<Versioned<String>> current = scenarioStates.get(key);

            if (current.isPresent()) {
                // Key exists in the store
                String effectiveState = current.get().getValue();
                if (!requiredState.equals(effectiveState)) {
                    return false;
                }
                // State matches — apply transition
                if (newState == null) {
                    return true; // match-only, no transition
                }
                if (scenarioStates.compareAndSet(key, current.get().getVersion(), newState)) {
                    return true;
                }
                // CAS failed: another writer changed the value — retry
                continue;
            } else {
                // Key does not exist — implicit state is STARTED
                if (!requiredState.equals(STARTED)) {
                    return false;
                }
                // State matches STARTED — apply transition
                if (newState == null) {
                    return true; // match-only, no transition
                }
                // Atomically create the key with the target state using
                // putIfAbsent so that only the first creator wins. If
                // another thread raced and created the key first,
                // putIfAbsent returns the existing value without
                // modifying the store — no lost-update risk.
                Optional<Versioned<String>> existing = scenarioStates.putIfAbsent(key, newState);
                if (!existing.isPresent()) {
                    // We were the first creator — transition complete
                    return true;
                }
                // Another thread created the key first. Re-read and
                // retry the CAS loop so we see the actual stored value.
                continue;
            }
        }
        // Exhausted retries (should not happen in practice)
        return false;
    }

    public void transitionState(String scenarioName, String isolation, String newState) {
        if (scenarioName != null && newState != null) {
            String key = new ScenarioKey(scenarioName, isolation).toString();
            scenarioStates.put(key, newState);
        }
    }

    /**
     * Clears ALL isolation variants of the given scenario name.
     * Both the legacy null-isolation key and any composite keys with
     * the same scenario name are removed.
     */
    public void clear(String scenarioName) {
        if (scenarioName != null) {
            // Collect keys matching this scenario name, then remove them.
            // Uses parseKey to extract the scenario name from the length-prefixed
            // key format, ensuring exact match without prefix-collision ambiguity.
            scenarioStates.entries().forEach(entry -> {
                String key = entry.getKey();
                if (keyBelongsToScenario(key, scenarioName)) {
                    scenarioStates.remove(key);
                }
            });
        }
    }

    /**
     * Checks whether a serialized key string belongs to the given scenario name.
     * Parses the length-prefixed key format to extract the exact scenario name
     * and compares it for equality — no prefix-based matching that could collide
     * with names that are prefixes of other names.
     */
    static boolean keyBelongsToScenario(String key, String scenarioName) {
        ScenarioKey parsed = parseKey(key);
        return scenarioName.equals(parsed.getScenarioName());
    }

    public void reset() {
        scenarioStates.clear();
    }

    /**
     * Returns all states as a flat map of display key to state. For entries with
     * null isolation, the display key is just the scenario name. For entries with
     * non-null isolation, the display key is {@code "name[isolation]"}.
     * <p>
     * <strong>Warning:</strong> The display string format
     * ({@code "name[isolation]"}) is NOT a stable API — it is intended for display
     * and logging only. For programmatic access, use {@link #getAllStatesStructured()}.
     */
    public Map<String, String> getAllStates() {
        Map<String, String> result = new LinkedHashMap<>();
        scenarioStates.entries().forEach(entry -> {
            ScenarioKey parsed = parseKey(entry.getKey());
            String displayKey;
            if (parsed.getIsolation() == null) {
                displayKey = parsed.getScenarioName();
            } else {
                displayKey = parsed.getScenarioName() + "[" + parsed.getIsolation() + "]";
            }
            result.put(displayKey, entry.getValue());
        });
        return result;
    }

    /**
     * Returns all states as a map of {@link ScenarioKey} to state string,
     * suitable for programmatic access without relying on the display-oriented
     * string format produced by {@link #getAllStates()}.
     */
    public Map<ScenarioKey, String> getAllStatesStructured() {
        Map<ScenarioKey, String> result = new LinkedHashMap<>();
        scenarioStates.entries().forEach(entry -> {
            ScenarioKey scenarioKey = parseKey(entry.getKey());
            result.put(scenarioKey, entry.getValue());
        });
        return result;
    }

    /**
     * Parses a serialized key string back into a {@link ScenarioKey}.
     * <p>
     * Key format (length-prefixed, unambiguous):
     * <ul>
     *   <li>{@code <len>:<name>:N} &mdash; null isolation</li>
     *   <li>{@code <len>:<name>:V<len>:<isolation>} &mdash; non-null isolation</li>
     * </ul>
     *
     * @throws IllegalArgumentException if the key does not conform to the expected format
     */
    static ScenarioKey parseKey(String key) {
        // Parse name length
        int firstColon = key.indexOf(':');
        if (firstColon < 1) {
            throw new IllegalArgumentException("invalid scenario key format (missing name length): " + key);
        }
        int nameLen;
        try {
            nameLen = Integer.parseInt(key.substring(0, firstColon));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid scenario key format (bad name length): " + key, e);
        }
        // Extract name
        int nameStart = firstColon + 1;
        int nameEnd = nameStart + nameLen;
        if (nameEnd >= key.length()) {
            throw new IllegalArgumentException("invalid scenario key format (name length exceeds key): " + key);
        }
        String scenarioName = key.substring(nameStart, nameEnd);
        // Expect ':' separator after name
        if (key.charAt(nameEnd) != ':') {
            throw new IllegalArgumentException("invalid scenario key format (missing separator after name): " + key);
        }
        // Flag character
        int flagPos = nameEnd + 1;
        if (flagPos >= key.length()) {
            throw new IllegalArgumentException("invalid scenario key format (missing flag): " + key);
        }
        char flag = key.charAt(flagPos);
        if (flag == 'N') {
            return new ScenarioKey(scenarioName, null);
        } else if (flag == 'V') {
            // Parse isolation length
            int isoLenStart = flagPos + 1;
            int isoColon = key.indexOf(':', isoLenStart);
            if (isoColon < 0) {
                throw new IllegalArgumentException("invalid scenario key format (missing isolation length separator): " + key);
            }
            int isoLen;
            try {
                isoLen = Integer.parseInt(key.substring(isoLenStart, isoColon));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid scenario key format (bad isolation length): " + key, e);
            }
            String isolation = key.substring(isoColon + 1, isoColon + 1 + isoLen);
            return new ScenarioKey(scenarioName, isolation);
        } else {
            throw new IllegalArgumentException("invalid scenario key format (unknown flag '" + flag + "'): " + key);
        }
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
