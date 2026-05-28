package org.mockserver.serialization.serializers.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.NumberSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.junit.Test;
import org.mockserver.serialization.ObjectMapperFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class ComposedSchemaSerializerTest {

    private final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();

    @Test
    public void shouldSerializeComposedSchemaWithAllOf() throws JsonProcessingException {
        ComposedSchema schema = new ComposedSchema();
        schema.addAllOfItem(new StringSchema());
        schema.addAllOfItem(new IntegerSchema());

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"allOf\":[{\"type\":\"string\"},{\"format\":\"int32\",\"type\":\"integer\"}]}")
        );
    }

    @Test
    public void shouldSerializeComposedSchemaWithOneOf() throws JsonProcessingException {
        ComposedSchema schema = new ComposedSchema();
        schema.addOneOfItem(new StringSchema());
        schema.addOneOfItem(new IntegerSchema());

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"oneOf\":[{\"type\":\"string\"},{\"format\":\"int32\",\"type\":\"integer\"}]}")
        );
    }

    @Test
    public void shouldSerializeComposedSchemaWithAnyOf() throws JsonProcessingException {
        ComposedSchema schema = new ComposedSchema();
        schema.addAnyOfItem(new StringSchema());
        schema.addAnyOfItem(new NumberSchema());

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"number\"}]}")
        );
    }

    @Test
    public void shouldSerializeComposedSchemaWithDescription() throws JsonProcessingException {
        ComposedSchema schema = new ComposedSchema();
        schema.setDescription("A composed schema");
        schema.addOneOfItem(new StringSchema());

        String json = objectMapper.writeValueAsString(schema);

        assertThat(json.contains("\"description\":\"A composed schema\""), is(true));
        assertThat(json.contains("\"oneOf\""), is(true));
    }

    @Test
    public void shouldSerializeComposedSchemaWithNullable() throws JsonProcessingException {
        ComposedSchema schema = new ComposedSchema();
        schema.setNullable(true);
        schema.addOneOfItem(new StringSchema());

        String json = objectMapper.writeValueAsString(schema);

        assertThat(json.contains("\"nullable\":true"), is(true));
        assertThat(json.contains("\"oneOf\""), is(true));
    }
}
