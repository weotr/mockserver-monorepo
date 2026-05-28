package org.mockserver.serialization.serializers.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.media.DateSchema;
import org.junit.Test;
import org.mockserver.serialization.ObjectMapperFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class DateSchemaSerializerTest {

    private final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();

    @Test
    public void shouldSerializeEmptyDateSchema() throws JsonProcessingException {
        assertThat(
            objectMapper.writeValueAsString(new DateSchema()),
            is("{\"format\":\"date\",\"type\":\"string\"}")
        );
    }

    @Test
    public void shouldSerializeDateSchemaWithDescription() throws JsonProcessingException {
        DateSchema schema = new DateSchema();
        schema.setDescription("A date field");

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"description\":\"A date field\",\"format\":\"date\",\"type\":\"string\"}")
        );
    }

    @Test
    public void shouldSerializeDateSchemaWithNullable() throws JsonProcessingException {
        DateSchema schema = new DateSchema();
        schema.setNullable(true);

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"format\":\"date\",\"nullable\":true,\"type\":\"string\"}")
        );
    }
}
