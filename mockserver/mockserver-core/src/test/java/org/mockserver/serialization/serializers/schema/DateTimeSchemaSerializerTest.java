package org.mockserver.serialization.serializers.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.media.DateTimeSchema;
import org.junit.Test;
import org.mockserver.serialization.ObjectMapperFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class DateTimeSchemaSerializerTest {

    private final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();

    @Test
    public void shouldSerializeEmptyDateTimeSchema() throws JsonProcessingException {
        assertThat(
            objectMapper.writeValueAsString(new DateTimeSchema()),
            is("{\"format\":\"date-time\",\"type\":\"string\"}")
        );
    }

    @Test
    public void shouldSerializeDateTimeSchemaWithDescription() throws JsonProcessingException {
        DateTimeSchema schema = new DateTimeSchema();
        schema.setDescription("A date-time field");

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"description\":\"A date-time field\",\"format\":\"date-time\",\"type\":\"string\"}")
        );
    }

    @Test
    public void shouldSerializeDateTimeSchemaWithNullable() throws JsonProcessingException {
        DateTimeSchema schema = new DateTimeSchema();
        schema.setNullable(true);

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"format\":\"date-time\",\"nullable\":true,\"type\":\"string\"}")
        );
    }
}
