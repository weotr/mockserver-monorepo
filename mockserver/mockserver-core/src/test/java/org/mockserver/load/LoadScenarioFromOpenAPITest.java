package org.mockserver.load;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThrows;

/**
 * Unit tests for {@link LoadScenarioFromOpenAPI}: one step per operation with the right method/path and
 * request body example, target precedence (explicit target vs spec {@code servers[0]} vs path-only), and
 * a conservative default profile. Pure generation — no global state mutation, so it runs in the parallel
 * Surefire phase.
 */
public class LoadScenarioFromOpenAPITest {

    private static final MockServerLogger LOGGER = new MockServerLogger();

    // A small inline spec with three operations across two paths and one request body.
    private static final String SPEC = "{"
        + "\"openapi\": \"3.0.0\","
        + "\"info\": { \"title\": \"Tiny API\", \"version\": \"1.0.0\" },"
        + "\"servers\": [ { \"url\": \"https://api.example.com:8443/v1\" } ],"
        + "\"paths\": {"
        + "  \"/pets\": {"
        + "    \"get\": { \"operationId\": \"listPets\", \"responses\": { \"200\": { \"description\": \"ok\" } } },"
        + "    \"post\": {"
        + "      \"operationId\": \"createPet\","
        + "      \"requestBody\": { \"required\": true, \"content\": { \"application/json\": { \"schema\": {"
        + "        \"type\": \"object\", \"properties\": { \"name\": { \"type\": \"string\" }, \"age\": { \"type\": \"integer\" } } } } } },"
        + "      \"responses\": { \"201\": { \"description\": \"created\" } }"
        + "    }"
        + "  },"
        + "  \"/pets/{petId}\": {"
        + "    \"get\": { \"operationId\": \"showPetById\","
        + "      \"parameters\": [ { \"name\": \"petId\", \"in\": \"path\", \"required\": true, \"schema\": { \"type\": \"string\" } } ],"
        + "      \"responses\": { \"200\": { \"description\": \"ok\" } } }"
        + "  }"
        + "} }";

    // Same spec but with NO servers — used to prove the path-only fallback when no target is given.
    private static final String SPEC_NO_SERVERS = "{"
        + "\"openapi\": \"3.0.0\","
        + "\"info\": { \"title\": \"No Server API\", \"version\": \"1.0.0\" },"
        + "\"paths\": {"
        + "  \"/health\": { \"get\": { \"operationId\": \"health\", \"responses\": { \"200\": { \"description\": \"ok\" } } } }"
        + "} }";

    private static HttpRequest requestForOperation(LoadScenario scenario, String operationId) {
        for (LoadStep step : scenario.getSteps()) {
            if (operationId.equals(step.getName())) {
                return step.getRequest();
            }
        }
        throw new AssertionError("no step for operation " + operationId);
    }

    @Test
    public void generatesOneStepPerOperationWithMethodPathAndDefaultProfile() {
        LoadScenario scenario = LoadScenarioFromOpenAPI.generate("tiny", SPEC, null, null, LOGGER);

        assertThat(scenario.getName(), is("tiny"));
        List<LoadStep> steps = scenario.getSteps();
        assertThat("one step per operation", steps.size(), is(3));

        // server prefix /v1 is applied to every path
        HttpRequest listPets = requestForOperation(scenario, "listPets");
        assertThat(listPets.getMethod().getValue(), is("GET"));
        assertThat(listPets.getPath().getValue(), is("/v1/pets"));

        HttpRequest createPet = requestForOperation(scenario, "createPet");
        assertThat(createPet.getMethod().getValue(), is("POST"));
        assertThat(createPet.getPath().getValue(), is("/v1/pets"));

        HttpRequest showPetById = requestForOperation(scenario, "showPetById");
        assertThat(showPetById.getMethod().getValue(), is("GET"));
        assertThat(showPetById.getPath().getValue(), is("/v1/pets/{petId}"));

        // a conservative default profile is present and runnable
        LoadProfile profile = scenario.getProfile();
        assertThat(profile, is(notNullValue()));
        assertThat(profile.getStages().size(), is(1));
        assertThat(profile.peakVus(), is(LoadScenarioFromOpenAPI.DEFAULT_VUS));
        assertThat(profile.totalDurationMillis(), is(LoadScenarioFromOpenAPI.DEFAULT_DURATION_MILLIS));
    }

    @Test
    public void emitsRequestBodyExampleAndContentTypeForOperationsWithABody() {
        LoadScenario scenario = LoadScenarioFromOpenAPI.generate("tiny", SPEC, null, null, LOGGER);

        HttpRequest createPet = requestForOperation(scenario, "createPet");
        assertThat(createPet.getFirstHeader("Content-Type"), is("application/json"));
        assertThat(createPet.getBodyAsString(), containsString("name"));
        assertThat(createPet.getBodyAsString(), containsString("age"));

        // operations without a request body carry no body
        HttpRequest listPets = requestForOperation(scenario, "listPets");
        assertThat(listPets.getBody(), is(nullValue()));
    }

    @Test
    public void appliesTargetFromServersFirstUrlWhenNoExplicitTarget() {
        LoadScenario scenario = LoadScenarioFromOpenAPI.generate("tiny", SPEC, null, null, LOGGER);

        HttpRequest listPets = requestForOperation(scenario, "listPets");
        // host:port and scheme come from servers[0] = https://api.example.com:8443/v1
        assertThat(listPets.getFirstHeader("Host"), is("api.example.com:8443"));
        assertThat(listPets.isSecure(), is(true));
    }

    @Test
    public void explicitTargetOverridesServersUrl() {
        LoadScenarioFromOpenAPI.Target target =
            new LoadScenarioFromOpenAPI.Target("localhost", 1080, "http");
        LoadScenario scenario = LoadScenarioFromOpenAPI.generate("tiny", SPEC, target, null, LOGGER);

        HttpRequest listPets = requestForOperation(scenario, "listPets");
        assertThat(listPets.getFirstHeader("Host"), is("localhost:1080"));
        assertThat(listPets.isSecure(), is(false));
    }

    @Test
    public void leavesRequestPathOnlyWhenNoTargetAndNoServers() {
        LoadScenario scenario = LoadScenarioFromOpenAPI.generate("noserver", SPEC_NO_SERVERS, null, null, LOGGER);

        HttpRequest health = requestForOperation(scenario, "health");
        assertThat(health.getPath().getValue(), is("/health"));
        assertThat("no Host header without a target/server", health.getFirstHeader("Host"), is(""));
    }

    @Test
    public void usesSuppliedProfileWhenProvided() {
        LoadProfile custom = LoadProfile.constant(2, 5_000L);
        LoadScenario scenario = LoadScenarioFromOpenAPI.generate("tiny", SPEC, null, custom, LOGGER);

        assertThat(scenario.getProfile(), is(custom));
        assertThat(scenario.getProfile().peakVus(), is(2));
    }

    @Test
    public void throwsWhenSpecHasNoOperations() {
        String emptySpec = "{ \"openapi\": \"3.0.0\", \"info\": { \"title\": \"Empty\", \"version\": \"1.0.0\" }, \"paths\": {} }";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> LoadScenarioFromOpenAPI.generate("empty", emptySpec, null, null, LOGGER));
        assertThat(ex.getMessage(), containsString("no operations"));
    }

    @Test
    public void throwsWhenSpecIsUnparseable() {
        assertThrows(RuntimeException.class,
            () -> LoadScenarioFromOpenAPI.generate("bad", "{ not valid openapi", null, null, LOGGER));
    }
}
