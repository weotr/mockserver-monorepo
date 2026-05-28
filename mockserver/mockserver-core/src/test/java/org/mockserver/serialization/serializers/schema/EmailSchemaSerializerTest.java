package org.mockserver.serialization.serializers.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.media.EmailSchema;
import org.junit.Test;
import org.mockserver.serialization.ObjectMapperFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class EmailSchemaSerializerTest {

    private final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();

    @Test
    public void shouldSerializeEmptyEmailSchema() throws JsonProcessingException {
        assertThat(
            objectMapper.writeValueAsString(new EmailSchema()),
            is("{\"format\":\"email\",\"type\":\"string\"}")
        );
    }

    @Test
    public void shouldSerializeEmailSchemaWithDescription() throws JsonProcessingException {
        EmailSchema schema = new EmailSchema();
        schema.setDescription("An email address");

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"description\":\"An email address\",\"format\":\"email\",\"type\":\"string\"}")
        );
    }

    @Test
    public void shouldSerializeEmailSchemaWithNullable() throws JsonProcessingException {
        EmailSchema schema = new EmailSchema();
        schema.setNullable(true);

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"format\":\"email\",\"nullable\":true,\"type\":\"string\"}")
        );
    }

    @Test
    public void shouldSerializeEmailSchemaWithPattern() throws JsonProcessingException {
        EmailSchema schema = new EmailSchema();
        schema.setPattern("^[a-z]+@example\\.com$");

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"format\":\"email\",\"pattern\":\"^[a-z]+@example\\\\.com$\",\"type\":\"string\"}")
        );
    }
}
