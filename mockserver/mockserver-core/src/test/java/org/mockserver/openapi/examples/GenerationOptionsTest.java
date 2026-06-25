package org.mockserver.openapi.examples;

import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.junit.Test;
import org.mockserver.openapi.examples.models.Example;
import org.mockserver.openapi.examples.models.IntegerExample;
import org.mockserver.openapi.examples.models.ObjectExample;
import org.mockserver.openapi.examples.models.StringExample;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Behavioural tests for the WS3.3 per-field override + seed feature: the optional
 * {@link GenerationOptions} threaded through {@link ExampleBuilder}.
 *
 * <p>Covers parsing the reserved options map and the four required behaviours:
 * same seed → identical output, different seed → different output, a field override
 * pins one field while others still generate, and nothing supplied → unchanged (seed 42).</p>
 */
public class GenerationOptionsTest {

    // Realistic generation is driven per-call via GenerationOptions(.., realisticValues) rather than the
    // JVM-global generateRealisticExampleValues flag, so this class mutates no shared state and runs
    // safely in the parallel Surefire phase.

    private static ObjectSchema personSchema() {
        ObjectSchema schema = new ObjectSchema();
        Map<String, Schema> properties = new LinkedHashMap<>();
        properties.put("userId", new StringSchema());
        properties.put("name", new StringSchema());
        schema.setProperties(properties);
        return schema;
    }

    private static String stringFieldValue(Example object, String field) {
        ObjectExample objectExample = (ObjectExample) object;
        return ((StringExample) objectExample.getValues().get(field)).getValue();
    }

    // --- Reserved options map parsing ---

    @Test
    public void shouldParseSeedAndOverridesFromReservedKey() {
        Map<String, Object> operationsAndResponses = new HashMap<>();
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("seed", 123);
        options.put("fieldOverrides", Map.of("userId", "pinned-123"));
        operationsAndResponses.put(GenerationOptions.OPERATIONS_KEY, options);

        GenerationOptions parsed = GenerationOptions.fromOperationsMap(operationsAndResponses);

        assertThat(parsed, is(notNullValue()));
        assertThat(parsed.getSeed(), is(123L));
        assertThat(parsed.hasOverrideFor("userId"), is(true));
        assertThat(parsed.getOverride("userId"), is("pinned-123"));
        assertThat(parsed.hasOverrideFor("name"), is(false));
    }

    @Test
    public void shouldParseSeedGivenAsString() {
        Map<String, Object> operationsAndResponses = new HashMap<>();
        operationsAndResponses.put(GenerationOptions.OPERATIONS_KEY, Map.of("seed", "777"));

        GenerationOptions parsed = GenerationOptions.fromOperationsMap(operationsAndResponses);

        assertThat(parsed, is(notNullValue()));
        assertThat(parsed.getSeed(), is(777L));
    }

    @Test
    public void shouldReturnNullWhenNoReservedKeyPresent() {
        assertThat(GenerationOptions.fromOperationsMap(null), is(nullValue()));
        assertThat(GenerationOptions.fromOperationsMap(new HashMap<>()), is(nullValue()));
        assertThat(GenerationOptions.fromOperationsMap(Map.of("listPets", "200")), is(nullValue()));
    }

    @Test
    public void shouldReturnNullWhenOptionsEmpty() {
        Map<String, Object> operationsAndResponses = new HashMap<>();
        operationsAndResponses.put(GenerationOptions.OPERATIONS_KEY, new HashMap<>());

        assertThat(GenerationOptions.fromOperationsMap(operationsAndResponses), is(nullValue()));
    }

    // --- Seed determinism: same seed -> identical, different seed -> different ---

    @Test
    public void shouldProduceIdenticalValuesForSameSeed() {
        GenerationOptions options = new GenerationOptions(2024L, null, true);

        Example first = ExampleBuilder.fromSchema(personSchema(), new HashMap<>(), options);
        Example second = ExampleBuilder.fromSchema(personSchema(), new HashMap<>(), options);

        assertThat(stringFieldValue(first, "userId"), is(stringFieldValue(second, "userId")));
        assertThat(stringFieldValue(first, "name"), is(stringFieldValue(second, "name")));
    }

