package org.mockserver.netty.http3;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3DataFrame;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import io.netty.handler.codec.quic.QuicStreamChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * HTTP/3 CONNECT-UDP (MASQUE, RFC 9298) relay handler.
 * <p>
 * This handler intercepts HTTP/3 extended CONNECT requests with
 * {@code :protocol = connect-udp} when the {@code http3ConnectUdpEnabled}
 * configuration flag is set to {@code true}. It is inserted into the QUIC
 * stream pipeline <strong>before</strong> the regular {@link Http3MockServerHandler}
 * so that CONNECT-UDP requests are handled here and non-CONNECT requests pass
 * through to the normal mock pipeline.
 * <p>
 * <strong>Relay design:</strong>
 * <ol>
 *   <li>The handler recognises an extended CONNECT request by checking
 *       {@code :method = CONNECT} and {@code :protocol = connect-udp}.</li>
 *   <li>The target authority is parsed from the {@code :authority}
 *       pseudo-header (host:port).</li>
 *   <li>A UDP {@link NioDatagramChannel} is opened and connected to the
 *       target address.</li>
 *   <li>The handler responds with {@code 200 OK} to indicate the tunnel is
 *       established.</li>
 *   <li>Subsequent HTTP/3 DATA frames received on the QUIC stream are
 *       forwarded as UDP datagrams to the target. Each DATA frame payload
 *       is sent as one UDP datagram (simple 1:1 mapping without capsule
 *       framing -- suitable for testing).</li>
 *   <li>UDP datagrams received from the target are forwarded back to the
 *       client as HTTP/3 DATA frames on the same QUIC stream.</li>
 *   <li>When the QUIC stream is closed (input closed, error, or handler
 *       removal), the UDP channel is closed to release resources.</li>
 * </ol>
 * <p>
 * Plain CONNECT requests (without {@code :protocol}) are passed through to
 * the next handler in the pipeline (the mock handler) -- MockServer is not a
 * TCP forward proxy.
 * <p>
 * <strong>Framing note:</strong> a production MASQUE proxy would use the HTTP
 * Datagram / Capsule Protocol (RFC 9297) framing. This implementation uses a
 * simplified 1:1 DATA-frame-to-UDP-datagram mapping which is sufficient for
 * testing and mock-server usage. The Capsule Protocol adds a context ID varint
 * prefix; clients that require it should be adapted accordingly.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9298">RFC 9298 - Proxying UDP in HTTP (CONNECT-UDP)</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9220">RFC 9220 - Bootstrapping WebSockets with HTTP/3</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9297">RFC 9297 - HTTP Datagrams and the Capsule Protocol</a>
 */
