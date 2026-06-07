package org.mockserver.netty.unification;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPromise;
import org.mockserver.mock.action.http.TcpChaosRegistry;
import org.mockserver.model.TcpChaosProfile;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

/**
 * Netty {@link ChannelDuplexHandler} that injects raw TCP-layer faults
 * <em>before</em> HTTP decoding, based on the {@link TcpChaosProfile}
 * registered in {@link TcpChaosRegistry} for the connection's remote host.
 *
 * <p>Fault types mirror Toxiproxy's named toxics: latency, down, bandwidth,
 * slow_close, timeout, reset_peer, slicer, limit_data.
 *
 * <p>This handler is <em>not</em> sharable ({@code @ChannelHandler.Sharable}
 * is intentionally absent) because it maintains per-connection state
 * ({@code bytesConsumed} for the {@code limitData} fault).
 */
public class TcpChaosHandler extends ChannelDuplexHandler {

    private long bytesConsumed = 0;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        TcpChaosProfile profile = getProfile(ctx);
        if (profile == null || !profile.hasAnyFault()) {
            ctx.fireChannelRead(msg);
            return;
        }

        // down: drop ALL incoming data silently
        if (Boolean.TRUE.equals(profile.getDown())) {
            if (msg instanceof ByteBuf) {
                ((ByteBuf) msg).release();
            }
            return;
        }

        // reset peer: send RST and close immediately
        if (Boolean.TRUE.equals(profile.getResetPeer())) {
            if (msg instanceof ByteBuf) {
                ((ByteBuf) msg).release();
            }
            ctx.channel().config().setOption(ChannelOption.SO_LINGER, 0);
            ctx.close();
            return;
        }

        // limitData: close the connection after consuming enough bytes
        if (profile.getLimitDataBytes() != null && profile.getLimitDataBytes() > 0) {
            if (msg instanceof ByteBuf) {
                ByteBuf buf = (ByteBuf) msg;
                if (bytesConsumed >= profile.getLimitDataBytes()) {
                    buf.release();
                    ctx.close();
                    return;
                }
                bytesConsumed += buf.readableBytes();
            }
        }

        // slicer: fragment the buffer into small chunks
        if (profile.getSlicerChunkSize() != null && profile.getSlicerChunkSize() > 0
            && msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            int chunkSize = profile.getSlicerChunkSize();
            while (buf.readableBytes() > 0) {
                int len = Math.min(chunkSize, buf.readableBytes());
                ctx.fireChannelRead(buf.readRetainedSlice(len));
            }
            buf.release();
            return;
        }

        // bandwidth: throttle inbound data by scheduling a delayed forward
        if (profile.getBandwidthBytesPerSec() != null && profile.getBandwidthBytesPerSec() > 0
            && msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            long delayMs = (buf.readableBytes() * 1000L) / profile.getBandwidthBytesPerSec();
            if (delayMs > 0) {
                Object captured = msg;
                ctx.channel().eventLoop().schedule(
                    () -> ctx.fireChannelRead(captured),
                    delayMs, TimeUnit.MILLISECONDS
                );
                return;
            }
        }

        // latency: schedule the forward with a delay
        if (profile.getLatencyMs() != null && profile.getLatencyMs() > 0) {
            Object captured = msg;
            ctx.channel().eventLoop().schedule(
                () -> ctx.fireChannelRead(captured),
                profile.getLatencyMs(), TimeUnit.MILLISECONDS
            );
            return;
        }

        ctx.fireChannelRead(msg);
    }

    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        TcpChaosProfile profile = getProfile(ctx);
        if (profile != null && Boolean.TRUE.equals(profile.getSlowClose())) {
            // Delay the actual close by 2 seconds to simulate slow FIN
            ctx.channel().eventLoop().schedule(
                () -> ctx.close(promise),
                2000, TimeUnit.MILLISECONDS
            );
            return;
        }
        if (profile != null && Boolean.TRUE.equals(profile.getTimeout())) {
            // Timeout: never send FIN, just silently drop the close
            promise.setSuccess();
            return;
        }
        super.close(ctx, promise);
    }

    private TcpChaosProfile getProfile(ChannelHandlerContext ctx) {
        if (ctx.channel().remoteAddress() instanceof InetSocketAddress) {
            InetSocketAddress addr = (InetSocketAddress) ctx.channel().remoteAddress();
            return TcpChaosRegistry.getInstance().get(addr.getHostString());
        }
        return null;
    }
}
