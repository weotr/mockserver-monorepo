package org.mockserver.serialization.serializers.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.media.NumberSchema;
import org.junit.Test;
import org.mockserver.serialization.ObjectMapperFactory;

import java.math.BigDecimal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class NumberSchemaSerializerTest {

    private final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();

    @Test
    public void shouldSerializeEmptyNumberSchema() throws JsonProcessingException {
        assertThat(
            objectMapper.writeValueAsString(new NumberSchema()),
            is("{\"type\":\"number\"}")
        );
    }

    @Test
    public void shouldSerializeNumberSchemaWithDoubleFormat() throws JsonProcessingException {
        NumberSchema schema = new NumberSchema();
        schema.setFormat("double");

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"format\":\"double\",\"type\":\"number\"}")
        );
    }

    @Test
    public void shouldSerializeNumberSchemaWithFloatFormat() throws JsonProcessingException {
        NumberSchema schema = new NumberSchema();
        schema.setFormat("float");

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"format\":\"float\",\"type\":\"number\"}")
        );
    }

    @Test
    public void shouldSerializeNumberSchemaWithMinAndMax() throws JsonProcessingException {
        NumberSchema schema = new NumberSchema();
        schema.setMinimum(BigDecimal.ZERO);
        schema.setMaximum(BigDecimal.TEN);

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"maximum\":1E+1,\"minimum\":0,\"type\":\"number\"}")
        );
    }

    @Test
    public void shouldSerializeNumberSchemaWithDescription() throws JsonProcessingException {
        NumberSchema schema = new NumberSchema();
        schema.setDescription("A number field");

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"description\":\"A number field\",\"type\":\"number\"}")
        );
    }

    @Test
    public void shouldSerializeNumberSchemaWithNullable() throws JsonProcessingException {
        NumberSchema schema = new NumberSchema();
        schema.setNullable(true);

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"nullable\":true,\"type\":\"number\"}")
        );
    }
}
