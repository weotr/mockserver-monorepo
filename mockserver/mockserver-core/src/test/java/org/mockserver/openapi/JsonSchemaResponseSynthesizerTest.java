package org.mockserver.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertTrue;

/**
 * Verifies that {@link JsonSchemaResponseSynthesizer} generates schema-valid bodies from a plain inline
 * JSON Schema by reusing the OpenAPI example-generation engine.
 */
public class JsonSchemaResponseSynthesizerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonSchemaResponseSynthesizer synthesizer = new JsonSchemaResponseSynthesizer(new MockServerLogger(JsonSchemaResponseSynthesizerTest.class));

    @Test
    public void shouldGenerateScalarStringValue() throws Exception {
        // when
        String body = synthesizer.synthesizeResponse("{\"type\":\"string\"}");

        // then - a bare JSON string
        JsonNode node = MAPPER.readTree(body);
        assertTrue("scalar string", node.isTextual());
    }

    @Test
    public void shouldHonourTypesRequiredEnumDefaultArrayAndNestedObject() throws Exception {
        // given - a single object schema exercising the required generation features
        String schema = "{" +
            "\"type\":\"object\"," +
            "\"required\":[\"id\",\"status\",\"active\"]," +
            "\"properties\":{" +
            "\"id\":{\"type\":\"integer\"}," +
            "\"score\":{\"type\":\"number\"}," +
            "\"active\":{\"type\":\"boolean\"}," +
            "\"status\":{\"type\":\"string\",\"enum\":[\"OPEN\",\"CLOSED\"]}," +
            "\"role\":{\"type\":\"string\",\"default\":\"admin\"}," +
            "\"tags\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"minItems\":2}," +
            "\"owner\":{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},\"age\":{\"type\":\"integer\"}}}" +
            "}}";

        // when
        JsonNode body = MAPPER.readTree(synthesizer.synthesizeResponse(schema));

        // then - types
        assertTrue("integer", body.get("id").isInt());
        assertTrue("number", body.get("score").isNumber());
        assertTrue("boolean", body.get("active").isBoolean());
        // enum -> first value
        assertThat(body.get("status").asText(), is("OPEN"));
        // default -> declared default
        assertThat(body.get("role").asText(), is("admin"));
        // array honours minItems and item type
        assertTrue("array", body.get("tags").isArray());
        assertThat(body.get("tags").size(), is(greaterThanOrEqualTo(2)));
        assertTrue("array item string", body.get("tags").get(0).isTextual());
        // nested object
        assertTrue("nested object", body.get("owner").isObject());
        assertTrue("nested string", body.get("owner").get("name").isTextual());
        assertTrue("nested integer", body.get("owner").get("age").isInt());
    }

    @Test
    public void shouldGenerateArrayOfObjectsAtRoot() throws Exception {
        // given - a root array schema of objects
        String schema = "{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"sku\":{\"type\":\"string\"}}}}";

        // when
        JsonNode body = MAPPER.readTree(synthesizer.synthesizeResponse(schema));

        // then - a JSON array of objects
        assertTrue("root array", body.isArray());
        assertTrue("array element object", body.get(0).isObject());
        assertTrue("sku string", body.get(0).get("sku").isTextual());
    }

    @Test
    public void shouldReturnNullForBlankSchema() {
        assertThat(synthesizer.synthesizeResponse("   "), is(nullValue()));
        assertThat(synthesizer.synthesizeResponse(null), is(nullValue()));
    }

    @Test(expected = JsonSchemaResponseSynthesisException.class)
    public void shouldThrowSynthesisExceptionForUnparseableSchema() {
        synthesizer.synthesizeResponse("this is not valid json <<<");
    }
}
