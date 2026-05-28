package org.mockserver.serialization.serializers.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.media.ByteArraySchema;
import org.junit.Test;
import org.mockserver.serialization.ObjectMapperFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class ByteArraySchemaSerializerTest {

    private final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();

    @Test
    public void shouldSerializeEmptyByteArraySchema() throws JsonProcessingException {
        assertThat(
            objectMapper.writeValueAsString(new ByteArraySchema()),
            is("{\"format\":\"byte\",\"type\":\"string\"}")
        );
    }

    @Test
    public void shouldSerializeByteArraySchemaWithDescription() throws JsonProcessingException {
        ByteArraySchema schema = new ByteArraySchema();
        schema.setDescription("Base64 encoded data");

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"description\":\"Base64 encoded data\",\"format\":\"byte\",\"type\":\"string\"}")
        );
    }

    @Test
    public void shouldSerializeByteArraySchemaWithNullable() throws JsonProcessingException {
        ByteArraySchema schema = new ByteArraySchema();
        schema.setNullable(true);

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"format\":\"byte\",\"nullable\":true,\"type\":\"string\"}")
        );
    }
}
