package org.mockserver.serialization.serializers.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.media.PasswordSchema;
import org.junit.Test;
import org.mockserver.serialization.ObjectMapperFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class PasswordSchemaSerializerTest {

    private final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();

    @Test
    public void shouldSerializeEmptyPasswordSchema() throws JsonProcessingException {
        assertThat(
            objectMapper.writeValueAsString(new PasswordSchema()),
            is("{\"format\":\"password\",\"type\":\"string\"}")
        );
    }

    @Test
    public void shouldSerializePasswordSchemaWithDescription() throws JsonProcessingException {
        PasswordSchema schema = new PasswordSchema();
        schema.setDescription("User password");

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"description\":\"User password\",\"format\":\"password\",\"type\":\"string\"}")
        );
    }

    @Test
    public void shouldSerializePasswordSchemaWithMinAndMaxLength() throws JsonProcessingException {
        PasswordSchema schema = new PasswordSchema();
        schema.setMinLength(8);
        schema.setMaxLength(128);

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"format\":\"password\",\"maxLength\":128,\"minLength\":8,\"type\":\"string\"}")
        );
    }

    @Test
    public void shouldSerializePasswordSchemaWithNullable() throws JsonProcessingException {
        PasswordSchema schema = new PasswordSchema();
        schema.setNullable(true);

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"format\":\"password\",\"nullable\":true,\"type\":\"string\"}")
        );
    }
}
