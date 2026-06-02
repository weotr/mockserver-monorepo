package org.mockserver.async.asyncapi;

import org.junit.Test;

import java.io.IOException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Tests for {@link AsyncApiParser} covering both AsyncAPI 2.x and 3.x parsing.
 */
public class AsyncApiParserTest {

    private final AsyncApiParser parser = new AsyncApiParser();

    // ---- AsyncAPI 2.x ----

    @Test
    public void shouldParseAsyncApi2xWithExplicitExample() throws IOException {
        String spec = "{\n" +
            "  \"asyncapi\": \"2.6.0\",\n" +
            "  \"info\": { \"title\": \"Test API\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"user/signedup\": {\n" +
            "      \"publish\": {\n" +
            "        \"message\": {\n" +
            "          \"payload\": {\n" +
            "            \"type\": \"object\",\n" +
            "            \"properties\": {\n" +
            "              \"userId\": { \"type\": \"string\" },\n" +
            "              \"email\": { \"type\": \"string\" }\n" +
            "            },\n" +
            "            \"example\": { \"userId\": \"abc123\", \"email\": \"user@test.com\" }\n" +
            "          }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        AsyncApiSpec parsed = parser.parse(spec);

        assertThat(parsed.getAsyncApiVersion(), is("2.6.0"));
        assertThat(parsed.getTitle(), is("Test API"));
        assertThat(parsed.getChannels(), hasSize(1));

        AsyncApiChannel channel = parsed.getChannels().get(0);
        assertThat(channel.getName(), is("user/signedup"));
        assertThat(channel.getPayloadExamples(), hasSize(1));
        assertThat(channel.getPayloadExamples().get(0).get("userId").asText(), is("abc123"));
        assertThat(channel.getPayloadExamples().get(0).get("email").asText(), is("user@test.com"));
        assertThat(channel.getPayloadSchema(), is(notNullValue()));
    }

    @Test
    public void shouldParseAsyncApi2xWithMessageExamplesArray() throws IOException {
        String spec = "{\n" +
            "  \"asyncapi\": \"2.6.0\",\n" +
            "  \"info\": { \"title\": \"Events\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"orders\": {\n" +
            "      \"subscribe\": {\n" +
            "        \"message\": {\n" +
            "          \"payload\": {\n" +
            "            \"type\": \"object\",\n" +
            "            \"properties\": {\n" +
            "              \"orderId\": { \"type\": \"integer\" }\n" +
            "            }\n" +
            "          },\n" +
            "          \"examples\": [\n" +
            "            { \"payload\": { \"orderId\": 42 } },\n" +
            "            { \"payload\": { \"orderId\": 99 } }\n" +
            "          ]\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        AsyncApiSpec parsed = parser.parse(spec);

        assertThat(parsed.getChannels(), hasSize(1));
        AsyncApiChannel channel = parsed.getChannels().get(0);
        assertThat(channel.getName(), is("orders"));
        // examples array should have 2 entries
        assertThat(channel.getPayloadExamples(), hasSize(2));
        assertThat(channel.getPayloadExamples().get(0).get("orderId").asInt(), is(42));
        assertThat(channel.getPayloadExamples().get(1).get("orderId").asInt(), is(99));
    }

    @Test
    public void shouldParseAsyncApi2xWithMultipleChannels() throws IOException {
        String spec = "{\n" +
            "  \"asyncapi\": \"2.0.0\",\n" +
            "  \"info\": { \"title\": \"Multi\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"topic-a\": {\n" +
            "      \"publish\": {\n" +
            "        \"message\": {\n" +
            "          \"payload\": {\n" +
            "            \"type\": \"string\",\n" +
            "            \"example\": \"hello\"\n" +
            "          }\n" +
            "        }\n" +
            "      }\n" +
            "    },\n" +
            "    \"topic-b\": {\n" +
            "      \"publish\": {\n" +
            "        \"message\": {\n" +
            "          \"payload\": { \"type\": \"integer\" }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        AsyncApiSpec parsed = parser.parse(spec);
        assertThat(parsed.getChannels(), hasSize(2));
    }

