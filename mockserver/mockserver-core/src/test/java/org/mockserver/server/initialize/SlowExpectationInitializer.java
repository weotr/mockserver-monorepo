package org.mockserver.server.initialize;

import org.mockserver.mock.Expectation;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Test-only expectation initializer that blocks inside {@link #initializeExpectations()} until a
 * shared latch is released. Used to prove that the readiness flag stays not-ready while expectation
 * initializers are still running and only flips ready once they have completed.
 *
 * @author jamesdbloom
 */
public class SlowExpectationInitializer implements ExpectationInitializer {

    // released by the test once it has observed the not-ready state, so the constructor can complete
    public static final CountDownLatch RELEASE = new CountDownLatch(1);
    // counted down as soon as initialization starts, so the test knows construction is in progress
    public static final CountDownLatch STARTED = new CountDownLatch(1);

    @Override
    public Expectation[] initializeExpectations() {
        STARTED.countDown();
        try {
            RELEASE.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new Expectation[]{
            new Expectation(request("/slowInitialized")).thenRespond(response("slow initialized response"))
        };
    }
}
