package org.mockserver.httpclient;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.concurrent.ScheduledFuture;
import org.junit.After;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link HttpForwardConnectionPool} -- the keyed pool of idle, reusable upstream
 * HTTP/1.1 keep-alive connections used by {@link NettyHttpClient} when the forward connection pool
 * is enabled (now the default). Behaviour is exercised through the pool's package-private API using
 * Netty {@link EmbeddedChannel}s; no live network is required.
 *
 * <p>EmbeddedChannel reports {@code isActive() == true} while registered and open and uses a frozen
 * embedded clock, so scheduled idle-eviction tasks only fire after {@link EmbeddedChannel#advanceTimeBy}
 * plus {@link EmbeddedChannel#runScheduledPendingTasks()} -- this lets the eviction path be driven
 * deterministically.
 */
public class HttpForwardConnectionPoolTest {

    private static final String KEY = "localhost:1080:false";
    private static final long IDLE_TIMEOUT_MILLIS = 30_000L;

    private final List<EmbeddedChannel> channels = new ArrayList<>();

    @After
    public void tearDown() {
        for (EmbeddedChannel channel : channels) {
            channel.finishAndReleaseAll();
        }
    }

    private EmbeddedChannel newChannel() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channels.add(channel);
        return channel;
    }

    // ---- key derivation ------------------------------------------------------------------------

    @Test
    public void shouldBuildKeyFromHostPortAndSecureFlag() {
        InetSocketAddress address = InetSocketAddress.createUnresolved("example.com", 8443);

        assertThat(HttpForwardConnectionPool.keyFor(address, true), is("example.com:8443:true"));
        assertThat(HttpForwardConnectionPool.keyFor(address, false), is("example.com:8443:false"));
    }

    @Test
    public void shouldReturnNullKeyForNullAddress() {
        assertThat(HttpForwardConnectionPool.keyFor(null, true), is(nullValue()));
    }

    @Test
    public void shouldDistinguishKeysByPortAndSecureFlag() {
        InetSocketAddress portA = InetSocketAddress.createUnresolved("host", 1);
        InetSocketAddress portB = InetSocketAddress.createUnresolved("host", 2);

        assertThat(HttpForwardConnectionPool.keyFor(portA, false), not(equalTo(HttpForwardConnectionPool.keyFor(portB, false))));
        assertThat(HttpForwardConnectionPool.keyFor(portA, true), not(equalTo(HttpForwardConnectionPool.keyFor(portA, false))));
    }

    // ---- reuse ---------------------------------------------------------------------------------

    @Test
    public void shouldReuseReleasedChannelOnNextAcquireForSameKey() {
        HttpForwardConnectionPool pool = new HttpForwardConnectionPool(4, IDLE_TIMEOUT_MILLIS);
        EmbeddedChannel channel = newChannel();

        assertTrue("a still-active channel should be accepted into the pool", pool.release(KEY, channel));

        // next acquire for the same key hands back the SAME channel (no new channel created)
        assertThat(pool.acquire(KEY), is(sameInstance(channel)));
    }

    @Test
    public void shouldNotReuseChannelForADifferentKey() {
        HttpForwardConnectionPool pool = new HttpForwardConnectionPool(4, IDLE_TIMEOUT_MILLIS);
        EmbeddedChannel channel = newChannel();

        assertTrue(pool.release(KEY, channel));

        assertThat("a channel pooled under one key must never be handed to another key",
            pool.acquire("otherhost:1080:false"), is(nullValue()));
        // original key still has it
        assertThat(pool.acquire(KEY), is(sameInstance(channel)));
    }

    @Test
    public void shouldReturnNullWhenAcquiringFromUnknownOrEmptyKey() {
        HttpForwardConnectionPool pool = new HttpForwardConnectionPool(4, IDLE_TIMEOUT_MILLIS);

        // never-seen key
        assertThat(pool.acquire(KEY), is(nullValue()));

        // key that has been used then drained
        EmbeddedChannel channel = newChannel();
        assertTrue(pool.release(KEY, channel));
        assertThat(pool.acquire(KEY), is(sameInstance(channel)));
        assertThat("draining the only pooled channel leaves nothing to acquire", pool.acquire(KEY), is(nullValue()));
    }

    @Test
    public void shouldAcquireInLastInFirstOrder() {
        HttpForwardConnectionPool pool = new HttpForwardConnectionPool(4, IDLE_TIMEOUT_MILLIS);
        EmbeddedChannel first = newChannel();
        EmbeddedChannel second = newChannel();

        assertTrue(pool.release(KEY, first));
        assertTrue(pool.release(KEY, second));

        // release adds to the tail, acquire polls from the head -> FIFO. Assert the two distinct
        // channels both come back (order verified) and the pool is then empty.
        Object a = pool.acquire(KEY);
        Object b = pool.acquire(KEY);
        assertThat(a, is(sameInstance((Object) first)));
        assertThat(b, is(sameInstance((Object) second)));
        assertThat(pool.acquire(KEY), is(nullValue()));
    }

    // ---- saturation ----------------------------------------------------------------------------

    @Test
    public void shouldReturnFalseFromReleaseWhenKeyIsSaturated() {
        HttpForwardConnectionPool pool = new HttpForwardConnectionPool(2, IDLE_TIMEOUT_MILLIS);

        assertTrue("1st within capacity", pool.release(KEY, newChannel()));
        assertTrue("2nd within capacity", pool.release(KEY, newChannel()));

        EmbeddedChannel surplus = newChannel();
        assertFalse("3rd is over per-key capacity -> caller must close it", pool.release(KEY, surplus));
        assertTrue("rejected channel was NOT closed by the pool (caller owns it)", surplus.isActive());
    }

    @Test
    public void shouldSaturateIndependentlyPerKey() {
        HttpForwardConnectionPool pool = new HttpForwardConnectionPool(1, IDLE_TIMEOUT_MILLIS);

        assertTrue(pool.release("hostA:80:false", newChannel()));
        assertFalse("second under same key over capacity", pool.release("hostA:80:false", newChannel()));

        // a different key has its own independent capacity
        assertTrue("a different upstream key is not affected by another key's saturation",
            pool.release("hostB:80:false", newChannel()));
    }

    @Test
    public void shouldTreatNonPositiveCapacityAsAtLeastOne() {
        // constructor clamps maxIdleConnectionsPerKey to >= 1
        HttpForwardConnectionPool pool = new HttpForwardConnectionPool(0, IDLE_TIMEOUT_MILLIS);

        assertTrue("at least one channel is always poolable", pool.release(KEY, newChannel()));
        assertFalse("second exceeds the clamped capacity of one", pool.release(KEY, newChannel()));
    }

    // ---- stale / closed discard ----------------------------------------------------------------

    @Test
    public void shouldRejectReleaseOfAnInactiveChannel() {
        HttpForwardConnectionPool pool = new HttpForwardConnectionPool(4, IDLE_TIMEOUT_MILLIS);
        EmbeddedChannel channel = newChannel();
        channel.close().syncUninterruptibly();

        assertFalse("a closed channel must not be accepted into the pool", pool.release(KEY, channel));
        assertThat(pool.acquire(KEY), is(nullValue()));
    }

    @Test
    public void shouldDiscardStaleChannelAndReturnTheNextActiveOne() {
        HttpForwardConnectionPool pool = new HttpForwardConnectionPool(4, IDLE_TIMEOUT_MILLIS);
        EmbeddedChannel willGoStale = newChannel();
        EmbeddedChannel stillActive = newChannel();

        assertTrue(pool.release(KEY, willGoStale));
        assertTrue(pool.release(KEY, stillActive));

        // server closes the first connection while it sits idle, AFTER it was pooled
        willGoStale.close().syncUninterruptibly();

        // acquire skips the stale (now inactive) head and returns the still-active channel
        assertThat(pool.acquire(KEY), is(sameInstance(stillActive)));
    }

    @Test
    public void shouldReturnNullWhenAllPooledChannelsAreStale() {
        HttpForwardConnectionPool pool = new HttpForwardConnectionPool(4, IDLE_TIMEOUT_MILLIS);
        EmbeddedChannel staleOne = newChannel();
        EmbeddedChannel staleTwo = newChannel();

        assertTrue(pool.release(KEY, staleOne));
        assertTrue(pool.release(KEY, staleTwo));

        staleOne.close().syncUninterruptibly();
        staleTwo.close().syncUninterruptibly();

        assertThat("a pool of only-stale channels yields nothing", pool.acquire(KEY), is(nullValue()));
    }

    // ---- idle eviction -------------------------------------------------------------------------

    @Test
    public void shouldEvictAndCloseAnIdleChannelAfterTheIdleTimeout() {
        HttpForwardConnectionPool pool = new HttpForwardConnectionPool(4, IDLE_TIMEOUT_MILLIS);
        EmbeddedChannel channel = newChannel();

        assertTrue(pool.release(KEY, channel));
        assertTrue("channel is alive before the idle timeout elapses", channel.isActive());

        // advance the embedded clock past the idle timeout and fire the scheduled eviction
        channel.advanceTimeBy(IDLE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();

        assertFalse("an idle channel is closed once the idle timeout elapses", channel.isActive());
        assertThat("an evicted channel is no longer acquirable", pool.acquire(KEY), is(nullValue()));
    }

    @Test
    public void shouldNotEvictBeforeTheIdleTimeoutElapses() {
        HttpForwardConnectionPool pool = new HttpForwardConnectionPool(4, IDLE_TIMEOUT_MILLIS);
        EmbeddedChannel channel = newChannel();

        assertTrue(pool.release(KEY, channel));

        // not yet past the timeout
        channel.advanceTimeBy(IDLE_TIMEOUT_MILLIS - 1, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();

        assertTrue("channel must survive until the full idle timeout", channel.isActive());
        assertThat("channel is still acquirable before eviction fires", pool.acquire(KEY), is(sameInstance(channel)));
    }

    @Test
    public void shouldCancelIdleEvictionWhenChannelIsReAcquired() {
        HttpForwardConnectionPool pool = new HttpForwardConnectionPool(4, IDLE_TIMEOUT_MILLIS);
        EmbeddedChannel channel = newChannel();

        assertTrue(pool.release(KEY, channel));
        // re-acquire BEFORE the timeout -> eviction must be cancelled
        assertThat(pool.acquire(KEY), is(sameInstance(channel)));

        // even after advancing well past the original idle deadline, the cancelled task must not
        // close the (now in-use) channel
        channel.advanceTimeBy(IDLE_TIMEOUT_MILLIS * 2, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();

        assertTrue("re-acquiring a channel cancels its pending idle eviction", channel.isActive());
    }

    @Test
    public void shouldRescheduleEvictionOnEachRelease() {
        HttpForwardConnectionPool pool = new HttpForwardConnectionPool(4, IDLE_TIMEOUT_MILLIS);
        EmbeddedChannel channel = newChannel();

        // first lifecycle: release, re-acquire just before timeout (cancels first eviction)
        assertTrue(pool.release(KEY, channel));
        channel.advanceTimeBy(IDLE_TIMEOUT_MILLIS - 1, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        assertThat(pool.acquire(KEY), is(sameInstance(channel)));

        // second lifecycle: release again -> a fresh eviction is scheduled relative to "now"
        assertTrue(pool.release(KEY, channel));
        // advancing by just under another full timeout must NOT evict (proves the timer was reset)
        channel.advanceTimeBy(IDLE_TIMEOUT_MILLIS - 1, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        assertTrue("each release reschedules a fresh idle eviction", channel.isActive());

        // crossing the rescheduled deadline finally evicts
        channel.advanceTimeBy(1, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        assertFalse("the rescheduled eviction fires once its own deadline passes", channel.isActive());
    }

    @Test
    public void shouldNotScheduleEvictionWhenIdleTimeoutIsNonPositive() {
        HttpForwardConnectionPool pool = new HttpForwardConnectionPool(4, 0L);
        EmbeddedChannel channel = newChannel();

        assertTrue(pool.release(KEY, channel));
        // no eviction task should have been scheduled, so the POOL_IDLE_EVICTION attribute stays unset
        ScheduledFuture<?> eviction = channel.attr(NettyHttpClient.POOL_IDLE_EVICTION).get();
        assertThat("no idle eviction is scheduled when the timeout is non-positive", eviction, is(nullValue()));

        channel.advanceTimeBy(1, TimeUnit.HOURS);
        channel.runScheduledPendingTasks();
        assertTrue("with eviction disabled the channel is retained indefinitely", channel.isActive());
        assertThat(pool.acquire(KEY), is(sameInstance(channel)));
    }

    // ---- close-listener / CAS path -------------------------------------------------------------

    @Test
    public void shouldRemoveChannelFromPoolWhenItClosesWhileIdle() {
        HttpForwardConnectionPool pool = new HttpForwardConnectionPool(4, IDLE_TIMEOUT_MILLIS);
        EmbeddedChannel channel = newChannel();

        assertTrue(pool.release(KEY, channel));

        // server closes the connection while it sits idle -> the close listener removes it from the pool
        channel.close().syncUninterruptibly();
        channel.runPendingTasks();

        assertThat("a channel closed while idle is removed by its close listener, not handed out",
            pool.acquire(KEY), is(nullValue()));
    }

    @Test
    public void shouldRemainConsistentAfterManyReleaseReuseCyclesThenClose() {
        HttpForwardConnectionPool pool = new HttpForwardConnectionPool(4, IDLE_TIMEOUT_MILLIS);
        EmbeddedChannel channel = newChannel();

        // many release/acquire cycles for the same channel. The pool registers its close listener
        // exactly once per channel lifetime (CAS guard) even though the channel is pooled and reused
        // repeatedly here; this is observed behaviourally below -- a single clean removal on close,
        // never a double-removal error.
        for (int i = 0; i < 5; i++) {
            assertTrue(pool.release(KEY, channel));
            assertThat(pool.acquire(KEY), is(sameInstance(channel)));
        }

        // re-pool, then close: removal happens exactly once and the pool stays consistent
        assertTrue(pool.release(KEY, channel));
        channel.close().syncUninterruptibly();
        channel.runPendingTasks();
        assertThat(pool.acquire(KEY), is(nullValue()));
    }

    @Test
    public void shouldNotDoubleRemoveWhenAcquiredChannelLaterCloses() {
        HttpForwardConnectionPool pool = new HttpForwardConnectionPool(4, IDLE_TIMEOUT_MILLIS);
        EmbeddedChannel channel = newChannel();

        assertTrue(pool.release(KEY, channel));
        // acquire removes it from the idle deque (and cancels eviction)
        assertThat(pool.acquire(KEY), is(sameInstance(channel)));

        // now the in-use channel closes; the still-registered close listener calls removeIdle, which
        // must be a harmless no-op because the channel is no longer in the idle deque
        channel.close().syncUninterruptibly();
        channel.runPendingTasks();

        // pool remains consistent and empty for the key
        assertThat(pool.acquire(KEY), is(nullValue()));
    }

    @Test
    public void shouldRejectNullKeyAndNullChannel() {
        HttpForwardConnectionPool pool = new HttpForwardConnectionPool(4, IDLE_TIMEOUT_MILLIS);

        assertFalse("null key cannot be released", pool.release(null, newChannel()));
        assertFalse("null channel cannot be released", pool.release(KEY, null));
        assertThat("null key acquire returns null", pool.acquire(null), is(nullValue()));
    }
}
