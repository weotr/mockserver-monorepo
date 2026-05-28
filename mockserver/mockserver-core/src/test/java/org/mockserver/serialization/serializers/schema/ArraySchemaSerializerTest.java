package org.mockserver.serialization.serializers.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.junit.Test;
import org.mockserver.serialization.ObjectMapperFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class ArraySchemaSerializerTest {

    private final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();

    @Test
    public void shouldSerializeArraySchemaWithStringItems() throws JsonProcessingException {
        ArraySchema schema = new ArraySchema();
        schema.setItems(new StringSchema());

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"items\":{\"type\":\"string\"},\"type\":\"array\"}")
        );
    }

    @Test
    public void shouldSerializeArraySchemaWithIntegerItems() throws JsonProcessingException {
        ArraySchema schema = new ArraySchema();
        schema.setItems(new IntegerSchema());

        assertThat(
            objectMapper.writeValueAsString(schema),
            is("{\"items\":{\"format\":\"int32\",\"type\":\"integer\"},\"type\":\"array\"}")
        );
    }

    @Test
    public void shouldSerializeArraySchemaWithMinAndMaxItems() throws JsonProcessingException {
        ArraySchema schema = new ArraySchema();
        schema.setItems(new StringSchema());
        schema.setMinItems(1);
        schema.setMaxItems(10);

        String json = objectMapper.writeValueAsString(schema);

        assertThat(json.contains("\"type\":\"array\""), is(true));
        assertThat(json.contains("\"minItems\":1"), is(true));
        assertThat(json.contains("\"maxItems\":10"), is(true));
        assertThat(json.contains("\"items\":{\"type\":\"string\"}"), is(true));
    }

    @Test
    public void shouldSerializeArraySchemaWithUniqueItems() throws JsonProcessingException {
        ArraySchema schema = new ArraySchema();
        schema.setItems(new StringSchema());
        schema.setUniqueItems(true);

        String json = objectMapper.writeValueAsString(schema);

        assertThat(json.contains("\"type\":\"array\""), is(true));
        assertThat(json.contains("\"uniqueItems\":true"), is(true));
    }

    @Test
    public void shouldSerializeArraySchemaWithDescription() throws JsonProcessingException {
        ArraySchema schema = new ArraySchema();
        schema.setItems(new StringSchema());
        schema.setDescription("A list of strings");

        String json = objectMapper.writeValueAsString(schema);

        assertThat(json.contains("\"type\":\"array\""), is(true));
        assertThat(json.contains("\"description\":\"A list of strings\""), is(true));
    }
}