    @Test
    public void shouldParseAsyncApi2xYaml() throws IOException {
        String yamlSpec =
            "asyncapi: '2.6.0'\n" +
            "info:\n" +
            "  title: YAML Test\n" +
            "  version: '1.0.0'\n" +
            "channels:\n" +
            "  events:\n" +
            "    publish:\n" +
            "      message:\n" +
            "        payload:\n" +
            "          type: object\n" +
            "          properties:\n" +
            "            name:\n" +
            "              type: string\n" +
            "          example:\n" +
            "            name: test-event\n";

        AsyncApiSpec parsed = parser.parse(yamlSpec);

        assertThat(parsed.getAsyncApiVersion(), is("2.6.0"));
        assertThat(parsed.getTitle(), is("YAML Test"));
        assertThat(parsed.getChannels(), hasSize(1));
        assertThat(parsed.getChannels().get(0).getPayloadExamples(), hasSize(1));
        assertThat(parsed.getChannels().get(0).getPayloadExamples().get(0).get("name").asText(),
            is("test-event"));
    }

    @Test
    public void shouldTolerateChannelWithNoMessage() throws IOException {
        String spec = "{\n" +
            "  \"asyncapi\": \"2.6.0\",\n" +
            "  \"info\": { \"title\": \"Empty\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"empty-channel\": {}\n" +
            "  }\n" +
            "}";

        AsyncApiSpec parsed = parser.parse(spec);
        assertThat(parsed.getChannels(), hasSize(1));
        assertThat(parsed.getChannels().get(0).getName(), is("empty-channel"));
        assertThat(parsed.getChannels().get(0).getPayloadExamples(), is(empty()));
    }

    // ---- AsyncAPI 3.x ----

    @Test
    public void shouldParseAsyncApi3xWithInlineMessage() throws IOException {
        String spec = "{\n" +
            "  \"asyncapi\": \"3.0.0\",\n" +
            "  \"info\": { \"title\": \"V3 API\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"notifications\": {\n" +
            "      \"messages\": {\n" +
            "        \"notifyMessage\": {\n" +
            "          \"payload\": {\n" +
            "            \"type\": \"object\",\n" +
            "            \"properties\": {\n" +
            "              \"text\": { \"type\": \"string\" }\n" +
            "            }\n" +
            "          },\n" +
            "          \"examples\": [\n" +
            "            { \"payload\": { \"text\": \"Hello World\" } }\n" +
            "          ]\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        AsyncApiSpec parsed = parser.parse(spec);

        assertThat(parsed.getAsyncApiVersion(), is("3.0.0"));
        assertThat(parsed.getChannels(), hasSize(1));
        AsyncApiChannel channel = parsed.getChannels().get(0);
        assertThat(channel.getName(), is("notifications"));
        assertThat(channel.getPayloadExamples(), hasSize(1));
        assertThat(channel.getPayloadExamples().get(0).get("text").asText(), is("Hello World"));
    }

    @Test
    public void shouldParseAsyncApi3xWithRefToComponentsMessages() throws IOException {
        String spec = "{\n" +
            "  \"asyncapi\": \"3.0.0\",\n" +
            "  \"info\": { \"title\": \"Ref API\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"alerts\": {\n" +
            "      \"messages\": {\n" +
            "        \"alertMsg\": { \"$ref\": \"#/components/messages/AlertMessage\" }\n" +
            "      }\n" +
            "    }\n" +
            "  },\n" +
            "  \"components\": {\n" +
            "    \"messages\": {\n" +
            "      \"AlertMessage\": {\n" +
            "        \"payload\": {\n" +
            "          \"type\": \"object\",\n" +
            "          \"properties\": {\n" +
            "            \"severity\": { \"type\": \"string\" }\n" +
            "          }\n" +
            "        },\n" +
            "        \"examples\": [\n" +
            "          { \"payload\": { \"severity\": \"high\" } }\n" +
            "        ]\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        AsyncApiSpec parsed = parser.parse(spec);

        assertThat(parsed.getChannels(), hasSize(1));
        AsyncApiChannel channel = parsed.getChannels().get(0);
        assertThat(channel.getName(), is("alerts"));
        assertThat(channel.getPayloadExamples(), hasSize(1));
        assertThat(channel.getPayloadExamples().get(0).get("severity").asText(), is("high"));
    }

