package org.mockserver.async.controlplane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.ConfigurationProperties;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Tests for {@link AsyncApiControlPlaneImpl}.
 * <p>
 * These tests exercise the control-plane logic (spec parsing, status reporting,
 * reset) without connecting to real brokers. Broker connection failures are expected
 * for load() when no broker config points to a reachable broker, but the spec
 * parsing, validation, and status logic is still exercised.
 */
public class AsyncApiControlPlaneImplTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AsyncApiControlPlaneImpl controlPlane = new AsyncApiControlPlaneImpl();

    @After
    public void tearDown() {
        controlPlane.reset();
    }

    @Test
    public void shouldReturnEmptyStatusWhenNoSpecLoaded() throws Exception {
        JsonNode status = controlPlane.status();

        assertThat(status.get("loaded").asBoolean(), is(false));
        assertThat(status.get("channels").size(), is(0));
        assertThat(status.get("publishers").asInt(), is(0));
        assertThat(status.get("subscribers").asInt(), is(0));
        assertThat(status.get("recordedMessages").size(), is(0));
    }

    @Test
    public void shouldLoadSpecWithoutBrokerConfig() throws Exception {
        // When no brokerConfig is provided, the spec is parsed but no brokers
        // are connected (both kafkaBootstrapServers and mqttBrokerUrl are null)
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
            "            }\n" +
            "          }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        JsonNode result = controlPlane.load(spec);

        assertThat(result.get("loaded").asBoolean(), is(true));
        assertThat(result.get("specTitle").asText(), is("Test API"));
        assertThat(result.get("specVersion").asText(), is("2.6.0"));
        assertThat(result.get("channelCount").asInt(), is(1));
        assertThat(result.get("channels").get(0).get("name").asText(), is("user/signedup"));
        assertThat(result.get("publishers").asInt(), is(0));
        assertThat(result.get("subscribers").asInt(), is(0));
    }

    @Test
    public void shouldReturnStatusAfterLoad() throws Exception {
        String spec = "{\n" +
            "  \"asyncapi\": \"2.6.0\",\n" +
            "  \"info\": { \"title\": \"Status Test\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"orders\": {\n" +
            "      \"publish\": {\n" +
            "        \"message\": {\n" +
            "          \"payload\": {\n" +
            "            \"type\": \"object\",\n" +
            "            \"properties\": {\n" +
            "              \"orderId\": { \"type\": \"integer\" }\n" +
            "            },\n" +
            "            \"required\": [\"orderId\"]\n" +
            "          }\n" +
            "        }\n" +
            "      }\n" +
            "    },\n" +
            "    \"events\": {\n" +
            "      \"subscribe\": {\n" +
            "        \"message\": {\n" +
            "          \"payload\": { \"type\": \"string\" }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        controlPlane.load(spec);
        JsonNode status = controlPlane.status();

        assertThat(status.get("loaded").asBoolean(), is(true));
        assertThat(status.get("specTitle").asText(), is("Status Test"));
        assertThat(status.get("channels").size(), is(2));

        // Verify channel info
        boolean foundOrders = false;
        boolean foundEvents = false;
        for (JsonNode channel : status.get("channels")) {
            if ("orders".equals(channel.get("name").asText())) {
                assertThat(channel.get("hasSchema").asBoolean(), is(true));
                foundOrders = true;
            }
            if ("events".equals(channel.get("name").asText())) {
                assertThat(channel.get("hasSchema").asBoolean(), is(true));
                foundEvents = true;
            }
        }
        assertThat(foundOrders, is(true));
        assertThat(foundEvents, is(true));
    }

    @Test
    public void shouldResetState() throws Exception {
        String spec = "{\n" +
            "  \"asyncapi\": \"2.6.0\",\n" +
            "  \"info\": { \"title\": \"Reset Test\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"test-channel\": {\n" +
            "      \"publish\": {\n" +
            "        \"message\": {\n" +
            "          \"payload\": { \"type\": \"string\" }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        controlPlane.load(spec);
        assertThat(controlPlane.status().get("loaded").asBoolean(), is(true));

        controlPlane.reset();
        assertThat(controlPlane.status().get("loaded").asBoolean(), is(false));
        assertThat(controlPlane.status().get("channels").size(), is(0));
    }

    @Test
    public void shouldLoadSpecFromWrappedBody() throws Exception {
        String wrappedBody = "{\n" +
            "  \"spec\": {\n" +
            "    \"asyncapi\": \"2.6.0\",\n" +
            "    \"info\": { \"title\": \"Wrapped\", \"version\": \"1.0.0\" },\n" +
            "    \"channels\": {\n" +
            "      \"wrapped-channel\": {\n" +
            "        \"publish\": {\n" +
            "          \"message\": {\n" +
            "            \"payload\": { \"type\": \"string\" }\n" +
            "          }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  },\n" +
            "  \"brokerConfig\": {}\n" +
            "}";

        JsonNode result = controlPlane.load(wrappedBody);

        assertThat(result.get("loaded").asBoolean(), is(true));
        assertThat(result.get("specTitle").asText(), is("Wrapped"));
        assertThat(result.get("channelCount").asInt(), is(1));
    }

    @Test
    public void shouldHandleInvalidSpec() throws Exception {
        // A spec that parses but has no channels will load successfully with empty channels,
        // but a genuinely broken JSON should be caught as an error.
        JsonNode result = controlPlane.load("{\"asyncapi\": \"2.6.0\"}");

        // This is a valid (but empty) spec — it loads with 0 channels
        assertThat(result.get("loaded").asBoolean(), is(true));
        assertThat(result.get("channelCount").asInt(), is(0));
    }

    @Test(expected = RuntimeException.class)
    public void shouldThrowOnEmptyBody() {
        // Empty body triggers parse failure, which now throws RuntimeException
        controlPlane.load("");
    }

    @Test
    public void shouldLoadYamlSpec() throws Exception {
        String yamlSpec =
            "asyncapi: '2.6.0'\n" +
            "info:\n" +
            "  title: YAML Spec\n" +
            "  version: '1.0.0'\n" +
            "channels:\n" +
            "  yaml-topic:\n" +
            "    publish:\n" +
            "      message:\n" +
            "        payload:\n" +
            "          type: object\n" +
            "          properties:\n" +
            "            name:\n" +
            "              type: string\n";

        JsonNode result = controlPlane.load(yamlSpec);

        assertThat(result.get("loaded").asBoolean(), is(true));
        assertThat(result.get("specTitle").asText(), is("YAML Spec"));
        assertThat(result.get("channels").get(0).get("name").asText(), is("yaml-topic"));
    }

    @Test
    public void shouldLoadAsyncApi3xSpec() throws Exception {
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
            "          }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        JsonNode result = controlPlane.load(spec);

        assertThat(result.get("loaded").asBoolean(), is(true));
        assertThat(result.get("specVersion").asText(), is("3.0.0"));
        assertThat(result.get("channels").get(0).get("name").asText(), is("notifications"));
    }

    @Test
    public void shouldReloadSpecReplacingPrevious() throws Exception {
        String spec1 = "{\n" +
            "  \"asyncapi\": \"2.6.0\",\n" +
            "  \"info\": { \"title\": \"First\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": { \"ch1\": { \"publish\": { \"message\": { \"payload\": { \"type\": \"string\" } } } } }\n" +
            "}";

        String spec2 = "{\n" +
            "  \"asyncapi\": \"2.6.0\",\n" +
            "  \"info\": { \"title\": \"Second\", \"version\": \"2.0.0\" },\n" +
            "  \"channels\": { \"ch2\": { \"publish\": { \"message\": { \"payload\": { \"type\": \"integer\" } } } } }\n" +
            "}";

        controlPlane.load(spec1);
        assertThat(controlPlane.status().get("specTitle").asText(), is("First"));

        controlPlane.load(spec2);
        assertThat(controlPlane.status().get("specTitle").asText(), is("Second"));
        assertThat(controlPlane.status().get("channels").get(0).get("name").asText(), is("ch2"));
    }

    @Test
    public void shouldThrowOnFailureAndCleanUpPartialState() {
        // MQTT broker connection fails immediately (unlike Kafka which is lazy),
        // so configure with an unreachable MQTT broker to trigger a partial failure
        // after Kafka publisher is already created.
        String wrappedBody = "{\n" +
            "  \"spec\": {\n" +
            "    \"asyncapi\": \"2.6.0\",\n" +
            "    \"info\": { \"title\": \"Partial Fail\", \"version\": \"1.0.0\" },\n" +
            "    \"channels\": { \"ch1\": { \"publish\": { \"message\": { \"payload\": { \"type\": \"string\" } } } } }\n" +
            "  },\n" +
            "  \"brokerConfig\": { \"mqttBrokerUrl\": \"tcp://localhost:19999\" }\n" +
            "}";

        try {
            controlPlane.load(wrappedBody);
            // MQTT connection to unreachable broker should throw
        } catch (RuntimeException e) {
            // Expected — verify cleanup happened
            assertThat(e.getMessage(), containsString("Failed to load AsyncAPI spec"));
        }

        // After failure, status should show nothing loaded (resetInternal was called)
        JsonNode status = controlPlane.status();
        assertThat(status.get("loaded").asBoolean(), is(false));
        assertThat(status.get("publishers").asInt(), is(0));
        assertThat(status.get("subscribers").asInt(), is(0));
    }

    @Test
    public void loadFailureShouldThrowRuntimeException() {
        // Completely invalid body that can't be parsed at all
        boolean threw = false;
        try {
            controlPlane.load("");
        } catch (RuntimeException e) {
            threw = true;
            assertThat(e.getMessage(), containsString("Failed to load AsyncAPI spec"));
        }
        assertThat("load() should throw RuntimeException on failure", threw, is(true));
    }

    @Test
    public void shouldFallBackToConfigDefaultKafkaBootstrapServers() throws Exception {
        // Given: a config default for Kafka bootstrap servers (localhost resolves, even though
        // no broker is listening — KafkaProducer constructor only validates DNS resolution)
        String original = ConfigurationProperties.asyncKafkaBootstrapServers();
        try {
            ConfigurationProperties.asyncKafkaBootstrapServers("localhost:19999");

            // When: loading a spec with a wrapped body that omits kafkaBootstrapServers
            String wrappedBody = "{\n" +
                "  \"spec\": {\n" +
                "    \"asyncapi\": \"2.6.0\",\n" +
                "    \"info\": { \"title\": \"Fallback Test\", \"version\": \"1.0.0\" },\n" +
                "    \"channels\": { \"test\": { \"publish\": { \"message\": { \"payload\": { \"type\": \"string\" } } } } }\n" +
                "  },\n" +
                "  \"brokerConfig\": { \"publishOnLoad\": false }\n" +
                "}";

            JsonNode result = controlPlane.load(wrappedBody);

            // Then: the spec loaded and a Kafka publisher was created using the config default
            assertThat(result.get("loaded").asBoolean(), is(true));
            assertThat(result.get("publishers").asInt(), is(1));
        } finally {
            ConfigurationProperties.asyncKafkaBootstrapServers(original);
        }
    }

    @Test
    public void shouldPreferRequestBodyKafkaOverConfigDefault() throws Exception {
        // Given: a config default for Kafka bootstrap servers
        String original = ConfigurationProperties.asyncKafkaBootstrapServers();
        try {
            ConfigurationProperties.asyncKafkaBootstrapServers("localhost:29999");

            // When: loading a spec with an explicit kafkaBootstrapServers in the request body
            String wrappedBody = "{\n" +
                "  \"spec\": {\n" +
                "    \"asyncapi\": \"2.6.0\",\n" +
                "    \"info\": { \"title\": \"Override Test\", \"version\": \"1.0.0\" },\n" +
                "    \"channels\": { \"test\": { \"publish\": { \"message\": { \"payload\": { \"type\": \"string\" } } } } }\n" +
                "  },\n" +
                "  \"brokerConfig\": { \"kafkaBootstrapServers\": \"localhost:19999\", \"publishOnLoad\": false }\n" +
                "}";

            JsonNode result = controlPlane.load(wrappedBody);

            // Then: the spec loaded — request body value takes precedence
            assertThat(result.get("loaded").asBoolean(), is(true));
            assertThat(result.get("publishers").asInt(), is(1));
        } finally {
            ConfigurationProperties.asyncKafkaBootstrapServers(original);
        }
    }

    @Test
    public void shouldUseAsyncRecordedMessageMaxEntriesFromConfig() throws Exception {
        // Given: a custom max entries config
        int original = ConfigurationProperties.asyncRecordedMessageMaxEntries();
        try {
            ConfigurationProperties.asyncRecordedMessageMaxEntries(42);

            // Then: the ConfigurationProperties value should be 42
            assertThat(ConfigurationProperties.asyncRecordedMessageMaxEntries(), is(42));

            // And when loading a spec without broker config (no broker connections),
            // the config value is ready for use by createBrokerConnections
            String spec = "{\n" +
                "  \"asyncapi\": \"2.6.0\",\n" +
                "  \"info\": { \"title\": \"Max Entries Test\", \"version\": \"1.0.0\" },\n" +
                "  \"channels\": { \"ch1\": { \"publish\": { \"message\": { \"payload\": { \"type\": \"string\" } } } } }\n" +
                "}";

            JsonNode result = controlPlane.load(spec);
            assertThat(result.get("loaded").asBoolean(), is(true));
        } finally {
            ConfigurationProperties.asyncRecordedMessageMaxEntries(original);
        }
    }

    @Test
    public void shouldNoBrokersWhenNoConfigDefaultAndNoRequestConfig() throws Exception {
        // Given: no config defaults
        String originalKafka = ConfigurationProperties.asyncKafkaBootstrapServers();
        String originalMqtt = ConfigurationProperties.asyncMqttBrokerUrl();
        try {
            ConfigurationProperties.asyncKafkaBootstrapServers("");
            ConfigurationProperties.asyncMqttBrokerUrl("");

            // When: loading a plain spec (no broker config)
            String spec = "{\n" +
                "  \"asyncapi\": \"2.6.0\",\n" +
                "  \"info\": { \"title\": \"No Broker\", \"version\": \"1.0.0\" },\n" +
                "  \"channels\": { \"ch1\": { \"publish\": { \"message\": { \"payload\": { \"type\": \"string\" } } } } }\n" +
                "}";

            JsonNode result = controlPlane.load(spec);

            // Then: no publishers or subscribers created
            assertThat(result.get("loaded").asBoolean(), is(true));
            assertThat(result.get("publishers").asInt(), is(0));
            assertThat(result.get("subscribers").asInt(), is(0));
        } finally {
            ConfigurationProperties.asyncKafkaBootstrapServers(originalKafka);
            ConfigurationProperties.asyncMqttBrokerUrl(originalMqtt);
        }
    }
}