    @Test
    public void shouldProduceDifferentValuesForDifferentSeed() {
        Example seedA = ExampleBuilder.fromSchema(personSchema(), new HashMap<>(), new GenerationOptions(1L, null, true));
        Example seedB = ExampleBuilder.fromSchema(personSchema(), new HashMap<>(), new GenerationOptions(999999L, null, true));

        // at least one field should differ between two distinct seeds
        boolean differs = !stringFieldValue(seedA, "userId").equals(stringFieldValue(seedB, "userId"))
            || !stringFieldValue(seedA, "name").equals(stringFieldValue(seedB, "name"));
        assertThat(differs, is(true));
    }

    // --- Field override pins one field, others still generate ---

    @Test
    public void shouldPinOverriddenFieldWhileOthersStillGenerate() {
        GenerationOptions options = new GenerationOptions(42L, Map.of("userId", "system-user-123"), true);

        Example result = ExampleBuilder.fromSchema(personSchema(), new HashMap<>(), options);

        assertThat(stringFieldValue(result, "userId"), is("system-user-123"));
        // the non-overridden field is still realistically generated (not the static placeholder)
        assertThat(stringFieldValue(result, "name"), is(not(ExampleBuilder.SAMPLE_STRING_PROPERTY_VALUE)));
        assertThat(stringFieldValue(result, "name"), is(not("system-user-123")));
    }

    @Test
    public void shouldApplyOverrideEvenWhenRealisticGenerationDisabled() {
        // overrides apply regardless of the realistic-values flag
        GenerationOptions options = new GenerationOptions(null, Map.of("userId", "pinned"), false);

        Example result = ExampleBuilder.fromSchema(personSchema(), new HashMap<>(), options);

        assertThat(stringFieldValue(result, "userId"), is("pinned"));
        // non-overridden field falls back to the static placeholder when generation is off
        assertThat(stringFieldValue(result, "name"), is(ExampleBuilder.SAMPLE_STRING_PROPERTY_VALUE));
    }

    @Test
    public void shouldPreserveOverrideValueTypeForNonStringFields() {
        ObjectSchema schema = new ObjectSchema();
        Map<String, Schema> properties = new LinkedHashMap<>();
        properties.put("count", new io.swagger.v3.oas.models.media.IntegerSchema());
        schema.setProperties(properties);

        GenerationOptions options = new GenerationOptions(null, Map.of("count", 7), false);

        ObjectExample result = (ObjectExample) ExampleBuilder.fromSchema(schema, new HashMap<>(), options);

        Example countExample = result.getValues().get("count");
        assertThat(countExample, is(instanceOf(IntegerExample.class)));
        assertThat(((IntegerExample) countExample).getValue(), is(7));
    }

    // --- Author-declared example still wins over override ---

    @Test
    public void shouldNotOverrideWhenSchemaDeclaresExample() {
        StringSchema userId = new StringSchema();
        userId.setExample("author-declared");
        ObjectSchema schema = new ObjectSchema();
        Map<String, Schema> properties = new LinkedHashMap<>();
        properties.put("userId", userId);
        schema.setProperties(properties);

        GenerationOptions options = new GenerationOptions(null, Map.of("userId", "override-loses"), false);

        Example result = ExampleBuilder.fromSchema(schema, new HashMap<>(), options);

        assertThat(stringFieldValue(result, "userId"), is("author-declared"));
    }

    // --- Nothing supplied -> unchanged (historic fixed seed 42) ---

    @Test
    public void shouldMatchHistoricFixedSeedWhenNoSeedSupplied() {
        // realistic generation with no seed supplied vs. an explicit seed-42 options object should be
        // identical, proving the default is still the historic fixed seed of 42.
        Example noSeed = ExampleBuilder.fromSchema(personSchema(), new HashMap<>(), new GenerationOptions(null, null, true));
        Example seed42 = ExampleBuilder.fromSchema(personSchema(), new HashMap<>(), new GenerationOptions(42L, null, true));

        assertThat(stringFieldValue(noSeed, "userId"), is(stringFieldValue(seed42, "userId")));
        assertThat(stringFieldValue(noSeed, "name"), is(stringFieldValue(seed42, "name")));
    }
}
