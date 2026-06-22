package org.mockserver.serialization.serializers.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.Test;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * Verifies that the OpenAPI 3.1 {@code types} array is translated to the Draft-07 / OAS-3.0
 * {@code type} (+ {@code nullable}) form during schema serialization rather than being silently
 * dropped.
 */
public class AbstractSchemaSerializerTest {

    private final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();

    @SuppressWarnings({"unchecked", "rawtypes"})
    private JsonNode serialize(String... types) throws JsonProcessingException {
        Schema schema = new Schema<>();
        schema.setTypes(new LinkedHashSet<>(Arrays.asList(types)));
        return objectMapper.readTree(objectMapper.writeValueAsString(schema));
    }

    @Test
    public void shouldTranslateNullableStringTypeArray() throws JsonProcessingException {
        // when - ["string","null"]
        JsonNode node = serialize("string", "null");

        // then - type:string + nullable:true, types removed
        assertThat(node.get("type").asText(), is("string"));
        assertThat(node.get("nullable").booleanValue(), is(true));
        assertThat(node.get("types"), is(nullValue()));
    }

    @Test
    public void shouldTranslateNullableIntegerTypeArray() throws JsonProcessingException {
        // when - ["integer","null"]
        JsonNode node = serialize("integer", "null");

        // then - type:integer + nullable:true, types removed
        assertThat(node.get("type").asText(), is("integer"));
        assertThat(node.get("nullable").booleanValue(), is(true));
        assertThat(node.get("types"), is(nullValue()));
    }

    @Test
    public void shouldPreserveMultipleNonNullTypesAsArray() throws JsonProcessingException {
        // when - ["string","integer"]
        JsonNode node = serialize("string", "integer");

        // then - type:[string,integer] preserved, no nullable, types removed
        assertThat(node.get("type").isArray(), is(true));
        List<String> typeValues = new java.util.ArrayList<>();
        node.get("type").forEach(t -> typeValues.add(t.asText()));
        assertThat(typeValues, contains("string", "integer"));
        assertThat(node.get("nullable"), is(nullValue()));
        assertThat(node.get("types"), is(nullValue()));
    }

    @Test
    public void shouldAddNullableForMultipleNonNullTypesPlusNull() throws JsonProcessingException {
        // when - ["string","integer","null"]
        JsonNode node = serialize("string", "integer", "null");

        // then - type:[string,integer] preserved + nullable:true, types removed
        assertThat(node.get("type").isArray(), is(true));
        List<String> typeValues = new java.util.ArrayList<>();
        node.get("type").forEach(t -> typeValues.add(t.asText()));
        assertThat(typeValues, contains("string", "integer"));
        assertThat(node.get("nullable").booleanValue(), is(true));
        assertThat(node.get("types"), is(nullValue()));
    }

    @Test
    public void shouldHandleSingleElementTypeArray() throws JsonProcessingException {
        // when - ["string"] (existing behaviour, regression guard)
        JsonNode node = serialize("string");

        // then - type:string, no nullable, types removed
        assertThat(node.get("type").asText(), is("string"));
        assertThat(node.get("nullable"), is(nullValue()));
        assertThat(node.get("types"), is(nullValue()));
    }
}
