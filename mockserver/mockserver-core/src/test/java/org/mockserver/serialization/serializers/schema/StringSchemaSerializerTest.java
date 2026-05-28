package org.mockserver.serialization.serializers.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.media.StringSchema;
import org.junit.Test;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.Arrays;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class StringSchemaSerializerTest {

    private final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();

    @Test
    public void shouldSerializeEmptyStringSchema() throws JsonProcessingException {
        assertThat(
            objectMapper.writeValueAsString(new StringSchema()),
            is("{\"type\":\"string\"}")
        );
    }

    @Test
    public void shouldSerializeStringSchemaWithPattern() throws JsonProcessingException {
        StringSchema schema = new StringSchema();
        schema.setPattern("^[a-z]+$");

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"pattern\":\"^[a-z]+$\",\"type\":\"string\"}")
        );
    }

    @Test
    public void shouldSerializeStringSchemaWithMinAndMaxLength() throws JsonProcessingException {
        StringSchema schema = new StringSchema();
        schema.setMinLength(1);
        schema.setMaxLength(100);

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"maxLength\":100,\"minLength\":1,\"type\":\"string\"}")
        );
    }

    @Test
    public void shouldSerializeStringSchemaWithDescription() throws JsonProcessingException {
        StringSchema schema = new StringSchema();
        schema.setDescription("A string field");

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"description\":\"A string field\",\"type\":\"string\"}")
        );
    }

    @Test
    public void shouldSerializeStringSchemaWithEnum() throws JsonProcessingException {
        StringSchema schema = new StringSchema();
        schema.setEnum(Arrays.asList("red", "green", "blue"));

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"enum\":[\"red\",\"green\",\"blue\"],\"type\":\"string\"}")
        );
    }

    @Test
    public void shouldSerializeStringSchemaWithAllProperties() throws JsonProcessingException {
        StringSchema schema = new StringSchema();
        schema.setDescription("A string");
        schema.setMinLength(1);
        schema.setMaxLength(100);
        schema.setPattern("^[a-z]+$");

        String json = objectMapper.writeValueAsString(schema);

        assertThat(json.contains("\"type\":\"string\""), is(true));
        assertThat(json.contains("\"description\":\"A string\""), is(true));
        assertThat(json.contains("\"minLength\":1"), is(true));
        assertThat(json.contains("\"maxLength\":100"), is(true));
        assertThat(json.contains("\"pattern\":\"^[a-z]+$\""), is(true));
    }

    @Test
    public void shouldSerializeStringSchemaWithNullable() throws JsonProcessingException {
        StringSchema schema = new StringSchema();
        schema.setNullable(true);

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"nullable\":true,\"type\":\"string\"}")
        );
    }
}
