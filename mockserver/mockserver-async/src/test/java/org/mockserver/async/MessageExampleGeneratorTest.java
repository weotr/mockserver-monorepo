package org.mockserver.async;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.mockserver.async.asyncapi.AsyncApiChannel;
import org.mockserver.async.asyncapi.AsyncApiMessage;
import org.mockserver.async.asyncapi.AsyncApiSpec;

import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Tests for {@link MessageExampleGenerator}.
 */
public class MessageExampleGeneratorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final MessageExampleGenerator generator = new MessageExampleGenerator();

    @Test
    public void shouldUseExplicitExampleWhenPresent() throws Exception {
        JsonNode example = MAPPER.readTree("{\"userId\": \"abc123\", \"email\": \"user@test.com\"}");
        JsonNode schema = MAPPER.readTree("{\"type\": \"object\", \"properties\": {\"userId\": {\"type\": \"string\"}}}");

        AsyncApiChannel channel = new AsyncApiChannel("users", List.of(example), schema);
        AsyncApiSpec spec = new AsyncApiSpec("2.6.0", "Test", List.of(channel));

        Map<String, String> examples = generator.generateExamples(spec);
        assertThat(examples, hasKey("users"));

        JsonNode result = MAPPER.readTree(examples.get("users"));
        assertThat(result.get("userId").asText(), is("abc123"));
        assertThat(result.get("email").asText(), is("user@test.com"));
    }

    @Test
    public void shouldSynthesizeFromSchemaWhenNoExample() throws Exception {
        JsonNode schema = MAPPER.readTree(
            "{\"type\": \"object\", \"properties\": {" +
                "\"name\": {\"type\": \"string\"}," +
                "\"age\": {\"type\": \"integer\"}," +
                "\"active\": {\"type\": \"boolean\"}," +
                "\"score\": {\"type\": \"number\"}" +
            "}}"
        );

        AsyncApiChannel channel = new AsyncApiChannel("profiles", List.of(), schema);
        AsyncApiSpec spec = new AsyncApiSpec("2.6.0", "Test", List.of(channel));

        Map<String, String> examples = generator.generateExamples(spec);
        assertThat(examples, hasKey("profiles"));

        JsonNode result = MAPPER.readTree(examples.get("profiles"));
        assertThat(result.get("name").asText(), is("string"));
        assertThat(result.get("age").asInt(), is(0));
        assertThat(result.get("active").asBoolean(), is(false));
        assertThat(result.get("score").asDouble(), is(0.0));
    }

    @Test
    public void shouldSynthesizeArrayType() throws Exception {
        JsonNode schema = MAPPER.readTree(
            "{\"type\": \"array\", \"items\": {\"type\": \"string\"}}"
        );

        AsyncApiChannel channel = new AsyncApiChannel("tags", List.of(), schema);

        String payload = generator.generateExample(channel);
        JsonNode result = MAPPER.readTree(payload);
        assertThat(result.isArray(), is(true));
        assertThat(result.size(), is(1));
        assertThat(result.get(0).asText(), is("string"));
    }

    @Test
    public void shouldFallbackToEmptyObjectWhenNoSchemaAndNoExample() {
        AsyncApiChannel channel = new AsyncApiChannel("empty", List.of(), null);
        String payload = generator.generateExample(channel);
        assertThat(payload, is("{}"));
    }

    @Test
    public void shouldSynthesizeNestedObject() throws Exception {
        JsonNode schema = MAPPER.readTree(
            "{\"type\": \"object\", \"properties\": {" +
                "\"address\": {\"type\": \"object\", \"properties\": {" +
                    "\"street\": {\"type\": \"string\"}," +
                    "\"zip\": {\"type\": \"integer\"}" +
                "}}" +
            "}}"
        );

        AsyncApiChannel channel = new AsyncApiChannel("users", List.of(), schema);

        String payload = generator.generateExample(channel);
        JsonNode result = MAPPER.readTree(payload);
        assertThat(result.has("address"), is(true));
        assertThat(result.get("address").get("street").asText(), is("string"));
        assertThat(result.get("address").get("zip").asInt(), is(0));
    }

    @Test
    public void shouldGenerateExamplesForMultipleChannels() throws Exception {
        JsonNode example1 = MAPPER.readTree("{\"a\": 1}");
        JsonNode example2 = MAPPER.readTree("{\"b\": 2}");

        AsyncApiChannel channel1 = new AsyncApiChannel("ch1", List.of(example1), null);
        AsyncApiChannel channel2 = new AsyncApiChannel("ch2", List.of(example2), null);
        AsyncApiSpec spec = new AsyncApiSpec("2.6.0", "Multi", List.of(channel1, channel2));

        Map<String, String> examples = generator.generateExamples(spec);
        assertThat(examples, hasKey("ch1"));
        assertThat(examples, hasKey("ch2"));
        assertThat(MAPPER.readTree(examples.get("ch1")).get("a").asInt(), is(1));
        assertThat(MAPPER.readTree(examples.get("ch2")).get("b").asInt(), is(2));
    }

    @Test
    public void shouldHandleSchemaWithoutExplicitType() throws Exception {
        // Schema with properties but no "type" field — should infer object
        JsonNode schema = MAPPER.readTree(
            "{\"properties\": {\"name\": {\"type\": \"string\"}}}"
        );

        AsyncApiChannel channel = new AsyncApiChannel("inferred", List.of(), schema);
        String payload = generator.generateExample(channel);
        JsonNode result = MAPPER.readTree(payload);
        assertThat(result.has("name"), is(true));
        assertThat(result.get("name").asText(), is("string"));
    }

    // ---- generateExample(AsyncApiMessage) ----

    @Test
    public void shouldGenerateExampleFromMessageWithExplicitExample() throws Exception {
        JsonNode example = MAPPER.readTree("{\"userId\": \"abc123\"}");
        AsyncApiMessage message = new AsyncApiMessage("testMsg", null, List.of(example), null);

        String payload = generator.generateExample(message);
        JsonNode result = MAPPER.readTree(payload);
        assertThat(result.get("userId").asText(), is("abc123"));
    }

    @Test
    public void shouldGenerateExampleFromMessageWithSchema() throws Exception {
        JsonNode schema = MAPPER.readTree(
            "{\"type\": \"object\", \"properties\": {\"name\": {\"type\": \"string\"}}}"
        );
        AsyncApiMessage message = new AsyncApiMessage("testMsg", schema, List.of(), null);

        String payload = generator.generateExample(message);
        JsonNode result = MAPPER.readTree(payload);
        assertThat(result.get("name").asText(), is("string"));
    }

    @Test
    public void shouldGenerateEmptyObjectFromMessageWithNoSchemaOrExample() {
        AsyncApiMessage message = new AsyncApiMessage("empty", null, List.of(), null);
        String payload = generator.generateExample(message);
        assertThat(payload, is("{}"));
    }

    @Test
    public void shouldPreferExplicitExampleOverSchemaForMessage() throws Exception {
        JsonNode example = MAPPER.readTree("{\"orderId\": 42}");
        JsonNode schema = MAPPER.readTree(
            "{\"type\": \"object\", \"properties\": {\"orderId\": {\"type\": \"integer\"}}}"
        );
        AsyncApiMessage message = new AsyncApiMessage("msg", schema, List.of(example), null);

        String payload = generator.generateExample(message);
        JsonNode result = MAPPER.readTree(payload);
        assertThat(result.get("orderId").asInt(), is(42));
    }
}
