package org.mockserver.mock.listeners;

import org.junit.Test;
import org.mockserver.mock.RequestMatchers;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Tests that MockServerMatcherListener is a functional interface with the expected contract.
 */
public class MockServerMatcherListenerTest {

    @Test
    public void shouldAcceptLambdaImplementation() {
        boolean[] called = {false};
        MockServerMatcherListener listener = (requestMatchers, cause) -> called[0] = true;

        listener.updated(null, null);

        assertThat(called[0], is(true));
    }

    @Test
    public void shouldReceiveCauseArgument() {
        MockServerMatcherNotifier.Cause[] receivedCause = {null};
        MockServerMatcherListener listener = (requestMatchers, cause) -> receivedCause[0] = cause;

        MockServerMatcherNotifier.Cause apiCause = MockServerMatcherNotifier.Cause.API;
        listener.updated(null, apiCause);

        assertThat(receivedCause[0], is(sameInstance(apiCause)));
    }

    @Test
    public void shouldReceiveBothArguments() {
        int[] callCount = {0};
        Object[] received = new Object[2];
        MockServerMatcherListener listener = (requestMatchers, cause) -> {
            callCount[0]++;
            received[0] = requestMatchers;
            received[1] = cause;
        };

        MockServerMatcherNotifier.Cause cause = new MockServerMatcherNotifier.Cause("test-source", MockServerMatcherNotifier.Cause.Type.FILE_INITIALISER);
        listener.updated(null, cause);

        assertThat("listener was invoked exactly once", callCount[0], is(1));
        assertThat("null requestMatchers argument propagated unchanged", received[0], is(nullValue()));
        assertThat(received[1], is(sameInstance(cause)));
    }
}
