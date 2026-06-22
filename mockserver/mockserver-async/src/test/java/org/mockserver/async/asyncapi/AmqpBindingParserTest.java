package org.mockserver.async.asyncapi;

import org.junit.Test;

import java.io.IOException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Unit tests for AsyncAPI AMQP channel-binding parsing in {@link AsyncApiParser}.
 * These require no broker.
 */
public class AmqpBindingParserTest {

    private final AsyncApiParser parser = new AsyncApiParser();

    @Test
    public void shouldParseRoutingKeyExchangeBindingV2() throws IOException {
        String spec = "{"
            + "\"asyncapi\":\"2.6.0\","
            + "\"info\":{\"title\":\"t\"},"
            + "\"channels\":{"
            + "  \"user.signedup\":{"
            + "    \"bindings\":{\"amqp\":{"
            + "      \"is\":\"routingKey\","
            + "      \"exchange\":{\"name\":\"events\",\"type\":\"topic\",\"durable\":true}"
            + "    }},"
            + "    \"publish\":{\"message\":{\"payload\":{\"type\":\"object\"}}}"
            + "  }"
            + "}}";

        AsyncApiSpec parsed = parser.parse(spec);
        AsyncApiChannel channel = parsed.getChannels().get(0);
        AmqpBinding binding = channel.getAmqpBinding();

        assertThat(binding, is(notNullValue()));
        assertThat(binding.getChannelType(), is(AmqpBinding.ChannelType.ROUTING_KEY));
        assertThat(binding.getExchangeName(), is("events"));
        assertThat(binding.getExchangeType(), is("topic"));
        assertThat(binding.isExchangeDurable(), is(true));
    }

    @Test
    public void shouldParseQueueBindingV3() throws IOException {
        String spec = "{"
            + "\"asyncapi\":\"3.0.0\","
            + "\"info\":{\"title\":\"t\"},"
            + "\"channels\":{"
            + "  \"orders\":{"
            + "    \"bindings\":{\"amqp\":{"
            + "      \"is\":\"queue\","
            + "      \"queue\":{\"name\":\"orders-queue\",\"durable\":false}"
            + "    }},"
            + "    \"messages\":{\"orderPlaced\":{\"payload\":{\"type\":\"object\"}}}"
            + "  }"
            + "}}";

        AsyncApiSpec parsed = parser.parse(spec);
        AsyncApiChannel channel = parsed.getChannels().get(0);
        AmqpBinding binding = channel.getAmqpBinding();

        assertThat(binding, is(notNullValue()));
        assertThat(binding.getChannelType(), is(AmqpBinding.ChannelType.QUEUE));
        assertThat(binding.getQueueName(), is("orders-queue"));
        assertThat(binding.isQueueDurable(), is(false));
    }

    @Test
    public void shouldDefaultToRoutingKeyWhenIsAbsent() throws IOException {
        String spec = "{"
            + "\"asyncapi\":\"2.6.0\",\"info\":{\"title\":\"t\"},"
            + "\"channels\":{\"c\":{"
            + "  \"bindings\":{\"amqp\":{\"exchange\":{\"name\":\"ex\"}}},"
            + "  \"publish\":{\"message\":{\"payload\":{\"type\":\"object\"}}}"
            + "}}}";

        AmqpBinding binding = parser.parse(spec).getChannels().get(0).getAmqpBinding();
        assertThat(binding.getChannelType(), is(AmqpBinding.ChannelType.ROUTING_KEY));
        assertThat(binding.isExchangeDurable(), is(true)); // default durable
    }

    @Test
    public void shouldReturnNullBindingWhenNoAmqpBindingPresent() throws IOException {
        String spec = "{"
            + "\"asyncapi\":\"2.6.0\",\"info\":{\"title\":\"t\"},"
            + "\"channels\":{\"c\":{\"publish\":{\"message\":{\"payload\":{\"type\":\"object\"}}}}}}";

        AsyncApiChannel channel = parser.parse(spec).getChannels().get(0);
        assertThat(channel.getAmqpBinding(), is(nullValue()));
    }

    @Test
    public void shouldParseExplicitRoutingKeyExtension() throws IOException {
        String spec = "{"
            + "\"asyncapi\":\"2.6.0\",\"info\":{\"title\":\"t\"},"
            + "\"channels\":{\"c\":{"
            + "  \"bindings\":{\"amqp\":{\"exchange\":{\"name\":\"ex\"},\"routingKey\":\"my.key\"}},"
            + "  \"publish\":{\"message\":{\"payload\":{\"type\":\"object\"}}}"
            + "}}}";

        AmqpBinding binding = parser.parse(spec).getChannels().get(0).getAmqpBinding();
        assertThat(binding.getRoutingKey(), is("my.key"));
    }

    @Test
    public void shouldNotAffectKafkaOrMqttChannels() throws IOException {
        // A channel with MQTT bindings but no AMQP binding should leave amqpBinding null.
        String spec = "{"
            + "\"asyncapi\":\"2.6.0\",\"info\":{\"title\":\"t\"},"
            + "\"channels\":{\"c\":{"
            + "  \"publish\":{\"bindings\":{\"mqtt\":{\"qos\":2,\"retain\":true}},"
            + "    \"message\":{\"payload\":{\"type\":\"object\"}}}"
            + "}}}";

        AsyncApiChannel channel = parser.parse(spec).getChannels().get(0);
        assertThat(channel.getAmqpBinding(), is(nullValue()));
        assertThat(channel.getMqttQos(), is(2));
        assertThat(channel.getMqttRetain(), is(true));
    }
}
