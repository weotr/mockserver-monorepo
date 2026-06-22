package org.mockserver.mock.pact;

import com.fasterxml.jackson.databind.JsonNode;
import org.mockserver.mock.ScenarioManager;

import java.util.ArrayList;
import java.util.List;

import static org.apache.commons.lang3.StringUtils.isBlank;

/**
 * Maps a <a href="https://docs.pact.io/">Pact</a> interaction's provider states (the
 * "given ..." preconditions) onto MockServer's existing scenario-state mechanism, so that
 * an imported interaction can be gated on its provider state being activated before the
 * interaction is served or verified.
 *
 * <h2>Why scenario state</h2>
 * A Pact provider state is a named precondition the provider must establish before an
 * interaction is exercised (e.g. {@code "given a user with id 1 exists"}). MockServer already
 * models named, transition-able preconditions via {@link ScenarioManager} (a single scenario
 * holding one active state at a time). Rather than inventing a new engine, every provider state
 * is mapped onto the single, well-known scenario {@link #SCENARIO_NAME} with the provider-state
 * name as the required scenario state. An interaction with provider state {@code "user exists"}
 * yields an expectation with {@code scenarioName = "pact-provider-state"} and
 * {@code scenarioState = "user exists"}; that expectation only matches once the state is
 * <em>activated</em> via {@link #activate(ScenarioManager, String)}.
 *
 * <h2>Parsing</h2>
 * Both Pact wire forms are recognised:
 * <ul>
 *   <li>v2: {@code "providerState": "a user exists"} — a single string</li>
 *   <li>v3: {@code "providerStates": [{"name": "a user exists", "params": {...}}]} — an array
 *       of objects (the {@code name} is used; {@code params} are preserved for callbacks but do
 *       not affect matching)</li>
 * </ul>
 * An interaction may legitimately declare several provider states. Because a single MockServer
 * scenario holds exactly one active state at a time, only the <em>first</em> non-blank state name
 * gates matching ({@link #gatingStateOf}); any additional states on the same interaction are not
 * currently honoured. {@link #namesOf} still returns the full ordered list for callers that want to
 * inspect or display every declared state.
 */
public final class PactProviderStates {

    /**
     * The single well-known scenario name under which all Pact provider states are tracked.
     * Stateless interactions never touch this scenario, so they are unaffected.
     */
    public static final String SCENARIO_NAME = "pact-provider-state";

    private PactProviderStates() {
    }

    /**
     * Extracts the ordered list of provider-state names declared on a Pact interaction node,
     * tolerating both the v2 {@code providerState} string and the v3 {@code providerStates} array.
     * Blank names are skipped. Returns an empty list when the interaction declares no state.
     *
     * @param interaction the Pact interaction JSON node (may be a missing node)
     * @return the provider-state names in declared order (never null)
     */
    public static List<String> namesOf(JsonNode interaction) {
        final List<String> names = new ArrayList<>();
        if (interaction == null || interaction.isMissingNode() || interaction.isNull()) {
            return names;
        }

        // v3: providerStates : [ { "name": "...", "params": {...} }, ... ]
        final JsonNode providerStates = interaction.path("providerStates");
        if (providerStates.isArray()) {
            for (final JsonNode state : providerStates) {
                final String name = state.isObject() ? state.path("name").asText(null) : state.asText(null);
                if (!isBlank(name) && !names.contains(name)) {
                    names.add(name);
                }
            }
        }

        // v2: providerState : "..." (additive — some producers emit both forms)
        final JsonNode providerState = interaction.path("providerState");
        if (providerState.isTextual()) {
            final String name = providerState.asText(null);
            if (!isBlank(name) && !names.contains(name)) {
                names.add(name);
            }
        }

        return names;
    }

    /**
     * Returns the gating provider-state name for an interaction — the first declared state — or
     * {@code null} if the interaction declares no provider state. This is the value stored as an
     * expectation's {@code scenarioState}.
     *
     * @param interaction the Pact interaction JSON node
     * @return the gating provider-state name, or null when stateless
     */
    public static String gatingStateOf(JsonNode interaction) {
        final List<String> names = namesOf(interaction);
        return names.isEmpty() ? null : names.get(0);
    }

    /**
     * Activates a provider state: the provider-state callback. Sets the well-known scenario into
     * the given state so that any imported expectation gated on that provider state will match.
     * A blank state name is a no-op, so callers can activate unconditionally.
     *
     * @param scenarioManager the scenario manager backing matching (from {@code RequestMatchers})
     * @param providerState   the provider-state name to activate; blank/null is a no-op
     */
    public static void activate(ScenarioManager scenarioManager, String providerState) {
        if (scenarioManager == null || isBlank(providerState)) {
            return;
        }
        scenarioManager.setState(SCENARIO_NAME, providerState);
    }

    /**
     * Clears any active Pact provider state, returning the well-known scenario to its implicit
     * {@link ScenarioManager#STARTED} state. Useful between verification runs or when tearing down.
     *
     * @param scenarioManager the scenario manager backing matching
     */
    public static void clear(ScenarioManager scenarioManager) {
        if (scenarioManager != null) {
            scenarioManager.clear(SCENARIO_NAME);
        }
    }
}
