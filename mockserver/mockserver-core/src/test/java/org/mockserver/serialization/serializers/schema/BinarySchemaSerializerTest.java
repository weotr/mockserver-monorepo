package org.mockserver.serialization.serializers.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.media.BinarySchema;
import org.junit.Test;
import org.mockserver.serialization.ObjectMapperFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class BinarySchemaSerializerTest {

    private final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();

    @Test
    public void shouldSerializeEmptyBinarySchema() throws JsonProcessingException {
        assertThat(
            objectMapper.writeValueAsString(new BinarySchema()),
            is("{\"format\":\"binary\",\"type\":\"string\"}")
        );
    }

    @Test
    public void shouldSerializeBinarySchemaWithDescription() throws JsonProcessingException {
        BinarySchema schema = new BinarySchema();
        schema.setDescription("Binary content");

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"description\":\"Binary content\",\"format\":\"binary\",\"type\":\"string\"}")
        );
    }

    @Test
    public void shouldSerializeBinarySchemaWithNullable() throws JsonProcessingException {
        BinarySchema schema = new BinarySchema();
        schema.setNullable(true);

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"format\":\"binary\",\"nullable\":true,\"type\":\"string\"}")
        );
    }
}
