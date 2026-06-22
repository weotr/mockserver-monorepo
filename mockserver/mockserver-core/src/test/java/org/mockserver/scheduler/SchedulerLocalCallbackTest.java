package org.mockserver.scheduler;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * Unit guard for {@link Scheduler#scheduleLocalCallback} — the root fix that moves potentially-blocking
 * LOCAL object/class callbacks off the server worker event loop onto a dedicated, unbounded executor so
 * pool-on-by-default can never self-deadlock or starve under recursion.
 */
public class SchedulerLocalCallbackTest {

    private final MockServerLogger mockServerLogger = new MockServerLogger();

    /**
     * Asynchronous mode: the callback must NOT run inline on the calling thread; it must run on a
     * dedicated {@code MockServer-LocalCallback} pool thread (distinct from the calling thread and from
     * the bounded {@code MockServer-Scheduler} pool).
     */
    @Test(timeout = 10_000)
    public void shouldRunLocalCallbackOffTheCallingThreadInAsyncMode() throws Exception {
        Scheduler scheduler = new Scheduler(configuration(), mockServerLogger, false);
        try {
            Thread callingThread = Thread.currentThread();
            AtomicReference<Thread> ranOn = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);

            scheduler.scheduleLocalCallback(() -> {
                ranOn.set(Thread.currentThread());
                latch.countDown();
            }, false);

            assertThat("callback ran", latch.await(5, TimeUnit.SECONDS), is(true));
            assertThat(ranOn.get(), is(notNullValue()));
            assertThat("did not run inline on the calling (worker) thread", ranOn.get() == callingThread, is(false));
            assertThat("ran on the dedicated local-callback pool", ranOn.get().getName(), startsWith("MockServer-LocalCallback"));
        } finally {
            scheduler.shutdown();
        }
    }

    /**
     * Synchronous mode (WAR/servlet semantics): the callback must run INLINE on the calling thread so the
     * response is produced before the caller returns.
     */
    @Test(timeout = 10_000)
    public void shouldRunLocalCallbackInlineInSynchronousMode() {
        Scheduler scheduler = new Scheduler(configuration(), mockServerLogger, true);
        try {
            Thread callingThread = Thread.currentThread();
            AtomicReference<Thread> ranOn = new AtomicReference<>();

            scheduler.scheduleLocalCallback(() -> ranOn.set(Thread.currentThread()), false);

            // inline => already executed on the calling thread by the time the call returns
            assertThat("ran inline on the calling thread", ranOn.get() == callingThread, is(true));
        } finally {
            // synchronous mode has no executors; shutdown() is a guarded no-op in that mode
            scheduler.shutdown();
        }
    }

    /**
     * STARVATION / RECURSION guard at the unit level: with a deliberately tiny action-handler pool, an
     * OUTER local callback that blocks waiting for an INNER local callback must still complete — proving
     * local callbacks run on the unbounded local-callback pool, not the bounded scheduler pool (which
     * would deadlock: every outer thread blocked, no thread left to run the inner callback).
     */
    @Test(timeout = 15_000)
    public void shouldNotStarveWhenOuterLocalCallbackBlocksOnInnerLocalCallback() throws Exception {
        Configuration configuration = configuration().actionHandlerThreadCount(1);
        Scheduler scheduler = new Scheduler(configuration, mockServerLogger, false);
        try {
            int outerCount = 8; // far exceeds the size-1 scheduler pool
            CountDownLatch allDone = new CountDownLatch(outerCount);
            for (int i = 0; i < outerCount; i++) {
                scheduler.scheduleLocalCallback(() -> {
                    // OUTER callback blocks until its INNER callback (also dispatched as a local callback)
                    // has run — only possible without deadlock if the inner gets its own thread.
                    CountDownLatch innerDone = new CountDownLatch(1);
                    scheduler.scheduleLocalCallback(innerDone::countDown, false);
                    try {
                        if (innerDone.await(10, TimeUnit.SECONDS)) {
                            allDone.countDown();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }, false);
            }
            assertThat("all nested local callbacks completed without pool-exhaustion deadlock",
                allDone.await(12, TimeUnit.SECONDS), is(true));
        } finally {
            scheduler.shutdown();
        }
    }
}