    // ---- Binding parsing (v2) ----

    @Test
    public void shouldParseV2MqttBindingsQosAndRetain() throws IOException {
        String spec = "{\n" +
            "  \"asyncapi\": \"2.6.0\",\n" +
            "  \"info\": { \"title\": \"MQTT\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"sensors/temp\": {\n" +
            "      \"publish\": {\n" +
            "        \"bindings\": {\n" +
            "          \"mqtt\": { \"qos\": 2, \"retain\": true }\n" +
            "        },\n" +
            "        \"message\": {\n" +
            "          \"payload\": { \"type\": \"object\" }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        AsyncApiSpec parsed = parser.parse(spec);
        assertThat(parsed.getChannels(), hasSize(1));
        AsyncApiChannel channel = parsed.getChannels().get(0);
        assertThat(channel.getMqttQos(), is(2));
        assertThat(channel.getMqttRetain(), is(true));
        assertThat(channel.getKafkaKey(), is(nullValue()));
    }

    @Test
    public void shouldParseV2KafkaKeyLiteral() throws IOException {
        String spec = "{\n" +
            "  \"asyncapi\": \"2.6.0\",\n" +
            "  \"info\": { \"title\": \"Kafka\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"orders\": {\n" +
            "      \"publish\": {\n" +
            "        \"message\": {\n" +
            "          \"bindings\": {\n" +
            "            \"kafka\": { \"key\": \"order-key-123\" }\n" +
            "          },\n" +
            "          \"payload\": { \"type\": \"object\" }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        AsyncApiSpec parsed = parser.parse(spec);
        AsyncApiChannel channel = parsed.getChannels().get(0);
        assertThat(channel.getKafkaKey(), is("order-key-123"));
        assertThat(channel.getMqttQos(), is(nullValue()));
        assertThat(channel.getMqttRetain(), is(nullValue()));
    }

    @Test
    public void shouldParseV2KafkaKeyFromSchemaConst() throws IOException {
        String spec = "{\n" +
            "  \"asyncapi\": \"2.6.0\",\n" +
            "  \"info\": { \"title\": \"Kafka\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"events\": {\n" +
            "      \"publish\": {\n" +
            "        \"message\": {\n" +
            "          \"bindings\": {\n" +
            "            \"kafka\": {\n" +
            "              \"key\": { \"type\": \"string\", \"const\": \"fixed-key\" }\n" +
            "            }\n" +
            "          },\n" +
            "          \"payload\": { \"type\": \"object\" }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        AsyncApiSpec parsed = parser.parse(spec);
        assertThat(parsed.getChannels().get(0).getKafkaKey(), is("fixed-key"));
    }

    @Test
    public void shouldParseV2KafkaKeyFromSchemaExample() throws IOException {
        String spec = "{\n" +
            "  \"asyncapi\": \"2.6.0\",\n" +
            "  \"info\": { \"title\": \"Kafka\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"events\": {\n" +
            "      \"publish\": {\n" +
            "        \"message\": {\n" +
            "          \"bindings\": {\n" +
            "            \"kafka\": {\n" +
            "              \"key\": { \"type\": \"string\", \"example\": \"ex-key\" }\n" +
            "            }\n" +
            "          },\n" +
            "          \"payload\": { \"type\": \"object\" }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        AsyncApiSpec parsed = parser.parse(spec);
        assertThat(parsed.getChannels().get(0).getKafkaKey(), is("ex-key"));
    }

    @Test
    public void shouldParseV2KafkaKeyFromSchemaExamplesArray() throws IOException {
        String spec = "{\n" +
            "  \"asyncapi\": \"2.6.0\",\n" +
            "  \"info\": { \"title\": \"Kafka\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"events\": {\n" +
            "      \"publish\": {\n" +
            "        \"message\": {\n" +
            "          \"bindings\": {\n" +
            "            \"kafka\": {\n" +
            "              \"key\": { \"type\": \"string\", \"examples\": [\"first-key\", \"second-key\"] }\n" +
            "            }\n" +
            "          },\n" +
            "          \"payload\": { \"type\": \"object\" }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        AsyncApiSpec parsed = parser.parse(spec);
        assertThat(parsed.getChannels().get(0).getKafkaKey(), is("first-key"));
    }

