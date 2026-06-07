package org.mockserver.async;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.mockserver.async.asyncapi.AsyncApiChannel;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Tests for the schema-aware example generation in {@link MessageExampleGenerator}.
 * These test the enhanced synthesis that respects enum, default, format,
 * minimum/maximum, minLength, and const constraints.
 */
public class MessageExampleGeneratorSchemaAwareTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final MessageExampleGenerator generator = new MessageExampleGenerator();

    @Test
    public void shouldUseEnumFirstValue() throws Exception {
        JsonNode schema = MAPPER.readTree(
            "{\"type\": \"object\", \"properties\": {\"status\": {\"type\": \"string\", \"enum\": [\"active\", \"inactive\"]}}}"
        );
        AsyncApiChannel channel = new AsyncApiChannel("test", List.of(), schema);
        String payload = generator.generateExample(channel);
        JsonNode result = MAPPER.readTree(payload);
        assertThat(result.get("status").asText(), is("active"));
    }

    @Test
    public void shouldUseDefaultValue() throws Exception {
        JsonNode schema = MAPPER.readTree(
            "{\"type\": \"object\", \"properties\": {\"count\": {\"type\": \"integer\", \"default\": 42}}}"
        );
        AsyncApiChannel channel = new AsyncApiChannel("test", List.of(), schema);
        String payload = generator.generateExample(channel);
        JsonNode result = MAPPER.readTree(payload);
        assertThat(result.get("count").asInt(), is(42));
    }

    @Test
    public void shouldUseConstValue() throws Exception {
        JsonNode schema = MAPPER.readTree(
            "{\"type\": \"object\", \"properties\": {\"version\": {\"type\": \"string\", \"const\": \"v2\"}}}"
        );
        AsyncApiChannel channel = new AsyncApiChannel("test", List.of(), schema);
        String payload = generator.generateExample(channel);
        JsonNode result = MAPPER.readTree(payload);
        assertThat(result.get("version").asText(), is("v2"));
    }

    @Test
    public void shouldRespectMinimum() throws Exception {
        JsonNode schema = MAPPER.readTree(
            "{\"type\": \"object\", \"properties\": {\"score\": {\"type\": \"integer\", \"minimum\": 10}}}"
        );
        AsyncApiChannel channel = new AsyncApiChannel("test", List.of(), schema);
        String payload = generator.generateExample(channel);
        JsonNode result = MAPPER.readTree(payload);
        assertThat(result.get("score").asInt(), is(greaterThanOrEqualTo(10)));
    }

    @Test
    public void shouldRespectExclusiveMinimum() throws Exception {
        JsonNode schema = MAPPER.readTree(
            "{\"type\": \"object\", \"properties\": {\"score\": {\"type\": \"integer\", \"exclusiveMinimum\": 0}}}"
        );
        AsyncApiChannel channel = new AsyncApiChannel("test", List.of(), schema);
        String payload = generator.generateExample(channel);
        JsonNode result = MAPPER.readTree(payload);
        assertThat(result.get("score").asInt(), is(greaterThan(0)));
    }

    @Test
    public void shouldSynthesizeDateTimeFormat() throws Exception {
        JsonNode schema = MAPPER.readTree(
            "{\"type\": \"object\", \"properties\": {\"createdAt\": {\"type\": \"string\", \"format\": \"date-time\"}}}"
        );
        AsyncApiChannel channel = new AsyncApiChannel("test", List.of(), schema);
        String payload = generator.generateExample(channel);
        JsonNode result = MAPPER.readTree(payload);
        assertThat(result.get("createdAt").asText(), containsString("T"));
    }

    @Test
    public void shouldSynthesizeEmailFormat() throws Exception {
        JsonNode schema = MAPPER.readTree(
            "{\"type\": \"object\", \"properties\": {\"email\": {\"type\": \"string\", \"format\": \"email\"}}}"
        );
        AsyncApiChannel channel = new AsyncApiChannel("test", List.of(), schema);
        String payload = generator.generateExample(channel);
        JsonNode result = MAPPER.readTree(payload);
        assertThat(result.get("email").asText(), containsString("@"));
    }

    @Test
    public void shouldSynthesizeUuidFormat() throws Exception {
        JsonNode schema = MAPPER.readTree(
            "{\"type\": \"object\", \"properties\": {\"id\": {\"type\": \"string\", \"format\": \"uuid\"}}}"
        );
        AsyncApiChannel channel = new AsyncApiChannel("test", List.of(), schema);
        String payload = generator.generateExample(channel);
        JsonNode result = MAPPER.readTree(payload);
        assertThat(result.get("id").asText(), containsString("-"));
    }

    @Test
    public void shouldRespectMinLength() throws Exception {
        JsonNode schema = MAPPER.readTree(
            "{\"type\": \"object\", \"properties\": {\"code\": {\"type\": \"string\", \"minLength\": 10}}}"
        );
        AsyncApiChannel channel = new AsyncApiChannel("test", List.of(), schema);
        String payload = generator.generateExample(channel);
        JsonNode result = MAPPER.readTree(payload);
        assertThat(result.get("code").asText().length(), is(greaterThanOrEqualTo(10)));
    }

    @Test
    public void shouldRespectMinItems() throws Exception {
        JsonNode schema = MAPPER.readTree(
            "{\"type\": \"array\", \"items\": {\"type\": \"string\"}, \"minItems\": 3}"
        );
        AsyncApiChannel channel = new AsyncApiChannel("test", List.of(), schema);
        String payload = generator.generateExample(channel);
        JsonNode result = MAPPER.readTree(payload);
        assertThat(result.isArray(), is(true));
        assertThat(result.size(), is(greaterThanOrEqualTo(3)));
    }

    @Test
    public void shouldSynthesizeNumberWithMinimum() throws Exception {
        JsonNode schema = MAPPER.readTree(
            "{\"type\": \"object\", \"properties\": {\"price\": {\"type\": \"number\", \"minimum\": 9.99}}}"
        );
        AsyncApiChannel channel = new AsyncApiChannel("test", List.of(), schema);
        String payload = generator.generateExample(channel);
        JsonNode result = MAPPER.readTree(payload);
        assertThat(result.get("price").asDouble(), is(greaterThanOrEqualTo(9.99)));
    }
}
