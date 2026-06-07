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

    // ---- Multi-message channels (v3) ----

    @Test
    public void shouldParseV3ChannelWithMultipleMessages() throws IOException {
        String spec = "{\n" +
            "  \"asyncapi\": \"3.0.0\",\n" +
            "  \"info\": { \"title\": \"Multi-Message V3\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"events\": {\n" +
            "      \"messages\": {\n" +
            "        \"userCreated\": {\n" +
            "          \"payload\": {\n" +
            "            \"type\": \"object\",\n" +
            "            \"properties\": { \"userId\": { \"type\": \"string\" } }\n" +
            "          },\n" +
            "          \"examples\": [{ \"payload\": { \"userId\": \"u1\" } }],\n" +
            "          \"bindings\": { \"kafka\": { \"key\": \"user-key\" } }\n" +
            "        },\n" +
            "        \"orderPlaced\": {\n" +
            "          \"payload\": {\n" +
            "            \"type\": \"object\",\n" +
            "            \"properties\": { \"orderId\": { \"type\": \"integer\" } }\n" +
            "          },\n" +
            "          \"examples\": [{ \"payload\": { \"orderId\": 42 } }],\n" +
            "          \"bindings\": { \"kafka\": { \"key\": \"order-key\" } }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        AsyncApiSpec parsed = parser.parse(spec);
        assertThat(parsed.getChannels(), hasSize(1));
        AsyncApiChannel channel = parsed.getChannels().get(0);
        assertThat(channel.getName(), is("events"));

        // getMessages() should return both messages
        assertThat(channel.getMessages(), hasSize(2));
        assertThat(channel.getMessages().get(0).getName(), is("userCreated"));
        assertThat(channel.getMessages().get(0).getPayloadExamples(), hasSize(1));
        assertThat(channel.getMessages().get(0).getPayloadExamples().get(0).get("userId").asText(), is("u1"));
        assertThat(channel.getMessages().get(0).getKafkaKey(), is("user-key"));
        assertThat(channel.getMessages().get(1).getName(), is("orderPlaced"));
        assertThat(channel.getMessages().get(1).getPayloadExamples(), hasSize(1));
        assertThat(channel.getMessages().get(1).getPayloadExamples().get(0).get("orderId").asInt(), is(42));
        assertThat(channel.getMessages().get(1).getKafkaKey(), is("order-key"));

        // Back-compat: legacy accessors reflect the FIRST message
        assertThat(channel.getPayloadExamples(), hasSize(1));
        assertThat(channel.getPayloadExamples().get(0).get("userId").asText(), is("u1"));
        assertThat(channel.getKafkaKey(), is("user-key"));
        assertThat(channel.getPayloadSchema(), is(notNullValue()));
    }

    @Test
    public void shouldParseV3SingleMessageChannelGetMessagesReturnsOne() throws IOException {
        String spec = "{\n" +
            "  \"asyncapi\": \"3.0.0\",\n" +
            "  \"info\": { \"title\": \"Single V3\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"topic\": {\n" +
            "      \"messages\": {\n" +
            "        \"singleMsg\": {\n" +
            "          \"payload\": { \"type\": \"object\" },\n" +
            "          \"examples\": [{ \"payload\": { \"a\": 1 } }]\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        AsyncApiSpec parsed = parser.parse(spec);
        AsyncApiChannel channel = parsed.getChannels().get(0);

        // Single message — getMessages() synthesizes from channel fields
        assertThat(channel.getMessages(), hasSize(1));
        assertThat(channel.getMessages().get(0).getPayloadExamples(), hasSize(1));
        assertThat(channel.getMessages().get(0).getPayloadExamples().get(0).get("a").asInt(), is(1));

        // Legacy accessors unchanged
        assertThat(channel.getPayloadExamples(), hasSize(1));
    }

    @Test
    public void shouldParseV3MultiMessageWithRef() throws IOException {
        String spec = "{\n" +
            "  \"asyncapi\": \"3.0.0\",\n" +
            "  \"info\": { \"title\": \"Ref Multi\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"alerts\": {\n" +
            "      \"messages\": {\n" +
            "        \"alert1\": { \"$ref\": \"#/components/messages/AlertA\" },\n" +
            "        \"alert2\": {\n" +
            "          \"payload\": { \"type\": \"object\", \"properties\": { \"level\": { \"type\": \"integer\" } } },\n" +
            "          \"examples\": [{ \"payload\": { \"level\": 5 } }]\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  },\n" +
            "  \"components\": {\n" +
            "    \"messages\": {\n" +
            "      \"AlertA\": {\n" +
            "        \"payload\": { \"type\": \"object\", \"properties\": { \"severity\": { \"type\": \"string\" } } },\n" +
            "        \"examples\": [{ \"payload\": { \"severity\": \"high\" } }]\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        AsyncApiSpec parsed = parser.parse(spec);
        AsyncApiChannel channel = parsed.getChannels().get(0);

        assertThat(channel.getMessages(), hasSize(2));
        assertThat(channel.getMessages().get(0).getPayloadExamples().get(0).get("severity").asText(), is("high"));
        assertThat(channel.getMessages().get(1).getPayloadExamples().get(0).get("level").asInt(), is(5));
    }

    // ---- Multi-message channels (v2 oneOf) ----

    @Test
    public void shouldParseV2ChannelWithOneOfMultiMessage() throws IOException {
        String spec = "{\n" +
            "  \"asyncapi\": \"2.6.0\",\n" +
            "  \"info\": { \"title\": \"OneOf V2\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"events\": {\n" +
            "      \"publish\": {\n" +
            "        \"message\": {\n" +
            "          \"oneOf\": [\n" +
            "            {\n" +
            "              \"name\": \"UserEvent\",\n" +
            "              \"payload\": {\n" +
            "                \"type\": \"object\",\n" +
            "                \"properties\": { \"user\": { \"type\": \"string\" } },\n" +
            "                \"example\": { \"user\": \"alice\" }\n" +
            "              },\n" +
            "              \"bindings\": { \"kafka\": { \"key\": \"user-k\" } }\n" +
            "            },\n" +
            "            {\n" +
            "              \"name\": \"OrderEvent\",\n" +
            "              \"payload\": {\n" +
            "                \"type\": \"object\",\n" +
            "                \"properties\": { \"orderId\": { \"type\": \"integer\" } },\n" +
            "                \"example\": { \"orderId\": 99 }\n" +
            "              },\n" +
            "              \"bindings\": { \"kafka\": { \"key\": \"order-k\" } }\n" +
            "            }\n" +
            "          ]\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        AsyncApiSpec parsed = parser.parse(spec);
        assertThat(parsed.getChannels(), hasSize(1));
        AsyncApiChannel channel = parsed.getChannels().get(0);
        assertThat(channel.getName(), is("events"));

        // getMessages() should return both oneOf variants
        assertThat(channel.getMessages(), hasSize(2));
        assertThat(channel.getMessages().get(0).getName(), is("UserEvent"));
        assertThat(channel.getMessages().get(0).getPayloadExamples(), hasSize(1));
        assertThat(channel.getMessages().get(0).getPayloadExamples().get(0).get("user").asText(), is("alice"));
        assertThat(channel.getMessages().get(0).getKafkaKey(), is("user-k"));
        assertThat(channel.getMessages().get(1).getName(), is("OrderEvent"));
        assertThat(channel.getMessages().get(1).getPayloadExamples(), hasSize(1));
        assertThat(channel.getMessages().get(1).getPayloadExamples().get(0).get("orderId").asInt(), is(99));
        assertThat(channel.getMessages().get(1).getKafkaKey(), is("order-k"));

        // Back-compat: legacy accessors reflect the FIRST oneOf entry
        assertThat(channel.getPayloadExamples(), hasSize(1));
        assertThat(channel.getPayloadExamples().get(0).get("user").asText(), is("alice"));
        assertThat(channel.getKafkaKey(), is("user-k"));
    }

    @Test
    public void shouldParseV2SingleMessageNoOneOfGetMessagesReturnsOne() throws IOException {
        // No oneOf — standard single-message; getMessages() should still return 1
        String spec = "{\n" +
            "  \"asyncapi\": \"2.6.0\",\n" +
            "  \"info\": { \"title\": \"Single V2\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"topic\": {\n" +
            "      \"publish\": {\n" +
            "        \"message\": {\n" +
            "          \"payload\": { \"type\": \"object\", \"example\": { \"x\": 1 } }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        AsyncApiSpec parsed = parser.parse(spec);
        AsyncApiChannel channel = parsed.getChannels().get(0);

        assertThat(channel.getMessages(), hasSize(1));
        assertThat(channel.getPayloadExamples(), hasSize(1));
        assertThat(channel.getPayloadExamples().get(0).get("x").asInt(), is(1));
    }

    @Test
    public void shouldParseV2OneOfWithMqttBindingsAtOperationLevel() throws IOException {
        String spec = "{\n" +
            "  \"asyncapi\": \"2.6.0\",\n" +
            "  \"info\": { \"title\": \"OneOf MQTT\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"sensors\": {\n" +
            "      \"publish\": {\n" +
            "        \"bindings\": { \"mqtt\": { \"qos\": 2, \"retain\": true } },\n" +
            "        \"message\": {\n" +
            "          \"oneOf\": [\n" +
            "            {\n" +
            "              \"payload\": { \"type\": \"object\", \"example\": { \"temp\": 22 } }\n" +
            "            },\n" +
            "            {\n" +
            "              \"payload\": { \"type\": \"object\", \"example\": { \"humidity\": 60 } }\n" +
            "            }\n" +
            "          ]\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        AsyncApiSpec parsed = parser.parse(spec);
        AsyncApiChannel channel = parsed.getChannels().get(0);

        // MQTT bindings are channel-level
        assertThat(channel.getMqttQos(), is(2));
        assertThat(channel.getMqttRetain(), is(true));

        // Two messages
        assertThat(channel.getMessages(), hasSize(2));
    }

    @Test
    public void shouldTolerateV2OneOfWithSingleEntry() throws IOException {
        String spec = "{\n" +
            "  \"asyncapi\": \"2.6.0\",\n" +
            "  \"info\": { \"title\": \"OneOf Single\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"topic\": {\n" +
            "      \"publish\": {\n" +
            "        \"message\": {\n" +
            "          \"oneOf\": [\n" +
            "            {\n" +
            "              \"payload\": { \"type\": \"object\", \"example\": { \"v\": 1 } }\n" +
            "            }\n" +
            "          ]\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        AsyncApiSpec parsed = parser.parse(spec);
        AsyncApiChannel channel = parsed.getChannels().get(0);

        // Single entry oneOf — behaves as single message
        assertThat(channel.getMessages(), hasSize(1));
        assertThat(channel.getPayloadExamples(), hasSize(1));
        assertThat(channel.getPayloadExamples().get(0).get("v").asInt(), is(1));
    }

    // ---- Correlation ID parsing ----

    @Test
    public void shouldParseV2CorrelationIdHeaderLocation() throws IOException {
        String spec = "{\n" +
            "  \"asyncapi\": \"2.6.0\",\n" +
            "  \"info\": { \"title\": \"CorrId\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"orders\": {\n" +
            "      \"publish\": {\n" +
            "        \"message\": {\n" +
            "          \"correlationId\": {\n" +
            "            \"location\": \"$message.header#/correlationId\",\n" +
            "            \"description\": \"Default Correlation ID\"\n" +
            "          },\n" +
            "          \"payload\": { \"type\": \"object\" }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        AsyncApiSpec parsed = parser.parse(spec);
        AsyncApiChannel channel = parsed.getChannels().get(0);
        assertThat(channel.getMessages(), hasSize(1));
        assertThat(channel.getMessages().get(0).getCorrelationIdLocation(),
            is("$message.header#/correlationId"));
    }

    @Test
    public void shouldParseV2CorrelationIdPayloadLocation() throws IOException {
        String spec = "{\n" +
            "  \"asyncapi\": \"2.6.0\",\n" +
            "  \"info\": { \"title\": \"CorrId\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"orders\": {\n" +
            "      \"publish\": {\n" +
            "        \"message\": {\n" +
            "          \"correlationId\": {\n" +
            "            \"location\": \"$message.payload#/metadata/id\"\n" +
            "          },\n" +
            "          \"payload\": { \"type\": \"object\" }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        AsyncApiSpec parsed = parser.parse(spec);
        assertThat(parsed.getChannels().get(0).getMessages().get(0).getCorrelationIdLocation(),
            is("$message.payload#/metadata/id"));
    }

    @Test
    public void shouldParseV2CorrelationIdFromRefToComponents() throws IOException {
        String spec = "{\n" +
            "  \"asyncapi\": \"2.6.0\",\n" +
            "  \"info\": { \"title\": \"CorrIdRef\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"orders\": {\n" +
            "      \"publish\": {\n" +
            "        \"message\": {\n" +
            "          \"correlationId\": { \"$ref\": \"#/components/correlationIds/defaultCorrId\" },\n" +
            "          \"payload\": { \"type\": \"object\" }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  },\n" +
            "  \"components\": {\n" +
            "    \"correlationIds\": {\n" +
            "      \"defaultCorrId\": {\n" +
            "        \"location\": \"$message.header#/x-corr-id\",\n" +
            "        \"description\": \"Resolved from components\"\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        AsyncApiSpec parsed = parser.parse(spec);
        assertThat(parsed.getChannels().get(0).getMessages().get(0).getCorrelationIdLocation(),
            is("$message.header#/x-corr-id"));
    }

    @Test
    public void shouldReturnNullCorrelationIdWhenAbsent() throws IOException {
        String spec = "{\n" +
            "  \"asyncapi\": \"2.6.0\",\n" +
            "  \"info\": { \"title\": \"NoCorrId\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"orders\": {\n" +
            "      \"publish\": {\n" +
            "        \"message\": {\n" +
            "          \"payload\": { \"type\": \"object\" }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        AsyncApiSpec parsed = parser.parse(spec);
        assertThat(parsed.getChannels().get(0).getMessages().get(0).getCorrelationIdLocation(),
            is(nullValue()));
    }

    @Test
    public void shouldReturnNullCorrelationIdWhenMalformed() throws IOException {
        // correlationId is a string instead of an object — should not throw
        String spec = "{\n" +
            "  \"asyncapi\": \"2.6.0\",\n" +
            "  \"info\": { \"title\": \"Malformed\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"orders\": {\n" +
            "      \"publish\": {\n" +
            "        \"message\": {\n" +
            "          \"correlationId\": \"not-an-object\",\n" +
            "          \"payload\": { \"type\": \"object\" }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        AsyncApiSpec parsed = parser.parse(spec);
        assertThat(parsed.getChannels().get(0).getMessages().get(0).getCorrelationIdLocation(),
            is(nullValue()));
    }

    @Test
    public void shouldParseV2OneOfCorrelationIdPerVariant() throws IOException {
        String spec = "{\n" +
            "  \"asyncapi\": \"2.6.0\",\n" +
            "  \"info\": { \"title\": \"OneOf CorrId\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"events\": {\n" +
            "      \"publish\": {\n" +
            "        \"message\": {\n" +
            "          \"oneOf\": [\n" +
            "            {\n" +
            "              \"name\": \"MsgA\",\n" +
            "              \"correlationId\": { \"location\": \"$message.header#/corrA\" },\n" +
            "              \"payload\": { \"type\": \"object\", \"example\": { \"a\": 1 } }\n" +
            "            },\n" +
            "            {\n" +
            "              \"name\": \"MsgB\",\n" +
            "              \"correlationId\": { \"location\": \"$message.payload#/corrId\" },\n" +
            "              \"payload\": { \"type\": \"object\", \"example\": { \"b\": 2 } }\n" +
            "            }\n" +
            "          ]\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        AsyncApiSpec parsed = parser.parse(spec);
        AsyncApiChannel channel = parsed.getChannels().get(0);
        assertThat(channel.getMessages(), hasSize(2));
        assertThat(channel.getMessages().get(0).getCorrelationIdLocation(),
            is("$message.header#/corrA"));
        assertThat(channel.getMessages().get(1).getCorrelationIdLocation(),
            is("$message.payload#/corrId"));
    }

    @Test
    public void shouldParseV3CorrelationId() throws IOException {
        String spec = "{\n" +
            "  \"asyncapi\": \"3.0.0\",\n" +
            "  \"info\": { \"title\": \"V3 CorrId\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"orders\": {\n" +
            "      \"messages\": {\n" +
            "        \"orderMsg\": {\n" +
            "          \"correlationId\": {\n" +
            "            \"location\": \"$message.header#/x-correlation-id\"\n" +
            "          },\n" +
            "          \"payload\": { \"type\": \"object\" },\n" +
            "          \"examples\": [{ \"payload\": { \"orderId\": 1 } }]\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        AsyncApiSpec parsed = parser.parse(spec);
        AsyncApiChannel channel = parsed.getChannels().get(0);
        assertThat(channel.getMessages(), hasSize(1));
        assertThat(channel.getMessages().get(0).getCorrelationIdLocation(),
            is("$message.header#/x-correlation-id"));
    }

    @Test
    public void shouldParseV3CorrelationIdWithRefToComponents() throws IOException {
        String spec = "{\n" +
            "  \"asyncapi\": \"3.0.0\",\n" +
            "  \"info\": { \"title\": \"V3 CorrId Ref\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"orders\": {\n" +
            "      \"messages\": {\n" +
            "        \"orderMsg\": {\n" +
            "          \"correlationId\": { \"$ref\": \"#/components/correlationIds/defaultId\" },\n" +
            "          \"payload\": { \"type\": \"object\" }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  },\n" +
            "  \"components\": {\n" +
            "    \"correlationIds\": {\n" +
            "      \"defaultId\": {\n" +
            "        \"location\": \"$message.payload#/correlationId\"\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        AsyncApiSpec parsed = parser.parse(spec);
        assertThat(parsed.getChannels().get(0).getMessages().get(0).getCorrelationIdLocation(),
            is("$message.payload#/correlationId"));
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
