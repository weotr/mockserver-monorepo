package org.mockserver.mock.action.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import org.mockserver.matchers.GraphQLAstMatcher;
import org.mockserver.model.Delay;
import org.mockserver.model.GraphQLBody;
import org.mockserver.model.SelectionSetMatchType;
import org.mockserver.model.WebSocketMessage;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles the <a href="https://github.com/enisdenjo/graphql-ws/blob/master/PROTOCOL.md">graphql-transport-ws</a>
 * protocol over an already-established WebSocket connection.
 * <p>
 * Installed after the WebSocket handshake when the negotiated subprotocol is
 * {@code graphql-transport-ws} or the legacy {@code graphql-ws}.
 * <p>
 * Protocol messages handled:
 * <ul>
 *   <li>{@code connection_init} - replies {@code connection_ack}</li>
 *   <li>{@code ping} - replies {@code pong}</li>
 *   <li>{@code subscribe} - AST-matches the query against the configured subscription expectation;
 *       on match pushes a scripted sequence of {@code next} messages then {@code complete};
 *       on no match sends {@code error}</li>
 *   <li>{@code complete} (client) - cancels that subscription's pending messages</li>
 * </ul>
 */
public class GraphQLSubscriptionHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final GraphQLAstMatcher astMatcher;
    private final List<WebSocketMessage> subscriptionPayloads;
    private final FrameSender frameSender;
    private final WebSocketServerHandshaker handshaker;

    /**
     * Tracks active subscription IDs so that a client {@code complete} message
     * can signal cancellation. The handler checks this set before each scheduled
     * {@code next} push.
     */
    private final Set<String> activeSubscriptions = ConcurrentHashMap.newKeySet();

    private boolean connectionInitialised = false;

    /**
     * Callback interface for sending text frames to the client, with optional delay support.
     */
    public interface FrameSender {
        /**
         * Send a text frame, optionally after a delay.
         *
         * @param ctx   the channel context
         * @param text  the text to send
         * @param delay optional delay before sending (may be null)
         */
        void send(ChannelHandlerContext ctx, String text, Delay delay);
    }

    /**
     * @param expectedSubscriptionQuery a GraphQLBody describing the subscription query to match
     * @param subscriptionPayloads      the sequence of payloads to push as {@code next} messages
     * @param frameSender               callback for sending text frames with optional delays
     * @param handshaker                the WebSocket handshaker for closing the connection
     */
    public GraphQLSubscriptionHandler(
        GraphQLBody expectedSubscriptionQuery,
        List<WebSocketMessage> subscriptionPayloads,
        FrameSender frameSender,
        WebSocketServerHandshaker handshaker
    ) {
        super(false); // don't auto-release frames
        this.astMatcher = createMatcher(expectedSubscriptionQuery);
        this.subscriptionPayloads = subscriptionPayloads != null ? subscriptionPayloads : Collections.emptyList();
        this.frameSender = frameSender;
        this.handshaker = handshaker;
    }

    private static GraphQLAstMatcher createMatcher(GraphQLBody body) {
        return new GraphQLAstMatcher(normaliseSubscriptionBody(body));
    }

    /**
     * Normalises the subscription filter body: when no {@code selectionSetMatchType}
     * is supplied, default to {@code AST_SUBSET} (forgiving) while PRESERVING any
     * explicitly-configured {@code fields}. Package-private for testing.
     */
    static GraphQLBody normaliseSubscriptionBody(GraphQLBody body) {
        if (body.getSelectionSetMatchType() == null) {
            // Capture the original fields BEFORE reassigning `body`, otherwise they are lost.
            List<String> originalFields = body.getFields();
            body = new GraphQLBody(body.getQuery(), body.getOperationName(), body.getVariablesSchema())
                .withSelectionSetMatchType(SelectionSetMatchType.AST_SUBSET);
            if (originalFields != null) {
                body.withFields(originalFields);
            }
        }
        return body;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        if (!(frame instanceof TextWebSocketFrame textFrame)) {
            frame.release();
            return;
        }

        String text = textFrame.text();
        frame.release();

        try {
            JsonNode message = OBJECT_MAPPER.readTree(text);
            String type = message.has("type") ? message.get("type").asText() : "";

            switch (type) {
                case "connection_init" -> handleConnectionInit(ctx);
                case "ping" -> handlePing(ctx);
                case "subscribe" -> handleSubscribe(ctx, message);
                case "complete" -> handleClientComplete(message);
                default -> {
                    // Unknown message type -- ignore per the protocol spec
                }
            }
        } catch (JsonProcessingException e) {
            // Malformed JSON -- close the connection per the protocol spec
            closeConnection(ctx);
        }
    }

    private void handleConnectionInit(ChannelHandlerContext ctx) {
        connectionInitialised = true;
        sendImmediate(ctx, "{\"type\":\"connection_ack\"}");
    }

    private void handlePing(ChannelHandlerContext ctx) {
        sendImmediate(ctx, "{\"type\":\"pong\"}");
    }

    void handleSubscribe(ChannelHandlerContext ctx, JsonNode message) {
        // Per graphql-transport-ws, a client must not send subscribe before the
        // connection_init/connection_ack handshake; close the connection if it does.
        if (!connectionInitialised) {
            closeConnection(ctx);
            return;
        }

        String id = message.has("id") ? message.get("id").asText() : null;
        if (id == null || id.isEmpty()) {
            closeConnection(ctx);
            return;
        }

        // Check for duplicate subscription ID
        if (activeSubscriptions.contains(id)) {
            closeConnection(ctx);
            return;
        }

        // Extract the query from the subscribe payload
        JsonNode payload = message.get("payload");
        String query = null;
        if (payload != null && payload.has("query")) {
            query = payload.get("query").asText();
        }

        if (query == null || query.isEmpty()) {
            sendError(ctx, id, "No query provided in subscribe message");
            return;
        }

        // Match against the configured subscription expectation
        if (!astMatcher.matches(query)) {
            sendError(ctx, id, "No matching subscription expectation found for query: " + query);
            return;
        }

        // Matched -- push the configured payloads as 'next' messages, then 'complete'
        activeSubscriptions.add(id);
        pushNextSequence(ctx, id, 0);
    }

    private void pushNextSequence(ChannelHandlerContext ctx, String subscriptionId, int index) {
        if (!ctx.channel().isActive() || !activeSubscriptions.contains(subscriptionId)) {
            activeSubscriptions.remove(subscriptionId);
            return;
        }

        if (index >= subscriptionPayloads.size()) {
            // All payloads sent -- send 'complete'
            sendComplete(ctx, subscriptionId);
            activeSubscriptions.remove(subscriptionId);
            return;
        }

        WebSocketMessage payload = subscriptionPayloads.get(index);
        String nextJson = buildNextMessage(subscriptionId, payload);

        if (nextJson == null) {
            // Skip payloads with no text content
            pushNextSequence(ctx, subscriptionId, index + 1);
            return;
        }

        frameSender.send(ctx, nextJson, payload.getDelay());

        // Chain to next message -- the FrameSender handles delay
        // but we need to schedule the next push after the current one
        // For simplicity, we push all at once with cumulative delays
        pushNextSequence(ctx, subscriptionId, index + 1);
    }

    /**
     * Build a {@code next} protocol message wrapping the given payload.
     * The payload text is interpreted as JSON if possible (for proper nesting),
     * otherwise embedded as a string.
     */
    String buildNextMessage(String subscriptionId, WebSocketMessage payload) {
        String data = payload.getText();
        if (data == null) {
            return null;
        }

        ObjectNode nextMessage = OBJECT_MAPPER.createObjectNode();
        nextMessage.put("id", subscriptionId);
        nextMessage.put("type", "next");

        ObjectNode payloadWrapper = OBJECT_MAPPER.createObjectNode();
        try {
            JsonNode payloadNode = OBJECT_MAPPER.readTree(data);
            payloadWrapper.set("data", payloadNode);
        } catch (JsonProcessingException e) {
            payloadWrapper.put("data", data);
        }
        nextMessage.set("payload", payloadWrapper);

        try {
            return OBJECT_MAPPER.writeValueAsString(nextMessage);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private void handleClientComplete(JsonNode message) {
        String id = message.has("id") ? message.get("id").asText() : null;
        if (id != null) {
            activeSubscriptions.remove(id);
        }
    }

    private void sendImmediate(ChannelHandlerContext ctx, String text) {
        if (ctx.channel().isActive()) {
            ctx.writeAndFlush(new TextWebSocketFrame(text));
        }
    }

    private void sendError(ChannelHandlerContext ctx, String subscriptionId, String errorMessage) {
        ObjectNode errorMsg = OBJECT_MAPPER.createObjectNode();
        errorMsg.put("id", subscriptionId);
        errorMsg.put("type", "error");
        ObjectNode errorPayload = OBJECT_MAPPER.createObjectNode();
        errorPayload.put("message", errorMessage);
        ArrayNode errorArray = OBJECT_MAPPER.createArrayNode();
        errorArray.add(errorPayload);
        errorMsg.set("payload", errorArray);
        try {
            sendImmediate(ctx, OBJECT_MAPPER.writeValueAsString(errorMsg));
        } catch (JsonProcessingException e) {
            closeConnection(ctx);
        }
    }

    private void sendComplete(ChannelHandlerContext ctx, String subscriptionId) {
        ObjectNode completeMsg = OBJECT_MAPPER.createObjectNode();
        completeMsg.put("id", subscriptionId);
        completeMsg.put("type", "complete");
        try {
            sendImmediate(ctx, OBJECT_MAPPER.writeValueAsString(completeMsg));
        } catch (JsonProcessingException e) {
            // Best effort
        }
    }

    private void closeConnection(ChannelHandlerContext ctx) {
        if (handshaker != null && ctx.channel().isActive()) {
            handshaker.close(ctx.channel(), new CloseWebSocketFrame());
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        closeConnection(ctx);
    }

    /**
     * Check whether the given subprotocol string indicates a graphql-transport-ws
     * or legacy graphql-ws protocol.
     */
    public static boolean isGraphQLWebSocketProtocol(String subprotocol) {
        return "graphql-transport-ws".equals(subprotocol) || "graphql-ws".equals(subprotocol);
    }
}
