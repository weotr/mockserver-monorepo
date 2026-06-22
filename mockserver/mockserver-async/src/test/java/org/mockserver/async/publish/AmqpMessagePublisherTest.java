package org.mockserver.async.publish;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.async.asyncapi.AmqpBinding;
import org.mockserver.async.asyncapi.AsyncApiChannel;
import org.mockserver.async.asyncapi.AsyncApiSpec;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AmqpMessagePublisher} destination derivation and publishing,
 * using a mocked AMQP {@link Channel} (no broker).
 */
public class AmqpMessagePublisherTest {

    private Connection connection;
    private Channel amqpChannel;

    @Before
    public void setUp() {
        connection = mock(Connection.class);
        amqpChannel = mock(Channel.class);
        when(connection.isOpen()).thenReturn(true);
        when(amqpChannel.isOpen()).thenReturn(true);
    }

    // ---- destination derivation (pure, broker-free) ----

    @Test
    public void shouldDeriveDefaultExchangeWithChannelNameWhenNoBinding() {
        AmqpMessagePublisher.Destination d =
            AmqpMessagePublisher.resolveDestination("orders", null);
        assertThat(d.exchange, is(""));
        assertThat(d.routingKey, is("orders"));
    }

    @Test
    public void shouldDeriveExchangeAndChannelNameRoutingKeyForRoutingKeyBinding() {
        AmqpBinding binding = new AmqpBinding(
            AmqpBinding.ChannelType.ROUTING_KEY, "events", "topic", true, null, true, null);
        AmqpMessagePublisher.Destination d =
            AmqpMessagePublisher.resolveDestination("user.signedup", binding);
        assertThat(d.exchange, is("events"));
        assertThat(d.routingKey, is("user.signedup"));
    }

    @Test
    public void shouldPreferExplicitRoutingKeyOverChannelName() {
        AmqpBinding binding = new AmqpBinding(
            AmqpBinding.ChannelType.ROUTING_KEY, "events", "topic", true, null, true, "explicit.key");
        AmqpMessagePublisher.Destination d =
            AmqpMessagePublisher.resolveDestination("channel-name", binding);
        assertThat(d.exchange, is("events"));
        assertThat(d.routingKey, is("explicit.key"));
    }

    @Test
    public void shouldDeriveDefaultExchangeAndQueueNameForQueueBinding() {
        AmqpBinding binding = new AmqpBinding(
            AmqpBinding.ChannelType.QUEUE, null, null, true, "orders-queue", true, null);
        AmqpMessagePublisher.Destination d =
            AmqpMessagePublisher.resolveDestination("orders", binding);
        assertThat(d.exchange, is(""));
        assertThat(d.routingKey, is("orders-queue"));
    }

    @Test
    public void shouldFallBackToChannelNameForQueueBindingWithoutQueueName() {
        AmqpBinding binding = new AmqpBinding(
            AmqpBinding.ChannelType.QUEUE, null, null, true, null, true, null);
        AmqpMessagePublisher.Destination d =
            AmqpMessagePublisher.resolveDestination("orders", binding);
        assertThat(d.exchange, is(""));
        assertThat(d.routingKey, is("orders"));
    }

    // ---- publishing via mocked channel ----

    @Test
    public void shouldPublishToDefaultExchangeWhenNoBinding() throws Exception {
        AsyncApiSpec spec = specWithChannel("plain", null);
        AmqpMessagePublisher publisher = new AmqpMessagePublisher(connection, amqpChannel, spec);

        publisher.publish("plain", "{\"a\":1}");

        verify(amqpChannel).basicPublish(eq(""), eq("plain"), isNull(),
            eq("{\"a\":1}".getBytes(StandardCharsets.UTF_8)));
        // default exchange must not be declared
        verify(amqpChannel, never()).exchangeDeclare(anyString(), anyString(), anyBoolean());
    }

    @Test
    public void shouldDeclareAndPublishToExchangeForRoutingKeyBinding() throws Exception {
        AmqpBinding binding = new AmqpBinding(
            AmqpBinding.ChannelType.ROUTING_KEY, "events", "topic", true, null, true, null);
        AsyncApiSpec spec = specWithChannel("user.signedup", binding);
        AmqpMessagePublisher publisher = new AmqpMessagePublisher(connection, amqpChannel, spec);

        publisher.publish("user.signedup", "{\"e\":1}");

        verify(amqpChannel).exchangeDeclare(eq("events"),
            eq(BuiltinExchangeType.TOPIC), eq(true));
        verify(amqpChannel).basicPublish(eq("events"), eq("user.signedup"), isNull(),
            eq("{\"e\":1}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void shouldDeclareQueueAndPublishToDefaultExchangeForQueueBinding() throws Exception {
        AmqpBinding binding = new AmqpBinding(
            AmqpBinding.ChannelType.QUEUE, null, null, true, "orders-queue", true, null);
        AsyncApiSpec spec = specWithChannel("orders", binding);
        AmqpMessagePublisher publisher = new AmqpMessagePublisher(connection, amqpChannel, spec);

        publisher.publish("orders", "{\"o\":1}");

        verify(amqpChannel).queueDeclare(eq("orders-queue"), eq(true), eq(false), eq(false), isNull());
        verify(amqpChannel).basicPublish(eq(""), eq("orders-queue"), isNull(),
            eq("{\"o\":1}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void shouldDeclareExchangeOnlyOnceAcrossMultiplePublishes() throws Exception {
        AmqpBinding binding = new AmqpBinding(
            AmqpBinding.ChannelType.ROUTING_KEY, "events", "direct", true, null, true, null);
        AsyncApiSpec spec = specWithChannel("c", binding);
        AmqpMessagePublisher publisher = new AmqpMessagePublisher(connection, amqpChannel, spec);

        publisher.publish("c", "{\"n\":1}");
        publisher.publish("c", "{\"n\":2}");

        verify(amqpChannel, times(1)).exchangeDeclare(eq("events"), any(BuiltinExchangeType.class), anyBoolean());
        verify(amqpChannel, times(2)).basicPublish(eq("events"), eq("c"), any(), any(byte[].class));
    }

    @Test
    public void shouldEmitHeadersAsAmqpProperties() throws Exception {
        AsyncApiSpec spec = specWithChannel("c", null);
        AmqpMessagePublisher publisher = new AmqpMessagePublisher(connection, amqpChannel, spec);

        PublishOptions options = new PublishOptions(null, null, null,
            java.util.Collections.singletonMap("correlationId", "abc-123"));
        publisher.publish("c", "{\"x\":1}", options);

        verify(amqpChannel).basicPublish(eq(""), eq("c"),
            argThat((AMQP.BasicProperties props) -> props != null && props.getHeaders() != null
                && "abc-123".equals(String.valueOf(props.getHeaders().get("correlationId")))),
            any(byte[].class));
    }

    @Test
    public void closeShouldCloseChannelAndConnection() throws Exception {
        AmqpMessagePublisher publisher = new AmqpMessagePublisher(connection, amqpChannel, null);
        publisher.close();
        verify(amqpChannel).close();
        verify(connection).close();
    }

    private AsyncApiSpec specWithChannel(String name, AmqpBinding binding) {
        AsyncApiChannel channel = new AsyncApiChannel(
            name, List.of(), null, null, null, null, null, null, binding);
        return new AsyncApiSpec("2.6.0", "Test", List.of(channel));
    }
}
