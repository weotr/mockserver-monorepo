package org.mockserver.mock;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.server.initialize.SlowExpectationInitializer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * Verifies the readiness signal: {@link HttpState#isInitializationComplete()} stays false while the
 * expectation initializers are still running and only flips true once the constructor (and therefore
 * all synchronous startup seeding) has completed.
 *
 * @author jamesdbloom
 */
public class HttpStateReadinessTest {

    @Test
    public void shouldNotBeReadyUntilSlowInitializerCompletes() throws Exception {
        // given - an initializer that blocks inside the HttpState constructor
        Configuration configuration = configuration()
            .initializationClass(SlowExpectationInitializer.class.getName());
        MockServerLogger mockServerLogger = new MockServerLogger(configuration, HttpStateReadinessTest.class);
        Scheduler scheduler = mock(Scheduler.class);

        AtomicReference<HttpState> constructed = new AtomicReference<>();
        CompletableFuture<Void> construction = CompletableFuture.runAsync(() ->
            constructed.set(new HttpState(configuration, mockServerLogger, scheduler))
        );

        // when - the initializer has started (so the constructor is mid-flight) but has not completed
        assertThat("slow initializer should have started", SlowExpectationInitializer.STARTED.await(30, TimeUnit.SECONDS), is(true));

        // then - construction has not returned, so the server is not yet ready
        assertThat("construction must not complete while the initializer is blocked", construction.isDone(), is(false));
        assertThat("no HttpState is observable until the constructor returns (not ready)", constructed.get(), is(org.hamcrest.Matchers.nullValue()));

        // when - the initializer is allowed to finish
        SlowExpectationInitializer.RELEASE.countDown();
        construction.get(30, TimeUnit.SECONDS);

        // then - once the constructor returns the server reports ready
        HttpState httpState = constructed.get();
        assertThat("HttpState should be constructed", httpState, is(org.hamcrest.Matchers.notNullValue()));
        assertThat("server should be ready after initialization completes", httpState.isInitializationComplete(), is(true));
    }
}
