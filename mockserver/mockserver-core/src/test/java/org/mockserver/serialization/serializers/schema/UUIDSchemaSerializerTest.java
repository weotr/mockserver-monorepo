package org.mockserver.serialization.serializers.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.media.UUIDSchema;
import org.junit.Test;
import org.mockserver.serialization.ObjectMapperFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class UUIDSchemaSerializerTest {

    private final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();

    @Test
    public void shouldSerializeEmptyUUIDSchema() throws JsonProcessingException {
        assertThat(
            objectMapper.writeValueAsString(new UUIDSchema()),
            is("{\"format\":\"uuid\",\"type\":\"string\"}")
        );
    }

    @Test
    public void shouldSerializeUUIDSchemaWithDescription() throws JsonProcessingException {
        UUIDSchema schema = new UUIDSchema();
        schema.setDescription("A unique identifier");

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"description\":\"A unique identifier\",\"format\":\"uuid\",\"type\":\"string\"}")
        );
    }

    @Test
    public void shouldSerializeUUIDSchemaWithNullable() throws JsonProcessingException {
        UUIDSchema schema = new UUIDSchema();
        schema.setNullable(true);

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"format\":\"uuid\",\"nullable\":true,\"type\":\"string\"}")
        );
    }
}
