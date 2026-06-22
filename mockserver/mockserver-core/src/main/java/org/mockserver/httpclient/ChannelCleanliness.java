package org.mockserver.httpclient;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.CombinedChannelDuplexHandler;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.http.HttpClientCodec;

import java.lang.reflect.Field;

/**
 * Decides whether an upstream HTTP/1.1 keep-alive channel is genuinely clean and idle after a
 * response — i.e. safe to return to the {@link HttpForwardConnectionPool} for reuse.
 * <p>
 * The status-range gate in {@link HttpClientHandler} (status within [100, 599]) is necessary but
 * <em>not sufficient</em>: an upstream can return a perfectly valid in-range status yet leave the
 * channel dirty — a wrong {@code Content-Length}, trailing/pipelined bytes, or raw bytes that frame
 * as an in-range status mid-stream all leave undecoded bytes sitting in the client codec's
 * cumulation buffer. Reusing such a channel feeds those leftover bytes into the parse of the
 * <em>next</em> response, desynchronising the {@code HttpClientCodec} so the next reply is silently
 * swallowed and the caller blocks until the forward timeout (the twice-burned default-on regression).
 * <p>
 * The decisive, fail-closed signal that catches this case is: <strong>the client codec's response
 * decoder has zero leftover readable bytes</strong> once the current response has been fully decoded.
 * Netty's {@link HttpClientCodec} is {@code final} and its inbound {@code HttpResponseDecoder} is a
 * private inner class, so there is no public accessor for its cumulation buffer. This class therefore
 * reads the leftover-byte count reflectively, but does so defensively and <strong>fails safe</strong>:
 * any uncertainty whatsoever — codec absent, reflection failure, unexpected type, or a non-zero
 * leftover count — is reported as "not clean", so the channel is closed rather than pooled.
 * Correctness (never reuse a desynced channel) is always preferred over reuse.
 * <p>
 * The reflection targets only Netty-owned fields ({@code CombinedChannelDuplexHandler.inboundHandler}
 * and {@code ByteToMessageDecoder.cumulation}), which are stable across Netty 4.2.x; because those
 * classes load from the (unnamed) application classpath, {@code setAccessible(true)} needs no
 * {@code --add-opens}. The {@link Field} handles are resolved once and cached.
 */
final class ChannelCleanliness {

    private static final Field INBOUND_HANDLER_FIELD = resolveField(CombinedChannelDuplexHandler.class, "inboundHandler");
    private static final Field CUMULATION_FIELD = resolveField(ByteToMessageDecoder.class, "cumulation");

    private ChannelCleanliness() {
    }

    private static Field resolveField(Class<?> declaringClass, String name) {
        try {
            Field field = declaringClass.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (Throwable throwable) {
            // If the Netty internals ever move/rename, leave the field null so isQuiescent() fails
            // safe (treats every channel as dirty and never pools) rather than ever pooling blind.
            return null;
        }
    }

    /**
     * Returns {@code true} only when the channel's {@link HttpClientCodec} response decoder has no
     * leftover undecoded bytes — i.e. the decoder is back at a clean message boundary, ready for the
     * next response — and false (fail-closed) on any uncertainty.
     * <p>
     * Must be called on the channel's event loop, after the current response has been fully decoded
     * (e.g. from {@code HttpClientHandler.channelRead0}), so that any trailing/pipelined leftover is
     * already sitting in the decoder's cumulation buffer where this check can observe it.
     */
    static boolean isQuiescent(Channel channel) {
        if (INBOUND_HANDLER_FIELD == null || CUMULATION_FIELD == null) {
            return false;
        }
        try {
            HttpClientCodec codec = channel.pipeline().get(HttpClientCodec.class);
            if (codec == null) {
                // No HTTP/1.1 client codec in the pipeline — not a path we know is clean to pool.
                return false;
            }
            Object inboundDecoder = INBOUND_HANDLER_FIELD.get(codec);
            if (!(inboundDecoder instanceof ByteToMessageDecoder)) {
                return false;
            }
            ByteBuf cumulation = (ByteBuf) CUMULATION_FIELD.get(inboundDecoder);
            // A null cumulation buffer means the decoder has consumed and released everything (clean);
            // a non-empty buffer means undecoded leftover bytes remain (dirty — must not pool).
            return cumulation == null || cumulation.readableBytes() == 0;
        } catch (Throwable throwable) {
            // Any reflection/visibility/type surprise => treat as dirty and close the channel.
            return false;
        }
    }
}
