package org.mockserver.async.asyncapi;

/**
 * Immutable model of the AsyncAPI AMQP 0.9.1 channel binding
 * ({@code channels.<name>.bindings.amqp}), carrying the publish-time routing
 * information that {@link org.mockserver.async.publish.AmqpMessagePublisher}
 * needs to deliver a message to a RabbitMQ broker.
 *
 * <p>The AsyncAPI AMQP channel binding distinguishes two channel shapes via its
 * {@code is} field:
 * <ul>
 *   <li>{@code is: routingKey} — the channel represents a routing key on an
 *       <em>exchange</em>. The channel name is used as the routing key and the
 *       exchange is named by {@code exchange.name}.</li>
 *   <li>{@code is: queue} — the channel represents a <em>queue</em> bound to the
 *       default exchange. The channel name is the queue name; messages are
 *       published to the default exchange ({@code ""}) with the queue name as the
 *       routing key.</li>
 * </ul>
 *
 * <h2>Supported binding fields</h2>
 * <table border="1">
 *   <caption>AMQP binding fields</caption>
 *   <tr><th>Field</th><th>AsyncAPI location</th><th>Effect</th></tr>
 *   <tr><td>{@code is}</td><td>{@code bindings.amqp.is}</td>
 *       <td>{@code routingKey} (exchange-based) or {@code queue} (queue-based). Defaults to {@code routingKey}.</td></tr>
 *   <tr><td>exchange name</td><td>{@code bindings.amqp.exchange.name}</td>
 *       <td>The exchange to publish to (empty string = default exchange).</td></tr>
 *   <tr><td>exchange type</td><td>{@code bindings.amqp.exchange.type}</td>
 *       <td>{@code direct}/{@code topic}/{@code fanout}/{@code headers}. Used when declaring the exchange.</td></tr>
 *   <tr><td>exchange durable</td><td>{@code bindings.amqp.exchange.durable}</td>
 *       <td>Whether a declared exchange is durable. Defaults to true.</td></tr>
 *   <tr><td>queue name</td><td>{@code bindings.amqp.queue.name}</td>
 *       <td>The queue to declare/route to (queue-based channels).</td></tr>
 *   <tr><td>queue durable</td><td>{@code bindings.amqp.queue.durable}</td>
 *       <td>Whether a declared queue is durable. Defaults to true.</td></tr>
 *   <tr><td>routing key</td><td>{@code bindings.amqp.routingKey} (extension) or the channel name</td>
 *       <td>The routing key used when publishing to the exchange.</td></tr>
 * </table>
 *
 * <h2>Deferred</h2>
 * The following AsyncAPI AMQP binding fields are intentionally <b>not</b> applied
 * at publish time and are documented as deferred:
 * <ul>
 *   <li>{@code exchange.autoDelete}, {@code exchange.vhost}, {@code queue.exclusive},
 *       {@code queue.autoDelete}, {@code queue.vhost} — topology lifecycle/scoping
 *       hints that do not affect a single publish.</li>
 *   <li>Operation/message-level AMQP bindings ({@code cc}, {@code bcc},
 *       {@code deliveryMode}, {@code mandatory}, {@code replyTo}, {@code priority},
 *       {@code timestamp}, {@code expiration}) — message-property level settings.</li>
 * </ul>
 */
public final class AmqpBinding {

    /** The two shapes an AMQP channel can take, per the AsyncAPI {@code is} field. */
    public enum ChannelType {
        ROUTING_KEY,
        QUEUE
    }

    private final ChannelType channelType;
    private final String exchangeName;
    private final String exchangeType;
    private final boolean exchangeDurable;
    private final String queueName;
    private final boolean queueDurable;
    private final String routingKey;

    public AmqpBinding(ChannelType channelType, String exchangeName, String exchangeType,
                       boolean exchangeDurable, String queueName, boolean queueDurable,
                       String routingKey) {
        this.channelType = channelType != null ? channelType : ChannelType.ROUTING_KEY;
        this.exchangeName = exchangeName;
        this.exchangeType = exchangeType;
        this.exchangeDurable = exchangeDurable;
        this.queueName = queueName;
        this.queueDurable = queueDurable;
        this.routingKey = routingKey;
    }

    public ChannelType getChannelType() {
        return channelType;
    }

    /**
     * The exchange name to publish to, or null when not specified. For a
     * queue-based channel this is typically null (the default exchange is used).
     */
    public String getExchangeName() {
        return exchangeName;
    }

    /**
     * The exchange type ({@code direct}/{@code topic}/{@code fanout}/{@code headers}),
     * or null when not specified.
     */
    public String getExchangeType() {
        return exchangeType;
    }

    public boolean isExchangeDurable() {
        return exchangeDurable;
    }

    /** The queue name (queue-based channels), or null when not specified. */
    public String getQueueName() {
        return queueName;
    }

    public boolean isQueueDurable() {
        return queueDurable;
    }

    /**
     * The explicit routing key from the binding, or null. When null the publisher
     * derives the routing key from the channel name (exchange-based) or queue name
     * (queue-based).
     */
    public String getRoutingKey() {
        return routingKey;
    }

    @Override
    public String toString() {
        return "AmqpBinding{is=" + channelType
            + ", exchange=" + exchangeName + " (" + exchangeType + ", durable=" + exchangeDurable + ")"
            + ", queue=" + queueName + " (durable=" + queueDurable + ")"
            + ", routingKey=" + routingKey + '}';
    }
}
