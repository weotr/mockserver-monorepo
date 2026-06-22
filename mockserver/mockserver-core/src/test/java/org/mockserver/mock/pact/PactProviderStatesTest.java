package org.mockserver.mock.pact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.mockserver.mock.ScenarioManager;
import org.mockserver.serialization.ObjectMapperFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Tests for {@link PactProviderStates} — parsing Pact provider states (v2 + v3 wire forms) and
 * mapping them onto MockServer's scenario-state mechanism.
 */
public class PactProviderStatesTest {

    private static final ObjectMapper MAPPER = ObjectMapperFactory.createObjectMapper();

    private static JsonNode node(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void parsesV3ProviderStatesArray() {
        JsonNode interaction = node("{\"providerStates\":[{\"name\":\"a user exists\",\"params\":{\"id\":1}},{\"name\":\"and is active\"}]}");
        assertThat(PactProviderStates.namesOf(interaction), contains("a user exists", "and is active"));
        assertEquals("a user exists", PactProviderStates.gatingStateOf(interaction));
    }

    @Test
    public void parsesV2ProviderStateString() {
        JsonNode interaction = node("{\"providerState\":\"a user exists\"}");
        assertThat(PactProviderStates.namesOf(interaction), contains("a user exists"));
        assertEquals("a user exists", PactProviderStates.gatingStateOf(interaction));
    }

    @Test
    public void mergesV2AndV3FormsWithoutDuplicates() {
        JsonNode interaction = node("{\"providerState\":\"a user exists\",\"providerStates\":[{\"name\":\"a user exists\"}]}");
        assertThat(PactProviderStates.namesOf(interaction), contains("a user exists"));
    }

    @Test
    public void statelessInteractionHasNoProviderState() {
        JsonNode interaction = node("{\"description\":\"get users\"}");
        assertThat(PactProviderStates.namesOf(interaction), empty());
        assertNull(PactProviderStates.gatingStateOf(interaction));
    }

    @Test
    public void skipsBlankProviderStateNames() {
        JsonNode interaction = node("{\"providerStates\":[{\"name\":\"\"},{\"name\":\"  \"},{\"name\":\"real\"}]}");
        assertThat(PactProviderStates.namesOf(interaction), contains("real"));
    }

    @Test
    public void toleratesNullInteractionNode() {
        assertThat(PactProviderStates.namesOf(null), empty());
        assertNull(PactProviderStates.gatingStateOf(null));
    }

    @Test
    public void activateSetsScenarioStateAndClearResets() {
        ScenarioManager scenarioManager = new ScenarioManager();

        PactProviderStates.activate(scenarioManager, "a user exists");
        assertEquals("a user exists", scenarioManager.getState(PactProviderStates.SCENARIO_NAME));

        PactProviderStates.clear(scenarioManager);
        assertEquals(ScenarioManager.STARTED, scenarioManager.getState(PactProviderStates.SCENARIO_NAME));
    }

    @Test
    public void activateBlankIsNoOp() {
        ScenarioManager scenarioManager = new ScenarioManager();
        scenarioManager.setState(PactProviderStates.SCENARIO_NAME, "existing");

        PactProviderStates.activate(scenarioManager, null);
        PactProviderStates.activate(scenarioManager, "   ");

        assertEquals("existing", scenarioManager.getState(PactProviderStates.SCENARIO_NAME));
    }

    @Test
    public void nullScenarioManagerIsSafe() {
        // no exception
        PactProviderStates.activate(null, "anything");
        PactProviderStates.clear(null);
    }

    @Test
    public void namesAreReturnedInDeclaredOrder() {
        JsonNode interaction = node("{\"providerStates\":[{\"name\":\"first\"},{\"name\":\"second\"}]}");
        assertThat(PactProviderStates.namesOf(interaction), contains("first", "second"));
    }
}
