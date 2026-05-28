package org.mockserver.serialization.serializers.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.media.IntegerSchema;
import org.junit.Test;
import org.mockserver.serialization.ObjectMapperFactory;

import java.math.BigDecimal;
import java.util.Arrays;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class IntegerSchemaSerializerTest {

    private final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();

    @Test
    public void shouldSerializeEmptyIntegerSchema() throws JsonProcessingException {
        assertThat(
            objectMapper.writeValueAsString(new IntegerSchema()),
            is("{\"format\":\"int32\",\"type\":\"integer\"}")
        );
    }

    @Test
    public void shouldSerializeIntegerSchemaWithInt64Format() throws JsonProcessingException {
        IntegerSchema schema = new IntegerSchema();
        schema.setFormat("int64");

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"format\":\"int64\",\"type\":\"integer\"}")
        );
    }

    @Test
    public void shouldSerializeIntegerSchemaWithMinAndMax() throws JsonProcessingException {
        IntegerSchema schema = new IntegerSchema();
        schema.setMinimum(BigDecimal.ZERO);
        schema.setMaximum(BigDecimal.valueOf(100));

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"format\":\"int32\",\"maximum\":1E+2,\"minimum\":0,\"type\":\"integer\"}")
        );
    }

    @Test
    public void shouldSerializeIntegerSchemaWithDescription() throws JsonProcessingException {
        IntegerSchema schema = new IntegerSchema();
        schema.setDescription("An integer field");

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"description\":\"An integer field\",\"format\":\"int32\",\"type\":\"integer\"}")
        );
    }

    @Test
    public void shouldSerializeIntegerSchemaWithEnum() throws JsonProcessingException {
        IntegerSchema schema = new IntegerSchema();
        schema.setEnum(Arrays.asList(1, 2, 3));

        String json = objectMapper.writeValueAsString(schema);

        assertThat(json.contains("\"type\":\"integer\""), is(true));
        assertThat(json.contains("\"enum\""), is(true));
    }

    @Test
    public void shouldSerializeIntegerSchemaWithNullable() throws JsonProcessingException {
        IntegerSchema schema = new IntegerSchema();
        schema.setNullable(true);

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"format\":\"int32\",\"nullable\":true,\"type\":\"integer\"}")
        );
    }

    @Test
    public void shouldSerializeIntegerSchemaWithExclusiveMinimum() throws JsonProcessingException {
        IntegerSchema schema = new IntegerSchema();
        schema.setExclusiveMinimum(true);
        schema.setMinimum(BigDecimal.ZERO);

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"exclusiveMinimum\":0,\"format\":\"int32\",\"type\":\"integer\"}")
        );
    }
}
