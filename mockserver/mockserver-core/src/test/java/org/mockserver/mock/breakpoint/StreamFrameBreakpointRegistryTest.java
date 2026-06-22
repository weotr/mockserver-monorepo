package org.mockserver.mock.breakpoint;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class StreamFrameBreakpointRegistryTest {

    @Before
    public void setup() {
        resetAllBreakpointSingletons();
    }

    @After
    public void cleanup() {
        resetAllBreakpointSingletons();
    }

    private void resetAllBreakpointSingletons() {
        BreakpointMatcherRegistry.getInstance().clear();
        StreamFrameBreakpointRegistry.getInstance().reset();
        BreakpointCallbackDispatcher.getInstance().reset();
        StreamFrameCallbackDispatcher.getInstance().reset();
    }

    private Configuration configWith(long timeout, int maxHeld) {
        return Configuration.configuration()
            .breakpointTimeoutMillis(timeout)
            .breakpointMaxHeld(maxHeld);
    }

    // --- Basic pause and resolve ---

    @Test
    public void shouldPauseFrameAndResolveContinue() throws Exception {
        Configuration config = configWith(30000, 50);
        PausedStreamFrame frame = StreamFrameBreakpointRegistry.getInstance()
            .pauseFrame("stream-1", "hello".getBytes(StandardCharsets.UTF_8), "GET", "/api/test", config);

        assertThat(frame, is(notNullValue()));
        assertThat(frame.getStreamId(), is("stream-1"));
        assertThat(frame.getSequenceNumber(), is(0));
        assertThat(frame.getCapturedBytes(), is("hello".getBytes(StandardCharsets.UTF_8)));
        assertThat(frame.getRequestMethod(), is("GET"));
        assertThat(frame.getRequestPath(), is("/api/test"));
        assertThat(StreamFrameBreakpointRegistry.getInstance().size(), is(1));

        boolean resolved = StreamFrameBreakpointRegistry.getInstance().resolveContinue(frame.getFrameId());
        assertThat(resolved, is(true));

        StreamFrameDecision decision = frame.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(decision.getAction(), is(StreamFrameDecision.Action.CONTINUE));
    }

    @Test
    public void shouldPauseFrameAndResolveModify() throws Exception {
        Configuration config = configWith(30000, 50);
        PausedStreamFrame frame = StreamFrameBreakpointRegistry.getInstance()
            .pauseFrame("stream-2", "original".getBytes(StandardCharsets.UTF_8), "POST", "/data", config);

        byte[] replacement = "modified body".getBytes(StandardCharsets.UTF_8);
        boolean resolved = StreamFrameBreakpointRegistry.getInstance().resolveModify(frame.getFrameId(), replacement);
        assertThat(resolved, is(true));

        StreamFrameDecision decision = frame.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(decision.getAction(), is(StreamFrameDecision.Action.MODIFY));
        assertThat(decision.getReplacementBody(), is(replacement));
    }

    @Test
    public void shouldPauseFrameAndResolveDrop() throws Exception {
        Configuration config = configWith(30000, 50);
        PausedStreamFrame frame = StreamFrameBreakpointRegistry.getInstance()
            .pauseFrame("stream-3", "drop me".getBytes(StandardCharsets.UTF_8), "GET", "/test", config);

        boolean resolved = StreamFrameBreakpointRegistry.getInstance().resolveDrop(frame.getFrameId());
        assertThat(resolved, is(true));

        StreamFrameDecision decision = frame.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(decision.getAction(), is(StreamFrameDecision.Action.DROP));
    }

    @Test
    public void shouldPauseFrameAndResolveInject() throws Exception {
        Configuration config = configWith(30000, 50);
        PausedStreamFrame frame = StreamFrameBreakpointRegistry.getInstance()
            .pauseFrame("stream-4", "original".getBytes(StandardCharsets.UTF_8), "GET", "/test", config);

        byte[] injected = "extra frame".getBytes(StandardCharsets.UTF_8);
        boolean resolved = StreamFrameBreakpointRegistry.getInstance().resolveInject(frame.getFrameId(), injected);
        assertThat(resolved, is(true));

        StreamFrameDecision decision = frame.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(decision.getAction(), is(StreamFrameDecision.Action.INJECT));
        assertThat(decision.getInjectedBody(), is(injected));
    }

    @Test
    public void shouldPauseFrameAndResolveClose() throws Exception {
        Configuration config = configWith(30000, 50);
        PausedStreamFrame frame = StreamFrameBreakpointRegistry.getInstance()
            .pauseFrame("stream-5", "close me".getBytes(StandardCharsets.UTF_8), "GET", "/test", config);

        boolean resolved = StreamFrameBreakpointRegistry.getInstance().resolveClose(frame.getFrameId());
        assertThat(resolved, is(true));

        StreamFrameDecision decision = frame.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(decision.getAction(), is(StreamFrameDecision.Action.CLOSE));
    }

    // --- Frame ordering enforcement ---

    @Test
    public void shouldEnforceFrameOrderingWithinStream() throws Exception {
        Configuration config = configWith(30000, 50);
        StreamFrameBreakpointRegistry registry = StreamFrameBreakpointRegistry.getInstance();

        PausedStreamFrame frame0 = registry.pauseFrame("stream-order", "frame0".getBytes(StandardCharsets.UTF_8), "GET", "/test", config);
        PausedStreamFrame frame1 = registry.pauseFrame("stream-order", "frame1".getBytes(StandardCharsets.UTF_8), "GET", "/test", config);
        PausedStreamFrame frame2 = registry.pauseFrame("stream-order", "frame2".getBytes(StandardCharsets.UTF_8), "GET", "/test", config);

        assertThat(frame0.getSequenceNumber(), is(0));
        assertThat(frame1.getSequenceNumber(), is(1));
        assertThat(frame2.getSequenceNumber(), is(2));
        assertThat(registry.size(), is(3));

        // Cannot resolve frame1 before frame0
        assertThat("frame1 should not be resolvable before frame0", registry.resolveContinue(frame1.getFrameId()), is(false));
        assertThat("frame2 should not be resolvable before frame0", registry.resolveContinue(frame2.getFrameId()), is(false));

        // Resolve frame0
        assertThat("frame0 should be resolvable", registry.resolveContinue(frame0.getFrameId()), is(true));
        // Wait for the whenComplete callback to advance the resumable counter
        frame0.getDecisionFuture().get(1, TimeUnit.SECONDS);
        Thread.sleep(200);

        // Now frame1 is the next resumable
        assertThat("frame2 should not be resolvable before frame1", registry.resolveContinue(frame2.getFrameId()), is(false));
        assertThat("frame1 should now be resolvable", registry.resolveContinue(frame1.getFrameId()), is(true));
        frame1.getDecisionFuture().get(1, TimeUnit.SECONDS);
        Thread.sleep(200);

        // Now frame2 is the next resumable
        assertThat("frame2 should now be resolvable", registry.resolveContinue(frame2.getFrameId()), is(true));
    }

    // --- Cap enforcement ---

    @Test
    public void shouldEnforceMaxHeldCap() {
        Configuration config = configWith(30000, 2);
        StreamFrameBreakpointRegistry registry = StreamFrameBreakpointRegistry.getInstance();

        PausedStreamFrame f1 = registry.pauseFrame("stream-cap", "f1".getBytes(StandardCharsets.UTF_8), "GET", "/test", config);
        PausedStreamFrame f2 = registry.pauseFrame("stream-cap", "f2".getBytes(StandardCharsets.UTF_8), "GET", "/test", config);
        PausedStreamFrame f3 = registry.pauseFrame("stream-cap", "f3".getBytes(StandardCharsets.UTF_8), "GET", "/test", config);

        assertThat("first should succeed", f1, is(notNullValue()));
        assertThat("second should succeed", f2, is(notNullValue()));
        assertThat("third should be rejected (cap reached)", f3, is(nullValue()));
        assertThat(registry.size(), is(2));
    }

    // --- Timeout auto-continue ---

    @Test
    public void shouldAutoContinueOnTimeout() throws Exception {
        Configuration config = configWith(200, 50);
        PausedStreamFrame frame = StreamFrameBreakpointRegistry.getInstance()
            .pauseFrame("stream-timeout", "data".getBytes(StandardCharsets.UTF_8), "GET", "/test", config);

        assertThat(frame, is(notNullValue()));

        StreamFrameDecision decision = frame.getDecisionFuture().get(2, TimeUnit.SECONDS);
        assertThat("should auto-continue on timeout", decision.getAction(), is(StreamFrameDecision.Action.CONTINUE));
    }

    // --- Stream eviction ---

    @Test
    public void shouldEvictAllFramesForStream() throws Exception {
        Configuration config = configWith(30000, 50);
        StreamFrameBreakpointRegistry registry = StreamFrameBreakpointRegistry.getInstance();

        PausedStreamFrame f1 = registry.pauseFrame("stream-evict", "f1".getBytes(StandardCharsets.UTF_8), "GET", "/test", config);
        PausedStreamFrame f2 = registry.pauseFrame("stream-evict", "f2".getBytes(StandardCharsets.UTF_8), "GET", "/test", config);
        PausedStreamFrame f3 = registry.pauseFrame("stream-evict", "f3".getBytes(StandardCharsets.UTF_8), "GET", "/test", config);

        assertThat(registry.size(), is(3));

        // Evict the stream (simulating channel close)
        registry.evictStream("stream-evict");

        // All frames should have been resolved with DROP (eviction uses DROP to
        // prevent out-of-order writes after LastHttpContent)
        StreamFrameDecision d1 = f1.getDecisionFuture().get(1, TimeUnit.SECONDS);
        StreamFrameDecision d2 = f2.getDecisionFuture().get(1, TimeUnit.SECONDS);
        StreamFrameDecision d3 = f3.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(d1.getAction(), is(StreamFrameDecision.Action.DROP));
        assertThat(d2.getAction(), is(StreamFrameDecision.Action.DROP));
        assertThat(d3.getAction(), is(StreamFrameDecision.Action.DROP));

        // Wait for cleanup callbacks
        Thread.sleep(200);
        assertThat("registry should be empty after eviction", registry.size(), is(0));
    }

    // --- Reset ---

    @Test
    public void shouldResetAndAutoContinueAllHeld() throws Exception {
        Configuration config = configWith(30000, 50);
        StreamFrameBreakpointRegistry registry = StreamFrameBreakpointRegistry.getInstance();

        PausedStreamFrame f1 = registry.pauseFrame("stream-r1", "f1".getBytes(StandardCharsets.UTF_8), "GET", "/a", config);
        PausedStreamFrame f2 = registry.pauseFrame("stream-r2", "f2".getBytes(StandardCharsets.UTF_8), "GET", "/b", config);

        registry.reset();

        assertThat(registry.size(), is(0));

        StreamFrameDecision d1 = f1.getDecisionFuture().get(1, TimeUnit.SECONDS);
        StreamFrameDecision d2 = f2.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(d1.getAction(), is(StreamFrameDecision.Action.CONTINUE));
        assertThat(d2.getAction(), is(StreamFrameDecision.Action.CONTINUE));
    }

    // --- Listing / query ---

    @Test
    public void shouldReturnSnapshotOfEntries() {
        Configuration config = configWith(30000, 50);
        StreamFrameBreakpointRegistry registry = StreamFrameBreakpointRegistry.getInstance();

        registry.pauseFrame("stream-list-a", "a".getBytes(StandardCharsets.UTF_8), "GET", "/a", config);
        registry.pauseFrame("stream-list-b", "b".getBytes(StandardCharsets.UTF_8), "POST", "/b", config);

        Map<String, PausedStreamFrame> entries = registry.entries();
        assertThat(entries.size(), is(2));
        assertThat(entries.containsKey("stream-list-a-frame-0"), is(true));
        assertThat(entries.containsKey("stream-list-b-frame-0"), is(true));
    }

    @Test
    public void shouldReturnFramesForStream() {
        Configuration config = configWith(30000, 50);
        StreamFrameBreakpointRegistry registry = StreamFrameBreakpointRegistry.getInstance();

        registry.pauseFrame("stream-query", "f1".getBytes(StandardCharsets.UTF_8), "GET", "/test", config);
        registry.pauseFrame("stream-query", "f2".getBytes(StandardCharsets.UTF_8), "GET", "/test", config);
        registry.pauseFrame("stream-other", "f3".getBytes(StandardCharsets.UTF_8), "GET", "/other", config);

        List<PausedStreamFrame> frames = registry.framesForStream("stream-query");
        assertThat(frames.size(), is(2));
        assertThat(frames.get(0).getSequenceNumber(), is(0));
        assertThat(frames.get(1).getSequenceNumber(), is(1));

        assertThat(registry.framesForStream("nonexistent").size(), is(0));
    }

    @Test
    public void shouldReturnActiveStreamIds() {
        Configuration config = configWith(30000, 50);
        StreamFrameBreakpointRegistry registry = StreamFrameBreakpointRegistry.getInstance();

        registry.pauseFrame("stream-ids-a", "a".getBytes(StandardCharsets.UTF_8), "GET", "/a", config);
        registry.pauseFrame("stream-ids-b", "b".getBytes(StandardCharsets.UTF_8), "GET", "/b", config);
        registry.pauseFrame("stream-ids-a", "a2".getBytes(StandardCharsets.UTF_8), "GET", "/a", config);

        Set<String> ids = registry.activeStreamIds();
        assertThat(ids, hasItems("stream-ids-a", "stream-ids-b"));
    }

    // --- Unknown frame id ---

    @Test
    public void shouldReturnFalseForUnknownFrameId() {
        StreamFrameBreakpointRegistry registry = StreamFrameBreakpointRegistry.getInstance();
        assertThat(registry.resolveContinue("unknown"), is(false));
        assertThat(registry.resolveModify("unknown", "x".getBytes(StandardCharsets.UTF_8)), is(false));
        assertThat(registry.resolveDrop("unknown"), is(false));
        assertThat(registry.resolveInject("unknown", "x".getBytes(StandardCharsets.UTF_8)), is(false));
        assertThat(registry.resolveClose("unknown"), is(false));
    }

    // --- Frame age ---

    @Test
    public void shouldReportAgeInPausedStreamFrame() throws Exception {
        Configuration config = configWith(30000, 50);
        PausedStreamFrame frame = StreamFrameBreakpointRegistry.getInstance()
            .pauseFrame("stream-age", "data".getBytes(StandardCharsets.UTF_8), "GET", "/test", config);
        Thread.sleep(200);
        assertThat("ageMillis should be positive", frame.ageMillis(), greaterThanOrEqualTo(40L));
    }

    // --- Cleanup after resolution ---

    @Test
    public void shouldRemoveFromRegistryAfterResolution() throws Exception {
        Configuration config = configWith(30000, 50);
        StreamFrameBreakpointRegistry registry = StreamFrameBreakpointRegistry.getInstance();

        PausedStreamFrame frame = registry.pauseFrame("stream-cleanup", "data".getBytes(StandardCharsets.UTF_8), "GET", "/test", config);
        assertThat(registry.size(), is(1));

        registry.resolveContinue(frame.getFrameId());

        // Wait for the whenComplete callback
        Thread.sleep(200);
        assertThat("should be removed after resolution", registry.size(), is(0));
    }

    // --- Multiple streams independently ---

    @Test
    public void shouldHandleMultipleStreamsIndependently() throws Exception {
        Configuration config = configWith(30000, 50);
        StreamFrameBreakpointRegistry registry = StreamFrameBreakpointRegistry.getInstance();

        PausedStreamFrame s1f0 = registry.pauseFrame("stream-A", "s1f0".getBytes(StandardCharsets.UTF_8), "GET", "/a", config);
        PausedStreamFrame s2f0 = registry.pauseFrame("stream-B", "s2f0".getBytes(StandardCharsets.UTF_8), "GET", "/b", config);

        // Can resolve stream-B's frame without resolving stream-A's (different streams)
        assertThat(registry.resolveContinue(s2f0.getFrameId()), is(true));

        StreamFrameDecision d2 = s2f0.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(d2.getAction(), is(StreamFrameDecision.Action.CONTINUE));

        // stream-A's frame is still held
        assertThat(s1f0.getDecisionFuture().isDone(), is(false));

        // Clean up
        registry.resolveContinue(s1f0.getFrameId());
    }

    // --- Non-blocking proof (mirrors BreakpointRegistryTest) ---

    @Test
    public void shouldNotBlockThreadsWhenMorePausedThanPoolSize() throws Exception {
        int poolSize = 3;
        int pausedCount = poolSize + 5;
        ExecutorService boundedPool = new ThreadPoolExecutor(
            poolSize, poolSize, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(1000),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );

        Configuration config = configWith(30000, pausedCount + 10);
        StreamFrameBreakpointRegistry registry = StreamFrameBreakpointRegistry.getInstance();

        // Pause N frames across different streams (so ordering doesn't block resolution)
        List<PausedStreamFrame> frames = new ArrayList<>();
        for (int i = 0; i < pausedCount; i++) {
            PausedStreamFrame f = registry.pauseFrame("nb-stream-" + i, ("frame" + i).getBytes(StandardCharsets.UTF_8), "GET", "/test/" + i, config);
            assertThat("frame " + i + " should be registered", f, is(notNullValue()));
            frames.add(f);
        }
        assertThat(registry.size(), is(pausedCount));

        // Verify bounded pool is NOT exhausted
        CountDownLatch canaryLatch = new CountDownLatch(1);
        boundedPool.submit(canaryLatch::countDown);
        assertThat("canary task should complete", canaryLatch.await(2, TimeUnit.SECONDS), is(true));

        // Chain async continuations
        AtomicInteger completedCount = new AtomicInteger(0);
        CountDownLatch allDone = new CountDownLatch(pausedCount);
        for (PausedStreamFrame f : frames) {
            f.getDecisionFuture().thenAcceptAsync(decision -> {
                completedCount.incrementAndGet();
                allDone.countDown();
            }, boundedPool);
        }

        // Resolve all: mix of actions
        for (int i = 0; i < pausedCount; i++) {
            String fid = frames.get(i).getFrameId();
            if (i == 0) {
                registry.resolveModify(fid, "mod".getBytes(StandardCharsets.UTF_8));
            } else if (i == 1) {
                registry.resolveDrop(fid);
            } else {
                registry.resolveContinue(fid);
            }
        }

        assertThat("all async continuations should complete", allDone.await(5, TimeUnit.SECONDS), is(true));
        assertThat(completedCount.get(), is(pausedCount));

        Thread.sleep(200);
        assertThat("registry should be empty", registry.size(), is(0));

        boundedPool.shutdown();
    }

    // --- Close evicts remaining frames for stream ---

    @Test
    public void shouldEvictRemainingFramesOnClose() throws Exception {
        Configuration config = configWith(30000, 50);
        StreamFrameBreakpointRegistry registry = StreamFrameBreakpointRegistry.getInstance();

        PausedStreamFrame f0 = registry.pauseFrame("stream-close-evict", "f0".getBytes(StandardCharsets.UTF_8), "GET", "/test", config);
        PausedStreamFrame f1 = registry.pauseFrame("stream-close-evict", "f1".getBytes(StandardCharsets.UTF_8), "GET", "/test", config);
        PausedStreamFrame f2 = registry.pauseFrame("stream-close-evict", "f2".getBytes(StandardCharsets.UTF_8), "GET", "/test", config);

        assertThat(registry.size(), is(3));

        // Close at frame0 — should evict f1 and f2
        boolean resolved = registry.resolveClose(f0.getFrameId());
        assertThat(resolved, is(true));

        StreamFrameDecision d0 = f0.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(d0.getAction(), is(StreamFrameDecision.Action.CLOSE));

        // f1 and f2 should have been dropped by eviction (not written after stream close)
        StreamFrameDecision d1 = f1.getDecisionFuture().get(1, TimeUnit.SECONDS);
        StreamFrameDecision d2 = f2.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(d1.getAction(), is(StreamFrameDecision.Action.DROP));
        assertThat(d2.getAction(), is(StreamFrameDecision.Action.DROP));

        Thread.sleep(200);
        assertThat("registry should be empty after close+evict", registry.size(), is(0));
    }
}