public class Http3ConnectUdpHandler extends ChannelInboundHandlerAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(Http3ConnectUdpHandler.class);

    /**
     * The UDP channel connected to the relay target. Null until a CONNECT-UDP
     * tunnel is established. Volatile because the channel may be closed from
     * a different thread (e.g. event loop vs handler removal).
     */
    private volatile Channel udpChannel;

    /**
     * Whether this handler has consumed the CONNECT request and established a
     * tunnel. When true, subsequent DATA frames are relayed; when false, the
     * handler is transparent and passes everything through.
     */
    private boolean tunnelEstablished;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (tunnelEstablished && msg instanceof Http3DataFrame) {
            // tunnel is active -- relay DATA frame payload as a UDP datagram
            relayToTarget(ctx, (Http3DataFrame) msg);
            return;
        }

        if (msg instanceof Http3HeadersFrame) {
            Http3HeadersFrame headersFrame = (Http3HeadersFrame) msg;
            CharSequence method = headersFrame.headers().method();
            CharSequence protocol = headersFrame.headers().protocol();

            if (method != null && "CONNECT".equalsIgnoreCase(method.toString())) {
                if (protocol != null && "connect-udp".equalsIgnoreCase(protocol.toString())) {
                    // Extended CONNECT with :protocol=connect-udp (RFC 9298)
                    handleConnectUdp(ctx, headersFrame);
                    return;
                }
                // Plain CONNECT (no :protocol) -- pass through to mock handler.
                // MockServer can match it as a regular request.
            }
        }

        // Not a CONNECT-UDP request -- pass through to the next handler
        // (Http3MockServerHandler for normal mock/proxy processing)
        ctx.fireChannelRead(msg);
    }

    /**
     * Establish a CONNECT-UDP tunnel: parse the target authority, open a UDP
     * channel, and respond with 200 OK.
     */
    private void handleConnectUdp(ChannelHandlerContext ctx, Http3HeadersFrame headersFrame) {
        CharSequence authority = headersFrame.headers().authority();
        if (authority == null || authority.length() == 0) {
            sendErrorResponse(ctx, "400", "Missing :authority for CONNECT-UDP");
            return;
        }

        InetSocketAddress targetAddress = parseAuthority(authority.toString());
        if (targetAddress == null) {
            sendErrorResponse(ctx, "400", "Invalid :authority for CONNECT-UDP: " + authority);
            return;
        }

        LOG.info("CONNECT-UDP tunnel requested to {} -- establishing UDP relay", targetAddress);

        // Open a UDP channel connected to the target.
        // Reuse the parent channel's event loop group for the UDP socket.
        Bootstrap udpBootstrap = new Bootstrap()
            .group(ctx.channel().eventLoop())
            .channel(NioDatagramChannel.class)
            .handler(new UdpRelayHandler(ctx));

        ChannelFuture bindFuture = udpBootstrap.bind(0);
        bindFuture.addListener(future -> {
            if (!future.isSuccess()) {
                LOG.error("Failed to bind UDP channel for CONNECT-UDP relay to {}",
                    targetAddress, future.cause());
                sendErrorResponse(ctx, "502", "Failed to establish UDP relay: " + future.cause().getMessage());
                return;
            }

            Channel boundChannel = bindFuture.channel();
            ChannelFuture connectFuture = boundChannel.connect(targetAddress);
            connectFuture.addListener(connFuture -> {
                if (!connFuture.isSuccess()) {
                    LOG.error("Failed to connect UDP channel to {} for CONNECT-UDP relay",
                        targetAddress, connFuture.cause());
                    boundChannel.close();
                    sendErrorResponse(ctx, "502", "Failed to connect UDP relay: " + connFuture.cause().getMessage());
                    return;
                }

                this.udpChannel = boundChannel;
                this.tunnelEstablished = true;

                LOG.info("CONNECT-UDP tunnel established to {} -- relaying datagrams", targetAddress);

                // Respond 200 OK to the client to indicate the tunnel is up
                DefaultHttp3HeadersFrame responseHeaders = new DefaultHttp3HeadersFrame();
                responseHeaders.headers().status("200");
                responseHeaders.headers().add("server", "mockserver-http3-masque");
                ctx.writeAndFlush(responseHeaders);
            });
        });
    }

    /**
     * Relay a DATA frame payload from the client to the UDP target.
     */
    private void relayToTarget(ChannelHandlerContext ctx, Http3DataFrame dataFrame) {
        Channel target = this.udpChannel;
        if (target == null || !target.isActive()) {
            LOG.warn("CONNECT-UDP relay: UDP channel not active, dropping data frame");
            dataFrame.release();
            return;
        }

        ByteBuf content = dataFrame.content();
        if (content.isReadable()) {
            // Send the DATA frame payload as a UDP datagram
            InetSocketAddress remoteAddr = (InetSocketAddress) target.remoteAddress();
            DatagramPacket packet = new DatagramPacket(content.retain(), remoteAddr);
            target.writeAndFlush(packet).addListener(f -> {
                if (!f.isSuccess()) {
                    LOG.debug("CONNECT-UDP relay: failed to send datagram to {}", remoteAddr, f.cause());
                }
            });
        }
        dataFrame.release();
    }

    /**
     * Send an error response and close the QUIC stream.
     */
    private void sendErrorResponse(ChannelHandlerContext ctx, String status, String message) {
        LOG.warn("CONNECT-UDP error: {} -- {}", status, message);

        String body = "{\"error\":\"" + escapeJsonString(message) + "\"}";
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);

        DefaultHttp3HeadersFrame responseHeaders = new DefaultHttp3HeadersFrame();
        responseHeaders.headers().status(status);
        responseHeaders.headers().add("content-type", "application/json; charset=utf-8");
        responseHeaders.headers().addInt("content-length", bodyBytes.length);
        responseHeaders.headers().add("server", "mockserver-http3-masque");

        ctx.write(responseHeaders);
        ctx.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(bodyBytes)))
            .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
    }

    /**
     * Parse an authority string (host:port) into an {@link InetSocketAddress}.
     * Supports both IPv4 ({@code host:port}) and IPv6 ({@code [host]:port}) forms.
     *
     * @return the parsed address, or null if the authority is malformed
     */
    static InetSocketAddress parseAuthority(String authority) {
        if (authority == null || authority.isEmpty()) {
            return null;
        }

        String host;
        int port;

        if (authority.startsWith("[")) {
            // IPv6: [host]:port
            int closeBracket = authority.indexOf(']');
            if (closeBracket < 0) {
                return null;
            }
            host = authority.substring(1, closeBracket);
            if (closeBracket + 1 < authority.length() && authority.charAt(closeBracket + 1) == ':') {
                try {
                    port = Integer.parseInt(authority.substring(closeBracket + 2));
                } catch (NumberFormatException e) {
                    return null;
                }
            } else {
                return null; // port required for CONNECT-UDP
            }
        } else {
            // IPv4 or hostname: host:port
            int lastColon = authority.lastIndexOf(':');
            if (lastColon < 0 || lastColon == authority.length() - 1) {
                return null; // port required for CONNECT-UDP
            }
            host = authority.substring(0, lastColon);
            try {
                port = Integer.parseInt(authority.substring(lastColon + 1));
            } catch (NumberFormatException e) {
                return null;
            }
        }

        if (port <= 0 || port > 65535) {
            return null;
        }

        return new InetSocketAddress(host, port);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        closeUdpChannel();
        super.channelInactive(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        closeUdpChannel();
        super.handlerRemoved(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.warn("CONNECT-UDP handler exception: {}", cause.getMessage(), cause);
        closeUdpChannel();
        ctx.close();
    }

    /**
     * Escape a string for safe embedding in a JSON string value. Handles
     * backslash, double-quote, and the control characters required by RFC 8259
     * (newline, carriage-return, tab, backspace, form-feed), plus any other
     * control character below U+0020.
     */
    static String escapeJsonString(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case '"':
                    sb.append("\\\"");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                    break;
            }
        }
        return sb.toString();
    }

    /**
     * Close the UDP relay channel if it is open.
     */
    private void closeUdpChannel() {
        Channel ch = this.udpChannel;
        if (ch != null) {
            this.udpChannel = null;
            ch.close();
            LOG.debug("CONNECT-UDP relay: UDP channel closed");
        }
    }

    /**
     * Inbound handler on the UDP channel that relays received datagrams back to
     * the client as HTTP/3 DATA frames on the QUIC stream.
     */
    private static class UdpRelayHandler extends ChannelInboundHandlerAdapter {

        private final ChannelHandlerContext quicStreamCtx;

        UdpRelayHandler(ChannelHandlerContext quicStreamCtx) {
            this.quicStreamCtx = quicStreamCtx;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof DatagramPacket) {
                DatagramPacket packet = (DatagramPacket) msg;
                ByteBuf content = packet.content();
                if (content.isReadable() && quicStreamCtx.channel().isActive()) {
                    DefaultHttp3DataFrame dataFrame = new DefaultHttp3DataFrame(content.retain());
                    quicStreamCtx.writeAndFlush(dataFrame);
                }
                packet.release();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            LOG.warn("CONNECT-UDP relay UDP handler exception: {}", cause.getMessage(), cause);
            ctx.close();
        }
    }
}
