package org.mockserver.log;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.RequestDefinition;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.verify.Verification;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.fail;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.RECEIVED_REQUEST;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.verify.Verification.verification;
import static org.mockserver.verify.VerificationTimes.atLeast;

/**
 * Concurrency safety for {@link MockServerEventLog}: many writer threads call {@link
 * MockServerEventLog#add} while other threads concurrently {@code verify} / {@code retrieve} /
 * {@code clear}. Asserts there are no exceptions, no deadlock (bounded join timeouts), and a
 * consistent final state.
 *
 * <p>Determinism strategy:
 * <ul>
 *   <li><b>{@link #shouldRemainConsistentUnderConcurrentAddAndRead()}</b> floods only adds + reads
 *       (no clear), with {@code maxLogEntries} sized well above the total so nothing is evicted and
 *       the disruptor ring buffer (sized {@code nextPowerOfTwo(maxLogEntries)}) never drops; the
 *       final retrieved count is therefore exactly the number added.</li>
 *   <li><b>{@link #shouldNotDeadlockOrThrowUnderConcurrentAddVerifyRetrieveClear()}</b> interleaves
 *       clear (which removes data, so the exact final count is non-deterministic); it asserts only
 *       the safety properties (no Throwable, all threads join, the log is usable afterwards).</li>
 * </ul>
 *
 * <p>Each test owns an isolated {@link MockServerEventLog}; no JVM-global static state is mutated.
 */
public class MockServerEventLogConcurrencyTest {

    private static final int WRITER_THREADS = 8;
    private static final int READER_THREADS = 4;
    private static final int ADDS_PER_WRITER = 500;

    private Configuration configuration;
    private Scheduler scheduler;
    private MockServerEventLog mockServerEventLog;

    @Before
    public void setupTestFixture() {
        configuration = configuration();
        // size the log (and therefore the ring buffer) well above the total number of adds so no
        // entry is evicted and the disruptor never drops, making the final count deterministic
        configuration.maxLogEntries(200_000);
        scheduler = new Scheduler(configuration, new MockServerLogger());
        mockServerEventLog = new MockServerEventLog(configuration, new MockServerLogger(configuration, MockServerLogger.class), scheduler, true);
    }

    @After
    public void stop() {
        if (mockServerEventLog != null) {
            mockServerEventLog.stop();
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    private List<RequestDefinition> retrieveAllRequests() {
        CompletableFuture<List<RequestDefinition>> result = new CompletableFuture<>();
        mockServerEventLog.retrieveRequests((RequestDefinition) null, result::complete);
        try {
            return result.get(60, SECONDS);
        } catch (Exception e) {
            fail("retrieve failed/timed out (possible deadlock): " + e.getMessage());
            return null;
        }
    }

    private void verifyEventually(Verification verification) {
        CompletableFuture<String> result = new CompletableFuture<>();
        mockServerEventLog.verify(verification, result::complete);
        try {
            result.get(60, SECONDS);
        } catch (Exception e) {
            fail("verify failed/timed out (possible deadlock): " + e.getMessage());
        }
    }

    @Test
    public void shouldRemainConsistentUnderConcurrentAddAndRead() throws InterruptedException {
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicBoolean writersDone = new AtomicBoolean(false);
        final List<Thread> threads = new CopyOnWriteArrayList<>();

        // writers
        for (int w = 0; w < WRITER_THREADS; w++) {
            final int writerId = w;
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < ADDS_PER_WRITER; i++) {
                        mockServerEventLog.add(new LogEntry()
                            .setType(RECEIVED_REQUEST)
                            .setHttpRequest(request("/w" + writerId + "/" + i)));
                    }
                } catch (Throwable throwable) {
                    failures.add(throwable);
                }
            }, "writer-" + w);
            threads.add(t);
        }

        // readers - verify + retrieve concurrently with the writers (no clear, so they remove nothing)
        for (int r = 0; r < READER_THREADS; r++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    while (!writersDone.get()) {
                        retrieveAllRequests();
                        verifyEventually(verification().withRequest(request("/w0/0")).withTimes(atLeast(0)));
                    }
                } catch (Throwable throwable) {
                    failures.add(throwable);
                }
            }, "reader-" + r);
            threads.add(t);
        }

        for (Thread t : threads) {
            t.start();
        }
        start.countDown();

        // join writers with a bounded timeout to catch any deadlock
        for (Thread t : threads) {
            if (t.getName().startsWith("writer-")) {
                t.join(60_000);
                assertThat("writer thread did not finish (possible deadlock): " + t.getName(), t.isAlive(), is(false));
            }
        }
        writersDone.set(true);
        for (Thread t : threads) {
            if (t.getName().startsWith("reader-")) {
                t.join(60_000);
                assertThat("reader thread did not finish (possible deadlock): " + t.getName(), t.isAlive(), is(false));
            }
        }

        // no thread captured a Throwable
        assertThat("threads threw: " + failures, failures, is(empty()));

        // no events were dropped by the (over-sized) ring buffer
        assertThat("ring buffer dropped events — increase ringBufferSize in the test", mockServerEventLog.getDroppedLogEventCount(), is(0L));

        // exactly every added request is present (FIFO drain via the read guarantees all adds processed)
        int expected = WRITER_THREADS * ADDS_PER_WRITER;
        assertThat(retrieveAllRequests().size(), is(expected));
        assertThat(mockServerEventLog.size(), is(expected));
    }

    @Test
    public void shouldNotDeadlockOrThrowUnderConcurrentAddVerifyRetrieveClear() throws InterruptedException {
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicBoolean writersDone = new AtomicBoolean(false);
        final List<Thread> threads = new CopyOnWriteArrayList<>();

        for (int w = 0; w < WRITER_THREADS; w++) {
            final int writerId = w;
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < ADDS_PER_WRITER; i++) {
                        mockServerEventLog.add(new LogEntry()
                            .setType(RECEIVED_REQUEST)
                            .setHttpRequest(request("/w" + writerId + "/" + i)));
                    }
                } catch (Throwable throwable) {
                    failures.add(throwable);
                }
            }, "writer-" + w);
            threads.add(t);
        }

        for (int r = 0; r < READER_THREADS; r++) {
            final boolean clears = r % 2 == 0; // half the readers also clear
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    while (!writersDone.get()) {
                        retrieveAllRequests();
                        verifyEventually(verification().withRequest(request("/w0/0")).withTimes(atLeast(0)));
                        if (clears) {
                            // clear() is itself blocking (it waits on an internal future), so calling
                            // it concurrently with adds/reads exercises the clear-vs-add interleaving
                            mockServerEventLog.clear(request("/w1/1"));
                        }
                    }
                } catch (Throwable throwable) {
                    failures.add(throwable);
                }
            }, "reader-" + r);
            threads.add(t);
        }

        for (Thread t : threads) {
            t.start();
        }
        start.countDown();

        for (Thread t : threads) {
            if (t.getName().startsWith("writer-")) {
                t.join(60_000);
                assertThat("writer thread did not finish (possible deadlock): " + t.getName(), t.isAlive(), is(false));
            }
        }
        writersDone.set(true);
        for (Thread t : threads) {
            if (t.getName().startsWith("reader-")) {
                t.join(60_000);
                assertThat("reader thread did not finish (possible deadlock): " + t.getName(), t.isAlive(), is(false));
            }
        }

        // safety properties: no exceptions and the log is still usable after the storm
        assertThat("threads threw: " + failures, failures, is(empty()));
        // a final read must still complete (no deadlock left behind) and return a consistent snapshot
        assertThat(retrieveAllRequests(), is(notNullValue()));
    }
}
