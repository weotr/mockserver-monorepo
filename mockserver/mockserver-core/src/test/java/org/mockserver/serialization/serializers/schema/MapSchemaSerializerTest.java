package org.mockserver.serialization.serializers.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.media.MapSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.junit.Test;
import org.mockserver.serialization.ObjectMapperFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class MapSchemaSerializerTest {

    private final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();

    @Test
    public void shouldSerializeEmptyMapSchema() throws JsonProcessingException {
        assertThat(
            objectMapper.writeValueAsString(new MapSchema()),
            is("{\"type\":\"object\"}")
        );
    }

    @Test
    public void shouldSerializeMapSchemaWithDescription() throws JsonProcessingException {
        MapSchema schema = new MapSchema();
        schema.setDescription("A map of values");

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"description\":\"A map of values\",\"type\":\"object\"}")
        );
    }

    @Test
    @SuppressWarnings("rawtypes")
    public void shouldSerializeMapSchemaWithAdditionalProperties() throws JsonProcessingException {
        MapSchema schema = new MapSchema();
        schema.setAdditionalProperties(new StringSchema());

        String json = objectMapper.writeValueAsString(schema);

        assertThat(json.contains("\"type\":\"object\""), is(true));
        assertThat(json.contains("\"additionalProperties\""), is(true));
    }

    @Test
    public void shouldSerializeMapSchemaWithNullable() throws JsonProcessingException {
        MapSchema schema = new MapSchema();
        schema.setNullable(true);

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"nullable\":true,\"type\":\"object\"}")
        );
    }
}