    @Test
    public void shouldReturnNullKafkaKeyForBareSchema() throws IOException {
        String spec = "{\n" +
            "  \"asyncapi\": \"2.6.0\",\n" +
            "  \"info\": { \"title\": \"Kafka\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"events\": {\n" +
            "      \"publish\": {\n" +
            "        \"message\": {\n" +
            "          \"bindings\": {\n" +
            "            \"kafka\": {\n" +
            "              \"key\": { \"type\": \"string\" }\n" +
            "            }\n" +
            "          },\n" +
            "          \"payload\": { \"type\": \"object\" }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        AsyncApiSpec parsed = parser.parse(spec);
        assertThat(parsed.getChannels().get(0).getKafkaKey(), is(nullValue()));
    }

    @Test
    public void shouldReturnNullBindingsWhenAbsent() throws IOException {
        String spec = "{\n" +
            "  \"asyncapi\": \"2.6.0\",\n" +
            "  \"info\": { \"title\": \"No bindings\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"topic\": {\n" +
            "      \"publish\": {\n" +
            "        \"message\": {\n" +
            "          \"payload\": { \"type\": \"object\" }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        AsyncApiSpec parsed = parser.parse(spec);
        AsyncApiChannel channel = parsed.getChannels().get(0);
        assertThat(channel.getMqttQos(), is(nullValue()));
        assertThat(channel.getMqttRetain(), is(nullValue()));
        assertThat(channel.getKafkaKey(), is(nullValue()));
    }

    @Test
    public void shouldIgnoreMalformedMqttBinding() throws IOException {
        // qos is a string instead of int, retain is a number instead of boolean
        String spec = "{\n" +
            "  \"asyncapi\": \"2.6.0\",\n" +
            "  \"info\": { \"title\": \"Malformed\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"topic\": {\n" +
            "      \"publish\": {\n" +
            "        \"bindings\": {\n" +
            "          \"mqtt\": { \"qos\": \"bad\", \"retain\": 42 }\n" +
            "        },\n" +
            "        \"message\": {\n" +
            "          \"payload\": { \"type\": \"object\" }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        AsyncApiSpec parsed = parser.parse(spec);
        AsyncApiChannel channel = parsed.getChannels().get(0);
        // Malformed values should be ignored (null), no exception
        assertThat(channel.getMqttQos(), is(nullValue()));
        assertThat(channel.getMqttRetain(), is(nullValue()));
    }

    @Test
    public void shouldIgnoreOutOfRangeMqttQos() throws IOException {
        String spec = "{\n" +
            "  \"asyncapi\": \"2.6.0\",\n" +
            "  \"info\": { \"title\": \"OOR\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"topic\": {\n" +
            "      \"publish\": {\n" +
            "        \"bindings\": {\n" +
            "          \"mqtt\": { \"qos\": 5 }\n" +
            "        },\n" +
            "        \"message\": {\n" +
            "          \"payload\": { \"type\": \"object\" }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        AsyncApiSpec parsed = parser.parse(spec);
        assertThat(parsed.getChannels().get(0).getMqttQos(), is(nullValue()));
    }

    // ---- Binding parsing (v3) ----

    @Test
    public void shouldParseV3KafkaKeyFromMessage() throws IOException {
        String spec = "{\n" +
            "  \"asyncapi\": \"3.0.0\",\n" +
            "  \"info\": { \"title\": \"V3 Kafka\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"orders\": {\n" +
            "      \"messages\": {\n" +
            "        \"orderMsg\": {\n" +
            "          \"bindings\": {\n" +
            "            \"kafka\": { \"key\": \"v3-key\" }\n" +
            "          },\n" +
            "          \"payload\": { \"type\": \"object\" }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        AsyncApiSpec parsed = parser.parse(spec);
        assertThat(parsed.getChannels().get(0).getKafkaKey(), is("v3-key"));
    }

