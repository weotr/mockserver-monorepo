package org.mockserver.mock.action.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.*;
import org.mockserver.model.WebSocketFrameType;
import org.mockserver.model.WebSocketMessage;
import org.mockserver.model.WebSocketMessageMatcher;

import java.util.List;
import java.util.regex.PatternSyntaxException;

/**
 * Installed after a WebSocket handshake when the HttpWebSocketResponse has matchers.
 * Evaluates incoming frames against the matcher list in order; first match sends its responses.
 */
public class BidirectionalWebSocketFrameHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    private final List<WebSocketMessageMatcher> matchers;
    private final FrameSender frameSender;

    /**
     * Callback interface for sending response frames to the client.
     */
    public interface FrameSender {
        void send(ChannelHandlerContext ctx, WebSocketMessage message);
    }

    public BidirectionalWebSocketFrameHandler(List<WebSocketMessageMatcher> matchers, FrameSender frameSender) {
        super(false); // don't auto-release frames — retain for pass-through if unmatched
        this.matchers = matchers;
        this.frameSender = frameSender;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        for (WebSocketMessageMatcher matcher : matchers) {
            if (matches(matcher, frame)) {
                if (matcher.getResponses() != null) {
                    for (WebSocketMessage response : matcher.getResponses()) {
                        frameSender.send(ctx, response);
                    }
                }
                frame.release();
                return; // first match wins
            }
        }
        // No matcher matched — pass frame through to next handler
        ctx.fireChannelRead(frame);
    }

    boolean matches(WebSocketMessageMatcher matcher, WebSocketFrame frame) {
        // Check frame type
        WebSocketFrameType expected = matcher.getFrameType();
        if (expected != null && expected != WebSocketFrameType.ANY) {
            if (expected == WebSocketFrameType.TEXT && !(frame instanceof TextWebSocketFrame)) {
                return false;
            }
            if (expected == WebSocketFrameType.BINARY && !(frame instanceof BinaryWebSocketFrame)) {
                return false;
            }
            if (expected == WebSocketFrameType.PING && !(frame instanceof PingWebSocketFrame)) {
                return false;
            }
            if (expected == WebSocketFrameType.PONG && !(frame instanceof PongWebSocketFrame)) {
                return false;
            }
        }
        // Check text content if matcher has a text matcher
        if (matcher.getTextMatcher() != null && frame instanceof TextWebSocketFrame textFrame) {
            String text = textFrame.text();
            String pattern = matcher.getTextMatcher().getValue();
            if (pattern != null && !pattern.isEmpty()) {
                // Try exact match first, then regex
                if (text.equals(pattern)) {
                    return true;
                }
                try {
                    return text.matches(pattern);
                } catch (PatternSyntaxException e) {
                    return false;
                }
            }
        }
        return true;
    }
}
