package org.mockserver.serialization.serializers.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.media.FileSchema;
import org.junit.Test;
import org.mockserver.serialization.ObjectMapperFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class FileSchemaSerializerTest {

    private final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();

    @Test
    public void shouldSerializeEmptyFileSchema() throws JsonProcessingException {
        assertThat(
            objectMapper.writeValueAsString(new FileSchema()),
            is("{\"format\":\"binary\",\"type\":\"string\"}")
        );
    }

    @Test
    public void shouldSerializeFileSchemaWithDescription() throws JsonProcessingException {
        FileSchema schema = new FileSchema();
        schema.setDescription("A file upload");

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"description\":\"A file upload\",\"format\":\"binary\",\"type\":\"string\"}")
        );
    }

    @Test
    public void shouldSerializeFileSchemaWithNullable() throws JsonProcessingException {
        FileSchema schema = new FileSchema();
        schema.setNullable(true);

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"format\":\"binary\",\"nullable\":true,\"type\":\"string\"}")
        );
    }
}