    @Test
    public void shouldParseV3ChannelLevelMqttBindings() throws IOException {
        // v3 best-effort: channel-level bindings.mqtt
        String spec = "{\n" +
            "  \"asyncapi\": \"3.0.0\",\n" +
            "  \"info\": { \"title\": \"V3 MQTT\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"sensors/temp\": {\n" +
            "      \"bindings\": {\n" +
            "        \"mqtt\": { \"qos\": 1, \"retain\": false }\n" +
            "      },\n" +
            "      \"messages\": {\n" +
            "        \"tempMsg\": {\n" +
            "          \"payload\": { \"type\": \"object\" }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        AsyncApiSpec parsed = parser.parse(spec);
        AsyncApiChannel channel = parsed.getChannels().get(0);
        assertThat(channel.getMqttQos(), is(1));
        assertThat(channel.getMqttRetain(), is(false));
    }

    @Test
    public void shouldReturnNullV3MqttBindingsWhenOnlyOnOperations() throws IOException {
        // v3 operation-level bindings are NOT navigated (documented limitation)
        String spec = "{\n" +
            "  \"asyncapi\": \"3.0.0\",\n" +
            "  \"info\": { \"title\": \"V3 ops\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"sensors/temp\": {\n" +
            "      \"messages\": {\n" +
            "        \"tempMsg\": {\n" +
            "          \"payload\": { \"type\": \"object\" }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  },\n" +
            "  \"operations\": {\n" +
            "    \"publishTemp\": {\n" +
            "      \"channel\": { \"$ref\": \"#/channels/sensors~1temp\" },\n" +
            "      \"bindings\": {\n" +
            "        \"mqtt\": { \"qos\": 2, \"retain\": true }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        AsyncApiSpec parsed = parser.parse(spec);
        AsyncApiChannel channel = parsed.getChannels().get(0);
        // v3 operation-binding navigation is not implemented — expect null
        assertThat(channel.getMqttQos(), is(nullValue()));
        assertThat(channel.getMqttRetain(), is(nullValue()));
    }

    // ---- AsyncApiChannel.toPublishOptions ----

    @Test
    public void channelToPublishOptionsShouldCarryBindings() throws IOException {
        String spec = "{\n" +
            "  \"asyncapi\": \"2.6.0\",\n" +
            "  \"info\": { \"title\": \"Both\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"topic\": {\n" +
            "      \"publish\": {\n" +
            "        \"bindings\": { \"mqtt\": { \"qos\": 0, \"retain\": true } },\n" +
            "        \"message\": {\n" +
            "          \"bindings\": { \"kafka\": { \"key\": \"k1\" } },\n" +
            "          \"payload\": { \"type\": \"object\" }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        AsyncApiSpec parsed = parser.parse(spec);
        AsyncApiChannel channel = parsed.getChannels().get(0);

        org.mockserver.async.publish.PublishOptions opts = channel.toPublishOptions();
        assertThat(opts.isEmpty(), is(false));
        assertThat(opts.getKey(), is("k1"));
        assertThat(opts.getQos(), is(0));
        assertThat(opts.getRetain(), is(true));
    }

    @Test
    public void channelToPublishOptionsShouldReturnNoneWhenNoBindings() throws IOException {
        String spec = "{\n" +
            "  \"asyncapi\": \"2.6.0\",\n" +
            "  \"info\": { \"title\": \"None\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"topic\": {\n" +
            "      \"publish\": {\n" +
            "        \"message\": { \"payload\": { \"type\": \"object\" } }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        AsyncApiSpec parsed = parser.parse(spec);
        AsyncApiChannel channel = parsed.getChannels().get(0);

        org.mockserver.async.publish.PublishOptions opts = channel.toPublishOptions();
        assertThat(opts.isEmpty(), is(true));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectNullDocument() throws IOException {
        parser.parse(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectBlankDocument() throws IOException {
        parser.parse("   ");
    }
}
