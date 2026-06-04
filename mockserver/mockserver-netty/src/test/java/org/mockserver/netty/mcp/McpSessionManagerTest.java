package org.mockserver.netty.mcp;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;

import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

public class McpSessionManagerTest {

    @Test
    public void shouldCreateSession() {
        McpSessionManager manager = new McpSessionManager(new MockServerLogger());
        McpSession session = manager.createSession();

        assertThat(session, notNullValue());
        assertThat(session.getSessionId(), notNullValue());
        assertThat(manager.size(), is(1));
    }

    @Test
    public void shouldGetSession() {
        McpSessionManager manager = new McpSessionManager(new MockServerLogger());
        McpSession session = manager.createSession();

        McpSession retrieved = manager.getSession(session.getSessionId());
        assertThat(retrieved, notNullValue());
        assertThat(retrieved.getSessionId(), is(session.getSessionId()));
    }

    @Test
    public void shouldReturnNullForUnknownSession() {
        McpSessionManager manager = new McpSessionManager(new MockServerLogger());

        McpSession retrieved = manager.getSession("nonexistent");
        assertThat(retrieved, nullValue());
    }

    @Test
    public void shouldValidateSession() {
        McpSessionManager manager = new McpSessionManager(new MockServerLogger());
        McpSession session = manager.createSession();

        assertThat(manager.isValidSession(session.getSessionId()), is(true));
        assertThat(manager.isValidSession("nonexistent"), is(false));
        assertThat(manager.isValidSession(null), is(false));
    }

    @Test
    public void shouldRemoveSession() {
        McpSessionManager manager = new McpSessionManager(new MockServerLogger());
        McpSession session = manager.createSession();

        McpSession removed = manager.removeSession(session.getSessionId());
        assertThat(removed, notNullValue());
        assertThat(manager.isValidSession(session.getSessionId()), is(false));
        assertThat(manager.size(), is(0));
    }

    @Test
    public void shouldReturnNullWhenRemovingNonexistentSession() {
        McpSessionManager manager = new McpSessionManager(new MockServerLogger());

        McpSession removed = manager.removeSession("nonexistent");
        assertThat(removed, nullValue());
    }

    @Test
    public void shouldEvictOldestSessionWhenMaxReached() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        McpSessionManager manager = new McpSessionManager(new MockServerLogger(), clock::get);

        // Create the first session at t=1_000_000
        McpSession firstSession = manager.createSession();
        String firstSessionId = firstSession.getSessionId();

        // Advance time so remaining sessions have a later timestamp
        clock.addAndGet(1000);

        // Create 99 more sessions (total 100 = the max)
        for (int i = 1; i < 100; i++) {
            manager.createSession();
        }
        assertThat(manager.size(), is(100));

        // Create one more -- should evict the oldest (first)
        manager.createSession();
        assertThat(manager.size(), is(100));
        assertThat(manager.isValidSession(firstSessionId), is(false));
    }

    @Test
    public void shouldTouchSessionOnGet() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        McpSessionManager manager = new McpSessionManager(new MockServerLogger(), clock::get);
        McpSession session = manager.createSession();
        long initialTime = session.getLastAccessedAt();

        // Advance time and retrieve the session (which triggers touch)
        clock.addAndGet(5000);
        McpSession retrieved = manager.getSession(session.getSessionId());

        assertThat(retrieved.getLastAccessedAt() > initialTime, is(true));
    }

    @Test
    public void shouldExpireSessionAfterTtl() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        McpSessionManager manager = new McpSessionManager(new MockServerLogger(), clock::get);
        McpSession session = manager.createSession();

        // Advance time beyond the TTL (60 minutes + 1 second)
        clock.addAndGet(60 * 60 * 1000L + 1000);

        // getSession should return null for expired session
        McpSession retrieved = manager.getSession(session.getSessionId());
        assertThat(retrieved, nullValue());

        // isValidSession should also return false
        assertThat(manager.isValidSession(session.getSessionId()), is(false));

        // Session should have been removed
        assertThat(manager.size(), is(0));
    }

    @Test
    public void shouldNotExpireRecentSession() {
        McpSessionManager manager = new McpSessionManager(new MockServerLogger());
        McpSession session = manager.createSession();

        // A just-created session should not be expired
        McpSession retrieved = manager.getSession(session.getSessionId());
        assertThat(retrieved, notNullValue());
        assertThat(manager.isValidSession(session.getSessionId()), is(true));
    }

    @Test
    public void shouldCreateSessionSynchronized() throws InterruptedException {
        McpSessionManager manager = new McpSessionManager(new MockServerLogger());
        int threadCount = 10;
        int sessionsPerThread = 10;
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            new Thread(() -> {
                for (int i = 0; i < sessionsPerThread; i++) {
                    manager.createSession();
                }
                latch.countDown();
            }).start();
        }

        latch.await(10, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(manager.size(), is(100));
    }

    @Test
    public void shouldShutdownExecutor() {
        McpSessionManager manager = new McpSessionManager(new MockServerLogger());
        assertThat(manager.getExecutor().isShutdown(), is(false));

        manager.shutdown();
        assertThat(manager.getExecutor().isShuttingDown(), is(true));
    }

    @Test
    public void shouldProvideExecutor() {
        McpSessionManager manager = new McpSessionManager(new MockServerLogger());
        assertThat(manager.getExecutor(), notNullValue());
    }

    @Test
    public void shouldEvictLeastRecentlyUsedSession() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        McpSessionManager manager = new McpSessionManager(new MockServerLogger(), clock::get);

        // Create first session at t=1_000_000
        McpSession firstSession = manager.createSession();

        // Create second session at t=1_001_000
        clock.addAndGet(1000);
        McpSession secondSession = manager.createSession();

        // Create 98 more sessions at t=1_002_000 (total 100)
        clock.addAndGet(1000);
        for (int i = 2; i < 100; i++) {
            manager.createSession();
        }
        assertThat(manager.size(), is(100));

        // Touch the first session at t=1_003_000 so it is no longer the LRU
        clock.addAndGet(1000);
        manager.getSession(firstSession.getSessionId());

        // Create one more at t=1_004_000 -- should evict the second session (least recently used), not the first
        clock.addAndGet(1000);
        manager.createSession();
        assertThat(manager.size(), is(100));
        assertThat("first session should survive because it was touched", manager.isValidSession(firstSession.getSessionId()), is(true));
        assertThat("second session should be evicted as LRU", manager.isValidSession(secondSession.getSessionId()), is(false));
    }
}
