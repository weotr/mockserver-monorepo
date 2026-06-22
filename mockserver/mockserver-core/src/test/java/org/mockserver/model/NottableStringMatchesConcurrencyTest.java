package org.mockserver.model;

import org.junit.Test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.fail;
import static org.mockserver.model.NottableString.string;

/**
 * Concurrency test for {@link NottableString#matches(String, boolean)}.
 * <p>
 * The lazily-compiled {@code pattern} / {@code caseSensitivePattern} memo fields are now
 * {@code volatile}. Without that, a fresh {@link NottableString} matched concurrently from many
 * threads risked unsafe publication of a half-constructed/never-visible {@link java.util.regex.Pattern}
 * (a reader could observe a non-null reference whose internals were not yet visible, or never see the
 * write at all). This test races many threads through {@code matches} on a FRESH NottableString and
 * asserts every result is correct and no thread threw, which would fail under the previous
 * non-volatile, racing publication.
 */
public class NottableStringMatchesConcurrencyTest {

    @Test
    public void shouldMatchConcurrentlyOnFreshNottableStringWithoutErrorOrWrongResult() throws Exception {
        int rounds = 200;
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < rounds; round++) {
                // a FRESH NottableString per round so its pattern memo starts un-compiled and is
                // first compiled under contention (the publication race the volatile fix closes)
                final NottableString value = string("[a-z]{3}-[0-9]+");
                final String matching = "abc-123";
                final String notMatching = "ABC";

                CountDownLatch ready = new CountDownLatch(threads);
                CountDownLatch start = new CountDownLatch(1);
                CopyOnWriteArrayList<Throwable> failures = new CopyOnWriteArrayList<>();
                AtomicInteger wrong = new AtomicInteger();
                CountDownLatch done = new CountDownLatch(threads);

                for (int t = 0; t < threads; t++) {
                    final boolean caseSensitive = (t % 2 == 0);
                    pool.submit(() -> {
                        ready.countDown();
                        try {
                            start.await();
                            // case-insensitive default: "abc-123" matches, "ABC" does not
                            // case-sensitive: "abc-123" matches, "ABC" does not (lowercase regex)
                            if (!value.matches(matching, caseSensitive)) {
                                wrong.incrementAndGet();
                            }
                            if (value.matches(notMatching, caseSensitive)) {
                                wrong.incrementAndGet();
                            }
                        } catch (Throwable throwable) {
                            failures.add(throwable);
                        } finally {
                            done.countDown();
                        }
                    });
                }

                ready.await(10, TimeUnit.SECONDS);
                start.countDown();
                if (!done.await(30, TimeUnit.SECONDS)) {
                    fail("timed out waiting for concurrent matches() in round " + round);
                }

                assertThat("matches() must not throw under concurrency", failures, is(empty()));
                assertThat("matches() must return the correct result under concurrency",
                    wrong.get(), is(0));
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
