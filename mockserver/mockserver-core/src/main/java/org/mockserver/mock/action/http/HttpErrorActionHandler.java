package org.mockserver.mock.action.http;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http2.DefaultHttp2ResetFrame;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2StreamChannel;
import org.mockserver.model.HttpError;
import org.mockserver.model.HttpRequest;

/**
 * Applies an {@link HttpError} action to the underlying Netty channel: writes raw response bytes,
 * resets the request stream (HTTP/2 RST_STREAM, written here; HTTP/3 RESET_STREAM, handled by the
 * HTTP/3 response writer seam in mockserver-netty), and/or drops the connection.
 *
 * @author jamesdbloom
 */
public class HttpErrorActionHandler {

    public void handle(HttpError httpError, ChannelHandlerContext ctx) {
        handle(httpError, null, ctx);
    }

    public void handle(HttpError httpError, HttpRequest request, ChannelHandlerContext ctx) {
        if (httpError.getResponseBytes() != null) {
            // write byte directly by skipping over HTTP codec
            ChannelHandlerContext httpCodecContext = ctx.pipeline().context(HttpServerCodec.class);
            if (httpCodecContext != null) {
                httpCodecContext.writeAndFlush(Unpooled.wrappedBuffer(httpError.getResponseBytes())).awaitUninterruptibly();
            }
        }
        if (httpError.getStreamError() != null) {
            // reset only this stream, leaving other multiplexed streams on the connection alive.
            // HTTP/3 (QuicStreamChannel) is handled earlier by the StreamErrorWriter seam in the
            // HTTP/3 response writer (mockserver-netty), so we only reach here for HTTP/2 (reset the
            // matched stream) and HTTP/1.1 (no stream concept -> fall back to dropping the connection).
            boolean reset = resetHttp2Stream(httpError.getStreamError(), request, ctx);
            if (!reset) {
                // HTTP/1.1 (or HTTP/2 stream id unavailable): there is no stream to reset, so fall
                // back to the existing HttpError connection-drop behaviour.
                ctx.disconnect();
                ctx.close();
            }
            return;
        }
        if (httpError.getDropConnection() != null && httpError.getDropConnection()) {
            ctx.disconnect();
            ctx.close();
        }
    }

    /**
     * Reset the matched HTTP/2 stream with the given error code, returning true if a reset was issued.
     * Supports both the connection-level pipeline ({@link Http2ConnectionHandler}, the default path)
     * and the multiplex pipeline (per-stream {@link Http2StreamChannel} child channels, used when gRPC
     * bidi streaming is enabled).
     */
    private boolean resetHttp2Stream(long errorCode, HttpRequest request, ChannelHandlerContext ctx) {
        // Multiplex path: the request is processed on a per-stream Http2StreamChannel child channel, so
        // writing a DefaultHttp2ResetFrame on that child channel resets exactly that stream. The parent
        // Http2MultiplexHandler/Http2FrameCodec translates it into a RST_STREAM frame for the stream.
        if (ctx.channel() instanceof Http2StreamChannel) {
            ctx.writeAndFlush(new DefaultHttp2ResetFrame(errorCode));
            return true;
        }
        // Connection-level path: a single Http2ConnectionHandler multiplexes all streams over one
        // channel, so resetStream(...) is targeted by stream id (carried on the request from the
        // x-http2-stream-id extension header set by InboundHttp2ToHttpAdapter).
        ChannelHandlerContext connectionHandlerContext = ctx.pipeline().context(Http2ConnectionHandler.class);
        if (connectionHandlerContext != null && request != null && request.getStreamId() != null) {
            Http2ConnectionHandler connectionHandler = (Http2ConnectionHandler) connectionHandlerContext.handler();
            connectionHandler.resetStream(connectionHandlerContext, request.getStreamId(), errorCode, connectionHandlerContext.newPromise());
            connectionHandlerContext.flush();
            return true;
        }
        return false;
    }

}
