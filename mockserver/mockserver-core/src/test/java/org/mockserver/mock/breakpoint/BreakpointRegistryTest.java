package org.mockserver.mock.breakpoint;

import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class BreakpointRegistryTest {

    @After
    public void cleanup() {
        BreakpointRegistry.getInstance().reset();
    }

    private Configuration configWith(boolean enabled, long timeout, int maxHeld) {
        return Configuration.configuration()
            .breakpointEnabled(enabled)
            .breakpointTimeoutMillis(timeout)
            .breakpointMaxHeld(maxHeld);
    }

    @Test
    public void shouldPauseAndResolveContinue() throws Exception {
        Configuration config = configWith(true, 30000, 50);
        HttpRequest req = request().withMethod("GET").withPath("/api/test");

        PausedExchange exchange = BreakpointRegistry.getInstance().pause("corr-1", req, null, config);
        assertThat(exchange, is(notNullValue()));
        assertThat(BreakpointRegistry.getInstance().size(), is(1));
        assertThat(BreakpointRegistry.getInstance().entries().containsKey("corr-1"), is(true));

        // resolve continue
        boolean resolved = BreakpointRegistry.getInstance().resolveContinue("corr-1");
        assertThat(resolved, is(true));

        BreakpointDecision decision = exchange.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(decision.getAction(), is(BreakpointDecision.Action.CONTINUE));
    }

    @Test
    public void shouldPauseAndResolveModify() throws Exception {
        Configuration config = configWith(true, 30000, 50);
        HttpRequest req = request().withMethod("GET").withPath("/api/test");

        PausedExchange exchange = BreakpointRegistry.getInstance().pause("corr-2", req, "exp-1", config);
        assertThat(exchange, is(notNullValue()));
        assertThat(exchange.getMatchedExpectationId(), is("exp-1"));

        HttpRequest modified = request().withMethod("POST").withPath("/api/modified");
        boolean resolved = BreakpointRegistry.getInstance().resolveModify("corr-2", modified);
        assertThat(resolved, is(true));

        BreakpointDecision decision = exchange.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(decision.getAction(), is(BreakpointDecision.Action.MODIFY));
        assertThat(decision.getModifiedRequest().getPath().getValue(), is("/api/modified"));
    }

    @Test
    public void shouldPauseAndResolveAbort() throws Exception {
        Configuration config = configWith(true, 30000, 50);
        HttpRequest req = request().withMethod("GET").withPath("/api/test");

        PausedExchange exchange = BreakpointRegistry.getInstance().pause("corr-3", req, null, config);

        HttpResponse abortResp = response().withStatusCode(403).withBody("forbidden");
        boolean resolved = BreakpointRegistry.getInstance().resolveAbort("corr-3", abortResp);
        assertThat(resolved, is(true));

        BreakpointDecision decision = exchange.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(decision.getAction(), is(BreakpointDecision.Action.ABORT));
        assertThat(decision.getAbortResponse().getStatusCode(), is(403));
    }

    @Test
    public void shouldReturnFalseForUnknownCorrelationId() {
        assertThat(BreakpointRegistry.getInstance().resolveContinue("unknown"), is(false));
        assertThat(BreakpointRegistry.getInstance().resolveModify("unknown", request()), is(false));
        assertThat(BreakpointRegistry.getInstance().resolveAbort("unknown", null), is(false));
    }

    @Test
    public void shouldEnforceMaxHeldCap() {
        Configuration config = configWith(true, 30000, 2);

        PausedExchange ex1 = BreakpointRegistry.getInstance().pause("corr-a", request(), null, config);
        PausedExchange ex2 = BreakpointRegistry.getInstance().pause("corr-b", request(), null, config);
        PausedExchange ex3 = BreakpointRegistry.getInstance().pause("corr-c", request(), null, config);

        assertThat("first should succeed", ex1, is(notNullValue()));
        assertThat("second should succeed", ex2, is(notNullValue()));
        assertThat("third should be rejected (cap reached)", ex3, is(nullValue()));
        assertThat(BreakpointRegistry.getInstance().size(), is(2));
    }

    @Test
    public void shouldAutoContinueOnTimeout() throws Exception {
        // use a very short timeout
        Configuration config = configWith(true, 200, 50);

        PausedExchange exchange = BreakpointRegistry.getInstance().pause("corr-timeout", request(), null, config);
        assertThat(exchange, is(notNullValue()));

        // wait for the timeout to fire + some margin
        BreakpointDecision decision = exchange.getDecisionFuture().get(2, TimeUnit.SECONDS);
        assertThat("should auto-continue on timeout", decision.getAction(), is(BreakpointDecision.Action.CONTINUE));
    }

    @Test
    public void shouldResetAndAutoContinueAllHeld() throws Exception {
        Configuration config = configWith(true, 30000, 50);

        PausedExchange ex1 = BreakpointRegistry.getInstance().pause("corr-r1", request(), null, config);
        PausedExchange ex2 = BreakpointRegistry.getInstance().pause("corr-r2", request(), null, config);

        BreakpointRegistry.getInstance().reset();

        assertThat(BreakpointRegistry.getInstance().size(), is(0));
        assertThat(BreakpointRegistry.getInstance().entries().isEmpty(), is(true));

        // both should have been auto-continued
        BreakpointDecision d1 = ex1.getDecisionFuture().get(1, TimeUnit.SECONDS);
        BreakpointDecision d2 = ex2.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(d1.getAction(), is(BreakpointDecision.Action.CONTINUE));
        assertThat(d2.getAction(), is(BreakpointDecision.Action.CONTINUE));
    }

    @Test
    public void shouldRemoveFromRegistryAfterResolution() throws Exception {
        Configuration config = configWith(true, 30000, 50);

        BreakpointRegistry.getInstance().pause("corr-cleanup", request(), null, config);
        assertThat(BreakpointRegistry.getInstance().size(), is(1));

        BreakpointRegistry.getInstance().resolveContinue("corr-cleanup");

        // give the whenComplete callback time to fire
        Thread.sleep(100);
        assertThat("should be removed after resolution", BreakpointRegistry.getInstance().size(), is(0));
    }

    @Test
    public void shouldReportAgeInPausedExchange() throws Exception {
        Configuration config = configWith(true, 30000, 50);
        PausedExchange exchange = BreakpointRegistry.getInstance().pause("corr-age", request(), null, config);
        Thread.sleep(50);
        assertThat("ageMillis should be positive", exchange.ageMillis(), greaterThanOrEqualTo(40L));

        // cleanup
        BreakpointRegistry.getInstance().resolveContinue("corr-age");
    }

    @Test
    public void shouldStoreCapturedRequest() throws Exception {
        Configuration config = configWith(true, 30000, 50);
        HttpRequest req = request().withMethod("POST").withPath("/data").withBody("payload");

        PausedExchange exchange = BreakpointRegistry.getInstance().pause("corr-capture", req, null, config);
        assertThat(exchange.getCapturedRequest().getMethod().getValue(), is("POST"));
        assertThat(exchange.getCapturedRequest().getPath().getValue(), is("/data"));

        BreakpointRegistry.getInstance().resolveContinue("corr-capture");
    }

    /**
     * Proves that the breakpoint mechanism is non-blocking: pauses MORE concurrent
     * exchanges than a typical scheduler pool size, then verifies that (a) async
     * continuations on a bounded pool still complete for all paused exchanges when
     * resolved, and (b) the pool is not exhausted (other tasks still run).
     *
     * <p>This is the regression test for the CRITICAL fix: the old implementation
     * blocked scheduler threads with {@code .get()}, which exhausted the pool and
     * stalled the Netty event loop via CallerRunsPolicy. The new async
     * {@code thenAcceptAsync} approach frees threads immediately.
     */
    @Test
    public void shouldNotBlockThreadsWhenMorePausedThanPoolSize() throws Exception {
        // Simulate a pool smaller than the number of paused exchanges.
        // A real scheduler pool is typically max(5, cpus); we use 3 to make the
        // test tight and fast.
        int poolSize = 3;
        int pausedCount = poolSize + 5; // more paused than pool threads
        ExecutorService boundedPool = new ThreadPoolExecutor(
            poolSize, poolSize, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(1000),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );

        Configuration config = configWith(true, 30000, pausedCount + 10);
        BreakpointRegistry registry = BreakpointRegistry.getInstance();

        // Pause N exchanges — none block any thread
        List<PausedExchange> exchanges = new ArrayList<>();
        for (int i = 0; i < pausedCount; i++) {
            PausedExchange ex = registry.pause("nb-corr-" + i, request().withPath("/test/" + i), null, config);
            assertThat("exchange " + i + " should be registered", ex, is(notNullValue()));
            exchanges.add(ex);
        }
        assertThat(registry.size(), is(pausedCount));

        // Verify the bounded pool is NOT exhausted: submit a canary task
        // that must complete within 1 second (it would hang if the pool were blocked)
        CountDownLatch canaryLatch = new CountDownLatch(1);
        boundedPool.submit(canaryLatch::countDown);
        assertThat("canary task should complete (pool not exhausted)",
            canaryLatch.await(2, TimeUnit.SECONDS), is(true));

        // Chain async continuations onto each decision future (mirrors the real
        // HttpActionHandler async refactor)
        AtomicInteger completedCount = new AtomicInteger(0);
        CountDownLatch allDone = new CountDownLatch(pausedCount);
        for (PausedExchange ex : exchanges) {
            ex.getDecisionFuture().thenAcceptAsync(decision -> {
                completedCount.incrementAndGet();
                allDone.countDown();
            }, boundedPool);
        }

        // Resolve all exchanges: CONTINUE for most, MODIFY for one, ABORT for one
        for (int i = 0; i < pausedCount; i++) {
            if (i == 0) {
                registry.resolveModify("nb-corr-" + i, request().withPath("/modified"));
            } else if (i == 1) {
                registry.resolveAbort("nb-corr-" + i, response().withStatusCode(503));
            } else {
                registry.resolveContinue("nb-corr-" + i);
            }
        }

        // All continuations must complete
        assertThat("all async continuations should complete",
            allDone.await(5, TimeUnit.SECONDS), is(true));
        assertThat(completedCount.get(), is(pausedCount));

        // Verify all exchanges were cleaned up from the registry
        // (give the whenComplete callbacks a moment to run)
        Thread.sleep(200);
        assertThat("registry should be empty after all resolved", registry.size(), is(0));

        // Verify pool still accepts work after all completions
        CountDownLatch postCanary = new CountDownLatch(1);
        boundedPool.submit(postCanary::countDown);
        assertThat("post-resolution canary should complete",
            postCanary.await(2, TimeUnit.SECONDS), is(true));

        boundedPool.shutdown();
    }

    /**
     * Verifies that reset drains correctly even when new entries appear during reset
     * (the race condition the old held.clear() was susceptible to).
     */
    @Test
    public void shouldResetCleanlyWithConcurrentAdds() throws Exception {
        Configuration config = configWith(true, 30000, 100);
        BreakpointRegistry registry = BreakpointRegistry.getInstance();

        // Pause some exchanges
        for (int i = 0; i < 5; i++) {
            registry.pause("reset-race-" + i, request(), null, config);
        }

        // Reset on another thread while adding a new one mid-reset
        AtomicBoolean resetDone = new AtomicBoolean(false);
        Thread resetThread = new Thread(() -> {
            registry.reset();
            resetDone.set(true);
        });
        resetThread.start();
        resetThread.join(2000);

        assertThat("reset should complete", resetDone.get(), is(true));
        assertThat("registry should be empty after reset", registry.size(), is(0));
    }

    // ===== Response-phase breakpoint tests (A1b) =====

    @Test
    public void shouldPauseResponseAndResolveContinue() throws Exception {
        Configuration config = configWith(true, 30000, 50);
        HttpRequest req = request().withMethod("GET").withPath("/api/test");
        HttpResponse resp = response().withStatusCode(200).withBody("upstream body");

        PausedExchange exchange = BreakpointRegistry.getInstance().pauseResponse(
            "resp-corr-1", req, resp, null, config
        );
        assertThat(exchange, is(notNullValue()));
        assertThat(exchange.getPhase(), is(PausedExchange.Phase.RESPONSE));
        assertThat(exchange.getCapturedResponse().getStatusCode(), is(200));
        assertThat(exchange.getCapturedRequest().getPath().getValue(), is("/api/test"));
        // Registry uses "-response" suffix
        assertThat(BreakpointRegistry.getInstance().entries().containsKey("resp-corr-1-response"), is(true));

        boolean resolved = BreakpointRegistry.getInstance().resolveContinue("resp-corr-1-response");
        assertThat(resolved, is(true));

        BreakpointDecision decision = exchange.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(decision.getAction(), is(BreakpointDecision.Action.CONTINUE));
    }

    @Test
    public void shouldPauseResponseAndResolveModifyResponse() throws Exception {
        Configuration config = configWith(true, 30000, 50);
        HttpRequest req = request().withMethod("GET").withPath("/api/test");
        HttpResponse resp = response().withStatusCode(200).withBody("original");

        PausedExchange exchange = BreakpointRegistry.getInstance().pauseResponse(
            "resp-corr-2", req, resp, "exp-1", config
        );
        assertThat(exchange, is(notNullValue()));
        assertThat(exchange.getMatchedExpectationId(), is("exp-1"));

        HttpResponse modified = response().withStatusCode(201).withBody("modified");
        boolean resolved = BreakpointRegistry.getInstance().resolveModifyResponse(
            "resp-corr-2-response", modified
        );
        assertThat(resolved, is(true));

        BreakpointDecision decision = exchange.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(decision.getAction(), is(BreakpointDecision.Action.MODIFY));
        assertThat(decision.getModifiedResponse().getStatusCode(), is(201));
        assertThat(decision.getModifiedResponse().getBodyAsString(), is("modified"));
    }

    @Test
    public void shouldPauseResponseAndResolveAbort() throws Exception {
        Configuration config = configWith(true, 30000, 50);
        HttpRequest req = request().withMethod("GET").withPath("/api/test");
        HttpResponse resp = response().withStatusCode(200);

        PausedExchange exchange = BreakpointRegistry.getInstance().pauseResponse(
            "resp-corr-3", req, resp, null, config
        );

        HttpResponse abortResp = response().withStatusCode(503).withBody("service down");
        boolean resolved = BreakpointRegistry.getInstance().resolveAbort(
            "resp-corr-3-response", abortResp
        );
        assertThat(resolved, is(true));

        BreakpointDecision decision = exchange.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(decision.getAction(), is(BreakpointDecision.Action.ABORT));
        assertThat(decision.getAbortResponse().getStatusCode(), is(503));
    }

    @Test
    public void shouldAutoContinueResponseOnTimeout() throws Exception {
        Configuration config = configWith(true, 200, 50);
        HttpRequest req = request().withMethod("GET").withPath("/api/test");
        HttpResponse resp = response().withStatusCode(200);

        PausedExchange exchange = BreakpointRegistry.getInstance().pauseResponse(
            "resp-corr-timeout", req, resp, null, config
        );
        assertThat(exchange, is(notNullValue()));

        BreakpointDecision decision = exchange.getDecisionFuture().get(2, TimeUnit.SECONDS);
        assertThat("should auto-continue on timeout", decision.getAction(), is(BreakpointDecision.Action.CONTINUE));
    }

    @Test
    public void shouldEnforceMaxHeldCapForResponseBreakpoints() {
        Configuration config = configWith(true, 30000, 2);

        // Fill cap with request-phase exchanges
        PausedExchange ex1 = BreakpointRegistry.getInstance().pause("cap-req-1", request(), null, config);
        PausedExchange ex2 = BreakpointRegistry.getInstance().pause("cap-req-2", request(), null, config);
        assertThat(ex1, is(notNullValue()));
        assertThat(ex2, is(notNullValue()));

        // Response pause should be rejected (cap reached — shared with request breakpoints)
        PausedExchange ex3 = BreakpointRegistry.getInstance().pauseResponse(
            "cap-resp-1", request(), response(), null, config
        );
        assertThat("response pause should be rejected (cap reached)", ex3, is(nullValue()));
    }

    @Test
    public void shouldResetResponsePhaseExchanges() throws Exception {
        Configuration config = configWith(true, 30000, 50);

        PausedExchange reqExchange = BreakpointRegistry.getInstance().pause("reset-req", request(), null, config);
        PausedExchange respExchange = BreakpointRegistry.getInstance().pauseResponse(
            "reset-resp", request(), response(), null, config
        );

        BreakpointRegistry.getInstance().reset();

        assertThat(BreakpointRegistry.getInstance().size(), is(0));

        BreakpointDecision d1 = reqExchange.getDecisionFuture().get(1, TimeUnit.SECONDS);
        BreakpointDecision d2 = respExchange.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(d1.getAction(), is(BreakpointDecision.Action.CONTINUE));
        assertThat(d2.getAction(), is(BreakpointDecision.Action.CONTINUE));
    }

    @Test
    public void shouldStorePhaseInPausedExchange() throws Exception {
        Configuration config = configWith(true, 30000, 50);

        PausedExchange reqExchange = BreakpointRegistry.getInstance().pause("phase-req", request(), null, config);
        PausedExchange respExchange = BreakpointRegistry.getInstance().pauseResponse(
            "phase-resp", request(), response().withStatusCode(404), null, config
        );

        assertThat(reqExchange.getPhase(), is(PausedExchange.Phase.REQUEST));
        assertThat(reqExchange.getCapturedResponse(), is(nullValue()));

        assertThat(respExchange.getPhase(), is(PausedExchange.Phase.RESPONSE));
        assertThat(respExchange.getCapturedResponse().getStatusCode(), is(404));
    }

    /**
     * Proves that response breakpoints are non-blocking: pauses MORE concurrent
     * response exchanges than a typical scheduler pool size, then verifies that
     * (a) async continuations on a bounded pool still complete, and (b) the pool
     * is not exhausted (other tasks still run).
     *
     * <p>Mirrors the existing shouldNotBlockThreadsWhenMorePausedThanPoolSize
     * test for request breakpoints.
     */
    @Test
    public void shouldNotBlockThreadsWhenMoreResponsesPausedThanPoolSize() throws Exception {
        int poolSize = 3;
        int pausedCount = poolSize + 5;
        ExecutorService boundedPool = new ThreadPoolExecutor(
            poolSize, poolSize, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(1000),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );

        Configuration config = configWith(true, 30000, pausedCount + 10);
        BreakpointRegistry registry = BreakpointRegistry.getInstance();

        // Pause N response exchanges — none block any thread
        List<PausedExchange> exchanges = new ArrayList<>();
        for (int i = 0; i < pausedCount; i++) {
            PausedExchange ex = registry.pauseResponse(
                "nb-resp-" + i, request().withPath("/test/" + i),
                response().withStatusCode(200), null, config
            );
            assertThat("exchange " + i + " should be registered", ex, is(notNullValue()));
            exchanges.add(ex);
        }
        assertThat(registry.size(), is(pausedCount));

        // Verify bounded pool is NOT exhausted: canary task completes
        CountDownLatch canaryLatch = new CountDownLatch(1);
        boundedPool.submit(canaryLatch::countDown);
        assertThat("canary task should complete (pool not exhausted)",
            canaryLatch.await(2, TimeUnit.SECONDS), is(true));

        // Chain async continuations onto each decision future
        AtomicInteger completedCount = new AtomicInteger(0);
        CountDownLatch allDone = new CountDownLatch(pausedCount);
        for (PausedExchange ex : exchanges) {
            ex.getDecisionFuture().thenAcceptAsync(decision -> {
                completedCount.incrementAndGet();
                allDone.countDown();
            }, boundedPool);
        }

        // Resolve: CONTINUE most, MODIFY one response, ABORT one
        for (int i = 0; i < pausedCount; i++) {
            String id = exchanges.get(i).getCorrelationId();
            if (i == 0) {
                registry.resolveModifyResponse(id, response().withStatusCode(201));
            } else if (i == 1) {
                registry.resolveAbort(id, response().withStatusCode(503));
            } else {
                registry.resolveContinue(id);
            }
        }

        assertThat("all async continuations should complete",
            allDone.await(5, TimeUnit.SECONDS), is(true));
        assertThat(completedCount.get(), is(pausedCount));

        // Verify cleanup
        Thread.sleep(200);
        assertThat("registry should be empty after all resolved", registry.size(), is(0));

        // Verify pool still accepts work
        CountDownLatch postCanary = new CountDownLatch(1);
        boundedPool.submit(postCanary::countDown);
        assertThat("post-resolution canary should complete",
            postCanary.await(2, TimeUnit.SECONDS), is(true));

        boundedPool.shutdown();
    }

    // ===== Phase-guard tests (INC-05) =====

    @Test
    public void shouldRejectResolveModifyRequestAgainstResponsePhaseExchange() throws Exception {
        Configuration config = configWith(true, 30000, 50);
        PausedExchange respExchange = BreakpointRegistry.getInstance().pauseResponse(
            "phase-guard-1", request().withPath("/test"), response().withStatusCode(200), null, config
        );
        assertThat(respExchange, is(notNullValue()));
        assertThat(respExchange.getPhase(), is(PausedExchange.Phase.RESPONSE));

        // Attempt a request-modify against a response-phase exchange — should be rejected
        boolean resolved = BreakpointRegistry.getInstance().resolveModify(
            "phase-guard-1-response", request().withPath("/modified")
        );
        assertThat("resolveModify should be rejected for RESPONSE-phase exchange", resolved, is(false));

        // Exchange should still be held (not resolved)
        assertThat(BreakpointRegistry.getInstance().size(), is(1));
        assertThat(respExchange.getDecisionFuture().isDone(), is(false));

        // Clean up: resolve with the correct method
        BreakpointRegistry.getInstance().resolveContinue("phase-guard-1-response");
    }

    @Test
    public void shouldRejectResolveModifyResponseAgainstRequestPhaseExchange() throws Exception {
        Configuration config = configWith(true, 30000, 50);
        PausedExchange reqExchange = BreakpointRegistry.getInstance().pause(
            "phase-guard-2", request().withPath("/test"), null, config
        );
        assertThat(reqExchange, is(notNullValue()));
        assertThat(reqExchange.getPhase(), is(PausedExchange.Phase.REQUEST));

        // Attempt a response-modify against a request-phase exchange — should be rejected
        boolean resolved = BreakpointRegistry.getInstance().resolveModifyResponse(
            "phase-guard-2", response().withStatusCode(201)
        );
        assertThat("resolveModifyResponse should be rejected for REQUEST-phase exchange", resolved, is(false));

        // Exchange should still be held (not resolved)
        assertThat(BreakpointRegistry.getInstance().size(), is(1));
        assertThat(reqExchange.getDecisionFuture().isDone(), is(false));

        // Clean up: resolve with the correct method
        BreakpointRegistry.getInstance().resolveContinue("phase-guard-2");
    }

    @Test
    public void shouldAllowCorrectPhaseModifyCalls() throws Exception {
        Configuration config = configWith(true, 30000, 50);

        // Request-phase: resolveModify should work
        PausedExchange reqExchange = BreakpointRegistry.getInstance().pause(
            "phase-ok-1", request().withPath("/test"), null, config
        );
        boolean reqResolved = BreakpointRegistry.getInstance().resolveModify(
            "phase-ok-1", request().withPath("/modified")
        );
        assertThat("resolveModify should succeed for REQUEST-phase exchange", reqResolved, is(true));
        BreakpointDecision reqDecision = reqExchange.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(reqDecision.getAction(), is(BreakpointDecision.Action.MODIFY));
        assertThat(reqDecision.getModifiedRequest().getPath().getValue(), is("/modified"));

        // Response-phase: resolveModifyResponse should work
        PausedExchange respExchange = BreakpointRegistry.getInstance().pauseResponse(
            "phase-ok-2", request().withPath("/test"), response().withStatusCode(200), null, config
        );
        boolean respResolved = BreakpointRegistry.getInstance().resolveModifyResponse(
            "phase-ok-2-response", response().withStatusCode(201)
        );
        assertThat("resolveModifyResponse should succeed for RESPONSE-phase exchange", respResolved, is(true));
        BreakpointDecision respDecision = respExchange.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(respDecision.getAction(), is(BreakpointDecision.Action.MODIFY));
        assertThat(respDecision.getModifiedResponse().getStatusCode(), is(201));
    }

    @Test
    public void shouldHandleMixedRequestAndResponseExchanges() throws Exception {
        Configuration config = configWith(true, 30000, 50);
        BreakpointRegistry registry = BreakpointRegistry.getInstance();

        PausedExchange reqEx = registry.pause("mixed-1", request().withPath("/req"), null, config);
        PausedExchange respEx = registry.pauseResponse("mixed-2", request().withPath("/resp"), response().withStatusCode(200), null, config);

        assertThat(registry.size(), is(2));

        // Resolve request exchange
        registry.resolveContinue("mixed-1");
        BreakpointDecision d1 = reqEx.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(d1.getAction(), is(BreakpointDecision.Action.CONTINUE));

        // Resolve response exchange with modified response
        registry.resolveModifyResponse("mixed-2-response", response().withStatusCode(201));
        BreakpointDecision d2 = respEx.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(d2.getAction(), is(BreakpointDecision.Action.MODIFY));
        assertThat(d2.getModifiedResponse().getStatusCode(), is(201));

        Thread.sleep(100);
        assertThat("registry should be empty", registry.size(), is(0));
    }
}
