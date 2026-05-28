package org.mockserver.serialization.serializers.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.media.BooleanSchema;
import org.junit.Test;
import org.mockserver.serialization.ObjectMapperFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class BooleanSchemaSerializerTest {

    private final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();

    @Test
    public void shouldSerializeEmptyBooleanSchema() throws JsonProcessingException {
        assertThat(
            objectMapper.writeValueAsString(new BooleanSchema()),
            is("{\"type\":\"boolean\"}")
        );
    }

    @Test
    public void shouldSerializeBooleanSchemaWithDescription() throws JsonProcessingException {
        BooleanSchema schema = new BooleanSchema();
        schema.setDescription("A boolean field");

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"description\":\"A boolean field\",\"type\":\"boolean\"}")
        );
    }

    @Test
    public void shouldSerializeBooleanSchemaWithNullable() throws JsonProcessingException {
        BooleanSchema schema = new BooleanSchema();
        schema.setNullable(true);

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"nullable\":true,\"type\":\"boolean\"}")
        );
    }

    @Test
    public void shouldSerializeBooleanSchemaWithExample() throws JsonProcessingException {
        BooleanSchema schema = new BooleanSchema();
        schema.setExample(true);

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"example\":true,\"type\":\"boolean\"}")
        );
    }
}
