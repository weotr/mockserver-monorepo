package org.mockserver.mock.breakpoint;

/**
 * The resolution decision for a paused (breakpointed) streaming response frame.
 * <p>
 * Five actions are supported:
 * <ul>
 *     <li>{@link Action#CONTINUE} — write the original frame as-is</li>
 *     <li>{@link Action#MODIFY} — write a replacement frame body</li>
 *     <li>{@link Action#DROP} — discard the frame (do not write to client)</li>
 *     <li>{@link Action#INJECT} — write the original frame AND an additional injected frame</li>
 *     <li>{@link Action#CLOSE} — end the stream (send LastHttpContent, close connection)</li>
 * </ul>
 */
public class StreamFrameDecision {

    public enum Action {
        CONTINUE,
        MODIFY,
        DROP,
        INJECT,
        CLOSE
    }

    private final Action action;
    private final byte[] replacementBody;
    private final byte[] injectedBody;

    private StreamFrameDecision(Action action, byte[] replacementBody, byte[] injectedBody) {
        this.action = action;
        this.replacementBody = replacementBody;
        this.injectedBody = injectedBody;
    }

    /**
     * Continue: write the original frame unchanged.
     */
    public static StreamFrameDecision continueFrame() {
        return new StreamFrameDecision(Action.CONTINUE, null, null);
    }

    /**
     * Modify: write a replacement frame body instead of the original.
     *
     * @param replacementBody the replacement bytes (must not be null)
     */
    public static StreamFrameDecision modify(byte[] replacementBody) {
        if (replacementBody == null) {
            throw new IllegalArgumentException("replacementBody must not be null for MODIFY decision");
        }
        return new StreamFrameDecision(Action.MODIFY, replacementBody, null);
    }

    /**
     * Drop: discard the frame without writing anything to the client.
     */
    public static StreamFrameDecision drop() {
        return new StreamFrameDecision(Action.DROP, null, null);
    }

    /**
     * Inject: write the original frame, then also write an additional injected frame.
     *
     * @param injectedBody the extra frame body to inject after the original
     */
    public static StreamFrameDecision inject(byte[] injectedBody) {
        if (injectedBody == null) {
            throw new IllegalArgumentException("injectedBody must not be null for INJECT decision");
        }
        return new StreamFrameDecision(Action.INJECT, null, injectedBody);
    }

    /**
     * Close: end the stream (drop the held frame, send LastHttpContent, close the connection).
     */
    public static StreamFrameDecision close() {
        return new StreamFrameDecision(Action.CLOSE, null, null);
    }

    public Action getAction() {
        return action;
    }

    /**
     * The replacement body (non-null only for {@link Action#MODIFY}).
     */
    public byte[] getReplacementBody() {
        return replacementBody;
    }

    /**
     * The injected body (non-null only for {@link Action#INJECT}).
     */
    public byte[] getInjectedBody() {
        return injectedBody;
    }
}
