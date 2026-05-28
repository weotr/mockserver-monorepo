package org.mockserver.serialization.serializers.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.junit.Test;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class ObjectSchemaSerializerTest {

    private final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();

    @Test
    public void shouldSerializeEmptyObjectSchema() throws JsonProcessingException {
        assertThat(
            objectMapper.writeValueAsString(new ObjectSchema()),
            is("{\"type\":\"object\"}")
        );
    }

    @Test
    @SuppressWarnings("rawtypes")
    public void shouldSerializeObjectSchemaWithProperties() throws JsonProcessingException {
        ObjectSchema schema = new ObjectSchema();
        Map<String, Schema> properties = new LinkedHashMap<>();
        properties.put("name", new StringSchema());
        properties.put("age", new IntegerSchema());
        schema.setProperties(properties);

        String json = objectMapper.writeValueAsString(schema);

        assertThat(json.contains("\"type\":\"object\""), is(true));
        assertThat(json.contains("\"name\":{\"type\":\"string\"}"), is(true));
        assertThat(json.contains("\"age\":{\"format\":\"int32\",\"type\":\"integer\"}"), is(true));
    }

    @Test
    public void shouldSerializeObjectSchemaWithRequired() throws JsonProcessingException {
        ObjectSchema schema = new ObjectSchema();
        schema.setRequired(Arrays.asList("name", "age"));

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"required\":[\"age\",\"name\"],\"type\":\"object\"}")
        );
    }

    @Test
    public void shouldSerializeObjectSchemaWithDescription() throws JsonProcessingException {
        ObjectSchema schema = new ObjectSchema();
        schema.setDescription("A person object");

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"description\":\"A person object\",\"type\":\"object\"}")
        );
    }

    @Test
    @SuppressWarnings("rawtypes")
    public void shouldSerializeObjectSchemaWithAdditionalProperties() throws JsonProcessingException {
        ObjectSchema schema = new ObjectSchema();
        schema.setAdditionalProperties(new StringSchema());

        String json = objectMapper.writeValueAsString(schema);

        assertThat(json.contains("\"type\":\"object\""), is(true));
        assertThat(json.contains("\"additionalProperties\""), is(true));
    }

    @Test
    public void shouldSerializeObjectSchemaWithNullable() throws JsonProcessingException {
        ObjectSchema schema = new ObjectSchema();
        schema.setNullable(true);

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"nullable\":true,\"type\":\"object\"}")
        );
    }
}
