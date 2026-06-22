package org.mockserver.log.model;

import org.junit.Test;

import java.util.Date;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.Assert.fail;

/**
 * Concurrency test for {@link LogEntry#LOG_DATE_FORMAT}.
 * <p>
 * The previous implementation was a single shared {@code SimpleDateFormat}, which is NOT
 * thread-safe: formatting it from multiple threads at once corrupts its internal {@code Calendar},
 * so a thread can read another thread's value (garbled timestamp) or hit an intermittent
 * {@link ArrayIndexOutOfBoundsException}.
 * <p>
 * Each thread here formats its OWN distinct instant in a tight loop and asserts it always gets its
 * own correct value back. Run against the old shared {@code SimpleDateFormat} this fails reliably
 * (tens of thousands of cross-contaminated results per run, verified standalone); against the
 * immutable {@link java.time.format.DateTimeFormatter}-backed replacement every result is correct
 * and no thread throws.
 */
public class LogEntryTimestampConcurrencyTest {

    private static final Pattern WELL_FORMED =
        Pattern.compile("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}");

    @Test
    public void shouldFormatConcurrentlyWithoutCrossThreadCorruption() throws Exception {
        int threads = 16;
        int iterationsPerThread = 20_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        CopyOnWriteArrayList<Throwable> failures = new CopyOnWriteArrayList<>();
        AtomicInteger wrong = new AtomicInteger();

        try {
            for (int t = 0; t < threads; t++) {
                final int threadIndex = t;
                pool.submit(() -> {
                    // a distinct instant per thread (one day apart) so any cross-thread leakage from
                    // a shared mutable formatter shows up as a value that does not equal this
                    // thread's reference
                    long epoch = 1718000000000L + (long) threadIndex * 86_400_000L;
                    String expected = LogEntry.LOG_DATE_FORMAT.format(new Date(epoch));
                    ready.countDown();
                    try {
                        start.await();
                        for (int i = 0; i < iterationsPerThread; i++) {
                            // exercise BOTH overloads against this thread's own instant
                            if (!expected.equals(LogEntry.LOG_DATE_FORMAT.format(new Date(epoch)))) {
                                wrong.incrementAndGet();
                            }
                            if (!expected.equals(LogEntry.LOG_DATE_FORMAT.format(epoch))) {
                                wrong.incrementAndGet();
                            }
                        }
                    } catch (Throwable throwable) {
                        // a corrupted SimpleDateFormat throws ArrayIndexOutOfBoundsException here
                        failures.add(throwable);
                    } finally {
                        done.countDown();
                    }
                });
            }

            ready.await(10, TimeUnit.SECONDS);
            // sanity: the reference values themselves must be well-formed
            assertThat(LogEntry.LOG_DATE_FORMAT.format(new Date(1718000000123L)), matchesPattern(WELL_FORMED));
            start.countDown();
            if (!done.await(30, TimeUnit.SECONDS)) {
                fail("timed out waiting for concurrent formatting to finish");
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat("concurrent formatting must not throw", failures, is(empty()));
        assertThat("each thread must always read back its own correct timestamp under concurrency",
            wrong.get(), is(0));
    }
}
