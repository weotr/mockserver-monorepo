package org.mockserver.netty.unification;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.mockserver.model.HttpResponse;

/**
 * Outbound handler that adds an {@code Alt-Svc} header to responses served
 * over the TCP (HTTP/1.1 and HTTP/2) paths when HTTP/3 is enabled.
 * <p>
 * The header advertises the HTTP/3 endpoint so HTTP/3-capable clients can
 * auto-upgrade to QUIC on subsequent requests while falling back to
 * HTTP/2 or HTTP/1.1 if QUIC is unavailable (RFC 7838).
 * <p>
 * The handler does NOT overwrite an {@code alt-svc} header that was
 * explicitly set by a user expectation.
 * <p>
 * This handler is {@link ChannelHandler.Sharable} because it keeps no
 * per-channel mutable state — the Alt-Svc header value is computed once
 * at construction time and is immutable.
 */
@ChannelHandler.Sharable
public class AltSvcHeaderHandler extends ChannelDuplexHandler {

    private static final String ALT_SVC_HEADER = "alt-svc";

    private final String altSvcValue;

    /**
     * @param http3Port the UDP port on which the HTTP/3 (QUIC) server is listening
     * @param maxAgeSeconds the {@code ma} (max-age) parameter in seconds
     */
    public AltSvcHeaderHandler(int http3Port, long maxAgeSeconds) {
        this.altSvcValue = "h3=\":" + http3Port + "\"; ma=" + maxAgeSeconds;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof HttpResponse) {
            HttpResponse response = (HttpResponse) msg;
            // Do not clobber a user-set Alt-Svc header
            if (!response.containsHeader(ALT_SVC_HEADER)) {
                response.withHeader(ALT_SVC_HEADER, altSvcValue);
            }
        }
        ctx.write(msg, promise);
    }
}
