package org.mockserver.mock.listeners;

import org.junit.Test;
import org.mockserver.log.MockServerEventLog;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * Tests that MockServerLogListener is a functional interface with the expected contract.
 */
public class MockServerLogListenerTest {

    @Test
    public void shouldAcceptLambdaImplementation() {
        boolean[] called = {false};
        MockServerLogListener listener = (mockServerLog) -> called[0] = true;

        listener.updated(null);

        assertThat(called[0], is(true));
    }

    @Test
    public void shouldPropagateEventLogArgumentIncludingNull() {
        int[] callCount = {0};
        MockServerEventLog[] received = {null};
        MockServerLogListener listener = (mockServerLog) -> {
            callCount[0]++;
            received[0] = mockServerLog;
        };

        // pass null since MockServerEventLog has complex constructor dependencies
        listener.updated(null);

        assertThat("listener was invoked exactly once", callCount[0], is(1));
        assertThat("null argument propagated unchanged", received[0], is(nullValue()));
    }
}
