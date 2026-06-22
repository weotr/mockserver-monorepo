package org.mockserver.mock;

import org.junit.Test;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.scheduler.Scheduler;

import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.mock.listeners.MockServerMatcherNotifier.Cause.API;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Behavioural test for review item W3-4: the lazy async removal of an inactive
 * matcher must be scheduled AT MOST ONCE, even when several data-plane scans each
 * observe the same matcher as {@code !responseInProgress && !active}.
 *
 * <p>The {@link Scheduler} is mocked so the submitted removal task never actually
 * runs — this keeps the now-inactive matcher in the queue across every scan,
 * reproducing exactly the check-then-act window in which duplicate removals were
 * previously submitted. With no listeners registered, the only
 * {@code scheduler.submit(Runnable)} invocations come from lazy removal, so a single
 * invocation proves the dedup.</p>
 */
public class RequestMatchersLazyRemovalTest {

    private static Expectation expiredExpectation() {
        // a TTL of 0 seconds is already past as soon as it is constructed, so the
        // matcher is inactive without ever being served (responseInProgress stays
        // false), which is exactly the state the lazy-removal scans react to
        return new Expectation(request().withPath("somePath"), Times.unlimited(), TimeToLive.exactly(TimeUnit.SECONDS, 0L), 0)
            .thenRespond(response().withBody("someBody"));
    }

    @Test
    public void schedulesRemovalOfInactiveMatcherAtMostOnceAcrossManyScans() {
        // given - an expectation whose time-to-live has already elapsed, so it is inactive
        Scheduler scheduler = mock(Scheduler.class);
        RequestMatchers requestMatchers = new RequestMatchers(
            configuration(), new MockServerLogger(), scheduler, mock(WebSocketClientRegistry.class));
        requestMatchers.add(expiredExpectation(), API);

        // when - many data-plane scans each observe the matcher as inactive
        for (int i = 0; i < 5; i++) {
            requestMatchers.retrieveActiveExpectations(null);
            requestMatchers.retrieveRequestMatchers(null);
            requestMatchers.firstMatchingExpectation(request().withPath("somePath"));
        }

        // then - the removal was scheduled exactly once (the mocked scheduler never
        // runs it, so the inactive matcher is observed on every scan, yet the CAS
        // guard lets only the first observer submit a removal task)
        verify(scheduler, times(1)).submit(org.mockito.ArgumentMatchers.any(Runnable.class));
    }
}
