package org.mockserver.matchers;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpRequest;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.core.Is.is;
import static org.mockserver.model.HttpRequest.request;

/**
 * Concurrency regression test for the shared {@link HttpRequestPropertiesMatcher} instance.
 * <p>
 * A single matcher instance is shared between the control plane (which rebuilds the compiled
 * criterion in {@code apply()} when an expectation is created or updated) and the data plane
 * (which reads the compiled criterion in {@code matches()} from many threads). Without a
 * consistent (non-torn) publication of the compiled criterion, a data-plane {@code matches()}
 * running concurrently with a control-plane {@code update()} of the SAME matcher can observe a
 * half-rebuilt matcher — e.g. a {@code methodMatcher} freshly rebuilt for the new criterion paired
 * with a {@code pathMatcher} still left over from the old one — and so return a result that is
 * impossible for either criterion in isolation (or throw a transient {@link NullPointerException}).
 * <p>
 * This test drives exactly that race. The two criteria and the fixed request are chosen so that the
 * request matches NEITHER criterion on its own:
 * <ul>
 *   <li>criterion A = {@code POST /alpha} — fails on path (request path is {@code /beta})</li>
 *   <li>criterion B = {@code PUT  /beta}  — fails on method (request method is {@code POST})</li>
 *   <li>fixed request = {@code POST /beta}</li>
 * </ul>
 * So a clean read can only ever return {@code false}. A {@code true} can be produced ONLY by a torn
 * read that pairs criterion A's {@code methodMatcher} (POST) with criterion B's {@code pathMatcher}
 * ({@code /beta}) — an impossible-for-either-criterion result, which is the bug's fingerprint. The
 * test therefore asserts {@code matches()} never returns {@code true} and never throws.
 */
public class HttpRequestPropertiesMatcherConcurrencyTest {

    private final Configuration configuration = Configuration.configuration();
    private final MockServerLogger mockServerLogger = new MockServerLogger(HttpRequestPropertiesMatcherConcurrencyTest.class);

    @Test
    public void matchesNeverObservesTornCriterionWhileUpdatedConcurrently() throws Exception {
        final Expectation criterionA = new Expectation(request().withMethod("POST").withPath("/alpha"));
        final Expectation criterionB = new Expectation(request().withMethod("PUT").withPath("/beta"));
        // matches neither A (wrong path) nor B (wrong method) — a clean read can only return false
        final HttpRequest fixedRequest = request().withMethod("POST").withPath("/beta");

        final HttpRequestPropertiesMatcher sharedMatcher = new HttpRequestPropertiesMatcher(configuration, mockServerLogger);
        sharedMatcher.update(criterionA);

        // sanity-check the pure-criterion premise before racing
        assertThat("criterion A must not match the fixed request", sharedMatcher.matches(null, fixedRequest), is(false));
        sharedMatcher.update(criterionB);
        assertThat("criterion B must not match the fixed request", sharedMatcher.matches(null, fixedRequest), is(false));

        final int readerThreads = 8;
        final long durationMillis = 3_000L;
        final AtomicBoolean running = new AtomicBoolean(true);
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        final AtomicBoolean sawImpossibleTrue = new AtomicBoolean(false);
        final AtomicLong matchInvocations = new AtomicLong();
        final AtomicLong updateInvocations = new AtomicLong();

        final ExecutorService pool = Executors.newFixedThreadPool(readerThreads + 1);
        final CountDownLatch ready = new CountDownLatch(readerThreads + 1);
        final CountDownLatch go = new CountDownLatch(1);

        // WRITER: flip the shared matcher between A and B as fast as possible
        pool.submit(() -> {
            ready.countDown();
            try {
                go.await();
                boolean useA = true;
                while (running.get()) {
                    sharedMatcher.update(useA ? criterionA : criterionB);
                    useA = !useA;
                    updateInvocations.incrementAndGet();
                }
            } catch (Throwable t) {
                failures.add(t);
            }
        });

        // READERS: a clean read can only ever be false; any true is proof of a torn read, and any
        // throw (e.g. NPE) is the other symptom of one
        for (int i = 0; i < readerThreads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    while (running.get()) {
                        boolean result = sharedMatcher.matches(null, fixedRequest);
                        matchInvocations.incrementAndGet();
                        if (result) {
                            sawImpossibleTrue.set(true);
                        }
                    }
                } catch (Throwable t) {
                    failures.add(t);
                }
            });
        }

        assertThat("threads did not become ready", ready.await(5, TimeUnit.SECONDS), is(true));
        go.countDown();
        Thread.sleep(durationMillis);
        running.set(false);
        pool.shutdown();
        assertThat("threads did not terminate", pool.awaitTermination(10, TimeUnit.SECONDS), is(true));

        if (!failures.isEmpty()) {
            throw new AssertionError(
                "matches()/update() threw " + failures.size() + " time(s) under concurrent update — "
                    + "the shared matcher exposed a torn (half-rebuilt) criterion. First failure: "
                    + failures.get(0),
                failures.get(0)
            );
        }
        assertThat(
            "matches() returned true for a request that matches NEITHER criterion — a torn read paired "
                + "one criterion's method matcher with the other's path matcher",
            sawImpossibleTrue.get(),
            is(false)
        );
        // sanity: the race actually ran (both planes made meaningful progress)
        assertThat("matches() never ran", matchInvocations.get(), is(greaterThan(0L)));
        assertThat("update() never ran", updateInvocations.get(), is(greaterThan(0L)));
    }
}
