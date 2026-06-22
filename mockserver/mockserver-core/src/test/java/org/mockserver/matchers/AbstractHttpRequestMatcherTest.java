package org.mockserver.matchers;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;

import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

/**
 * Unit tests for the lazy-removal CAS guard on {@link AbstractHttpRequestMatcher}
 * (review item W3-4): {@code tryScheduleRemoval()} must claim the removal exactly
 * once, including under concurrent observation, and re-arm when the matcher is
 * reused in place with a new expectation.
 */
public class AbstractHttpRequestMatcherTest {

    private static AbstractHttpRequestMatcher matcher() {
        HttpRequestPropertiesMatcher matcher = new HttpRequestPropertiesMatcher(configuration(), new MockServerLogger());
        matcher.update(new Expectation(request().withPath("somePath")));
        return matcher;
    }

    @Test
    public void tryScheduleRemovalReturnsTrueOnlyForFirstCaller() {
        AbstractHttpRequestMatcher matcher = matcher();

        assertThat("first claim wins", matcher.tryScheduleRemoval(), is(true));
        assertThat("second claim loses", matcher.tryScheduleRemoval(), is(false));
        assertThat("third claim loses", matcher.tryScheduleRemoval(), is(false));
    }

    @Test
    public void updateInPlaceReArmsRemovalClaim() {
        AbstractHttpRequestMatcher matcher = matcher();

        assertThat(matcher.tryScheduleRemoval(), is(true));
        assertThat(matcher.tryScheduleRemoval(), is(false));

        // reusing the matcher with a different expectation (e.g. Times reset) must
        // re-arm the lazy-removal claim so it can be removed again later
        matcher.update(new Expectation(request().withPath("otherPath")));

        assertThat("claim re-armed after in-place update", matcher.tryScheduleRemoval(), is(true));
        assertThat(matcher.tryScheduleRemoval(), is(false));
    }

    @Test
    public void concurrentCallersClaimRemovalExactlyOnce() throws InterruptedException {
        final int threads = 32;
        final AbstractHttpRequestMatcher matcher = matcher();
        final AtomicInteger winners = new AtomicInteger(0);
        final java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    if (matcher.tryScheduleRemoval()) {
                        winners.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }

        start.countDown();
        done.await();

        assertThat("exactly one thread may schedule the removal", winners.get(), is(1));
    }
}
