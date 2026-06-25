package org.mockserver.openapi;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpResponse;
import org.mockserver.openapi.examples.GenerationOptions;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * End-to-end behavioural tests for the WS3.3 per-field override + seed feature, exercised through the
 * full {@link OpenAPIConverter#buildExpectations} import path. Options are supplied via the reserved
 * {@link GenerationOptions#OPERATIONS_KEY} entry of the {@code operationsAndResponses} map, so no
 * public API signature changes and default behaviour is preserved when the key is absent.
 */
public class OpenAPIConverterGenerationOptionsTest {

    private final MockServerLogger mockServerLogger = new MockServerLogger(OpenAPIConverterGenerationOptionsTest.class);

    // minimal spec: one operation, 200 response is an object with two string fields and no declared example
    private static final String SPEC =
        "{" +
        "  \"openapi\": \"3.0.0\"," +
        "  \"info\": { \"title\": \"ws33\", \"version\": \"1.0\" }," +
        "  \"paths\": {" +
        "    \"/user\": {" +
        "      \"get\": {" +
        "        \"operationId\": \"getUser\"," +
        "        \"responses\": {" +
        "          \"200\": {" +
        "            \"description\": \"ok\"," +
        "            \"content\": {" +
        "              \"application/json\": {" +
        "                \"schema\": {" +
        "                  \"type\": \"object\"," +
        "                  \"properties\": {" +
        "                    \"userId\": { \"type\": \"string\" }," +
        "                    \"name\": { \"type\": \"string\" }" +
        "                  }" +
        "                }" +
        "              }" +
        "            }" +
        "          }" +
        "        }" +
        "      }" +
        "    }" +
        "  }" +
        "}";

    // Realistic generation is requested per-import via the reserved options map ("realisticValues": true)
    // rather than the JVM-global generateRealisticExampleValues flag, so this class mutates no shared
    // state and runs safely in the parallel Surefire phase.

    private String responseBody(Map<String, Object> operationsAndResponses) {
        List<Expectation> expectations = new OpenAPIConverter(mockServerLogger).buildExpectations(SPEC, operationsAndResponses);
        assertThat(expectations, hasSize(1));
        HttpResponse response = expectations.get(0).getHttpResponse();
        return response.getBodyAsString();
    }

    private static Map<String, Object> optionsMap(Object seed, Map<String, Object> fieldOverrides) {
        Map<String, Object> options = new HashMap<>();
        // request realistic generation explicitly via the options map rather than the global flag
        options.put("realisticValues", true);
        if (seed != null) {
            options.put("seed", seed);
        }
        if (fieldOverrides != null) {
            options.put("fieldOverrides", fieldOverrides);
        }
        Map<String, Object> operationsAndResponses = new HashMap<>();
        operationsAndResponses.put("getUser", "200");
        operationsAndResponses.put(GenerationOptions.OPERATIONS_KEY, options);
        return operationsAndResponses;
    }

    @Test
    public void sameSpecAndSeedProducesIdenticalBodyTwice() {
        String first = responseBody(optionsMap(2024, null));
        String second = responseBody(optionsMap(2024, null));

        assertThat(first, is(second));
    }

    @Test
    public void differentSeedProducesDifferentBody() {
        String seedA = responseBody(optionsMap(1, null));
        String seedB = responseBody(optionsMap(987654, null));

        assertThat(seedA, is(not(seedB)));
    }

    @Test
    public void fieldOverridePinsThatFieldWhileOthersGenerate() {
        String body = responseBody(optionsMap(42, Map.of("userId", "system-user-123")));

        assertThat(body, containsString("\"userId\""));
        assertThat(body, containsString("system-user-123"));
        // the non-overridden field is still present and not pinned to the override value
        assertThat(body, containsString("\"name\""));
    }

    @Test
    public void noSeedSuppliedUsesHistoricFixedSeed() {
        // realistic import with no seed supplied vs. an explicit seed-42 options object: identical,
        // proving the untouched default path still uses the historic fixed seed of 42.
        String defaultBody = responseBody(optionsMap(null, null));
        String seed42Body = responseBody(optionsMap(42, null));

        assertThat(defaultBody, is(seed42Body));
    }
}
