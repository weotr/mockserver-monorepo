package org.mockserver.httpclient;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.ScheduledFuture;

import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * A small keyed pool of idle, reusable upstream HTTP/1.1 keep-alive connections used by
 * {@link NettyHttpClient} when {@code forwardConnectionPoolEnabled} is set.
 * <p>
 * Connections are keyed by {@code host:port:secure} so that a checked-out idle channel is only
 * ever reused for an identical upstream (same host, same port, same TLS setting). The pool is
 * deliberately conservative:
 * <ul>
 *     <li>Only plain HTTP/1.1 keep-alive channels are ever offered to the pool — HTTP/2, HTTP/3,
 *     binary, streaming and proxy-tunnelled channels are never pooled (the caller never releases
 *     them here).</li>
 *     <li>{@link #acquire(String)} only ever returns a channel that is still {@code isActive()};
 *     stale (server-closed) channels are discarded.</li>
 *     <li>On saturation (per-key idle limit reached) {@link #release(String, Channel)} returns
 *     {@code false} so the caller closes the surplus channel rather than blocking or failing.</li>
 *     <li>Idle channels are evicted after {@code idleTimeoutMillis} of inactivity via a one-shot
 *     close scheduled on the channel's own event loop (cancelled if the channel is re-acquired).</li>
 * </ul>
 * The pool itself holds no event-loop threads and performs no I/O; it is a thread-safe index of
 * idle channels. All mutation of the per-key deques is guarded by the deque's own monitor.
 */
class HttpForwardConnectionPool {

    /**
     * The maximum number of idle channels retained per upstream key. Surplus channels are closed
     * rather than pooled (graceful degradation under saturation — never blocks).
     */
    private final int maxIdleConnectionsPerKey;

    /**
     * How long an idle pooled channel is retained before being closed and evicted.
     */
    private final long idleTimeoutMillis;

    private final Map<String, Deque<Channel>> idleChannels = new ConcurrentHashMap<>();

    /**
     * Marks a channel for which a close listener has already been registered, so the listener is
     * added exactly once over the channel's lifetime even though the channel may be pooled and
     * reused many times.
     */
    private static final AttributeKey<Boolean> CLOSE_LISTENER_ADDED = AttributeKey.valueOf("POOL_CLOSE_LISTENER_ADDED");

    HttpForwardConnectionPool(int maxIdleConnectionsPerKey, long idleTimeoutMillis) {
        this.maxIdleConnectionsPerKey = Math.max(1, maxIdleConnectionsPerKey);
        this.idleTimeoutMillis = idleTimeoutMillis;
    }

    /**
     * Builds the pool key for an upstream. {@code null} is returned only for a null address, in
     * which case the caller must not attempt to pool.
     */
    static String keyFor(InetSocketAddress remoteAddress, boolean secure) {
        if (remoteAddress == null) {
            return null;
        }
        String host = remoteAddress.getHostString();
        return host + ":" + remoteAddress.getPort() + ":" + secure;
    }

    /**
     * Returns an idle, still-active channel for the key, or {@code null} if none is available.
     * Any stale (inactive) channels encountered are discarded.
     */
    Channel acquire(String key) {
        if (key == null) {
            return null;
        }
        Deque<Channel> deque = idleChannels.get(key);
        if (deque == null) {
            return null;
        }
        synchronized (deque) {
            Channel channel;
            while ((channel = deque.pollFirst()) != null) {
                if (channel.isActive()) {
                    cancelIdleEviction(channel);
                    return channel;
                }
                // stale - drop it (its own close listener will also have removed it)
                channel.close();
            }
        }
        return null;
    }

    /**
     * Offers an idle, reusable channel back to the pool. Returns {@code true} if the channel was
     * accepted (the caller must NOT close it), or {@code false} if the pool is saturated for this
     * key and the caller should close the channel.
     */
    boolean release(String key, Channel channel) {
        // The isActive() check here is a fast best-effort guard; a channel can still go inactive
        // after this point, which is safe because acquire() re-checks isActive() and discards any
        // dead channel before returning it.
        if (key == null || channel == null || !channel.isActive()) {
            return false;
        }
        Deque<Channel> deque = idleChannels.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            if (deque.size() >= maxIdleConnectionsPerKey) {
                return false;
            }
            deque.addLast(channel);
        }
        // remove from the pool if the server closes the connection while it sits idle (added once
        // per channel lifetime even though the channel may be pooled and reused repeatedly)
        if (channel.attr(CLOSE_LISTENER_ADDED).compareAndSet(null, Boolean.TRUE)) {
            channel.closeFuture().addListener(future -> removeIdle(key, channel));
        }
        scheduleIdleEviction(key, channel);
        return true;
    }

    private void scheduleIdleEviction(String key, Channel channel) {
        if (idleTimeoutMillis <= 0) {
            return;
        }
        ScheduledFuture<?> eviction = channel.eventLoop().schedule(() -> {
            if (removeIdle(key, channel)) {
                channel.close();
            }
        }, idleTimeoutMillis, TimeUnit.MILLISECONDS);
        channel.attr(NettyHttpClient.POOL_IDLE_EVICTION).set(eviction);
    }

    private void cancelIdleEviction(Channel channel) {
        ScheduledFuture<?> eviction = channel.attr(NettyHttpClient.POOL_IDLE_EVICTION).getAndSet(null);
        if (eviction != null) {
            eviction.cancel(false);
        }
    }

    /**
     * Removes a specific channel from the idle set for a key. Returns true if it was present (i.e.
     * still idle), false if it had already been acquired or evicted.
     */
    private boolean removeIdle(String key, Channel channel) {
        Deque<Channel> deque = idleChannels.get(key);
        if (deque == null) {
            return false;
        }
        synchronized (deque) {
            return deque.remove(channel);
        }
    }

}
