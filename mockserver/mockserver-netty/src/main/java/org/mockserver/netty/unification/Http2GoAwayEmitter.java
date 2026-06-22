package org.mockserver.netty.unification;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2ConnectionHandler;

/**
 * Emits an HTTP/2 {@code GOAWAY} frame on a connection-level (default) HTTP/2 pipeline so the client
 * is told to stop opening new streams and drain — the graceful "this connection is going away"
 * signal a server sends before a shutdown / preemption. In-flight streams are allowed to complete;
 * GOAWAY does not reset them.
 *
 * <p>The connection handler is resolved with the same lookup pattern as
 * {@code HttpErrorActionHandler.resetHttp2Stream} — {@code ctx.pipeline().context(Http2ConnectionHandler.class)}
 * — so this works whether called from a child handler or the connection handler's own context.
 *
 * <p>v1 supports the connection-level pipeline only. The multiplex pipeline (per-stream child
 * channels, used for gRPC bidi streaming) is intentionally deferred — see docs/code/chaos.md.
 * HTTP/1.1 has no GOAWAY concept; callers degrade to {@code Connection: close} + 503 instead.
 */
public final class Http2GoAwayEmitter {

    private Http2GoAwayEmitter() {
    }

    /**
     * Emit a GOAWAY on the connection carrying {@code ctx}, if it is a connection-level HTTP/2
     * pipeline.
     *
     * @param ctx          a context on the HTTP/2 channel's pipeline
     * @param lastStreamId the {@code lastStreamId} to advertise; when negative the connection
     *                     handler's current last-stream-id is used (passing the max stream id so the
     *                     handler clamps to the connection's actual last-processed stream)
     * @param errorCode    the HTTP/2 error code (0 = NO_ERROR, the graceful-shutdown code)
     * @return {@code true} if a GOAWAY was written (connection-level HTTP/2 pipeline present),
     *     {@code false} otherwise (e.g. HTTP/1.1, or no HTTP/2 connection handler on the pipeline)
     */
    public static boolean emit(ChannelHandlerContext ctx, long lastStreamId, long errorCode) {
        if (ctx == null) {
            return false;
        }
        ChannelHandlerContext connCtx = ctx.pipeline().context(Http2ConnectionHandler.class);
        if (connCtx == null) {
            return false;
        }
        Http2ConnectionHandler connectionHandler = (Http2ConnectionHandler) connCtx.handler();
        // A negative lastStreamId means "use the connection's current last stream"; Integer.MAX_VALUE
        // is clamped down to the real last-created stream id by the connection handler, so it is the
        // safe "all streams so far" sentinel.
        int effectiveLastStreamId = lastStreamId < 0 ? Integer.MAX_VALUE : (int) Math.min(lastStreamId, Integer.MAX_VALUE);
        long effectiveErrorCode = errorCode < 0 ? 0L : errorCode;
        connectionHandler.goAway(connCtx, effectiveLastStreamId, effectiveErrorCode, Unpooled.EMPTY_BUFFER, connCtx.newPromise());
        connCtx.flush();
        return true;
    }
}
