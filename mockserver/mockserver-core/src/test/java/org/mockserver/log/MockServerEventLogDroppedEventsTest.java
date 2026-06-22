package org.mockserver.log;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.log.model.LogEntry;
import org.mockserver.scheduler.Scheduler;
import org.slf4j.event.Level;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.RECEIVED_REQUEST;
import static org.mockserver.model.HttpRequest.request;

/**
 * Verifies the dropped-log-events counter (Unit E4): when the disruptor ring buffer is full,
 * INFO/DEBUG events that previously vanished silently are now counted and observable via
 * {@link MockServerEventLog#getDroppedLogEventCount()}.
 */
public class MockServerEventLogDroppedEventsTest {

    @Test
    public void countsEventsDroppedWhenRingBufferIsFull() throws Exception {
        // Smallest configurable ring buffer (ringBufferSize = nextPowerOfTwo(maxLogEntries)).
        Configuration configuration = configuration().maxLogEntries(1);
        Scheduler scheduler = mock(Scheduler.class);
        MockServerEventLog eventLog = new MockServerEventLog(
            configuration,
            new MockServerLogger(configuration, MockServerEventLog.class),
            scheduler,
            true // asynchronousEventProcessing => disruptor path that can drop on overflow
        );

        // No drops before any overflow.
        assertThat(eventLog.getDroppedLogEventCount(), is(0L));

        // Occupy the single consumer thread with a blocking RUNNABLE so it cannot drain the ring
        // buffer while we flood it.
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch consumerStarted = new CountDownLatch(1);
        eventLog.add(new LogEntry()
            .setType(LogEntry.LogMessageType.RUNNABLE)
            .setConsumer(() -> {
                consumerStarted.countDown();
                try {
                    hold.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        assertThat("consumer should start", consumerStarted.await(10, TimeUnit.SECONDS), is(true));

        try {
            // With the consumer blocked, flood the ring buffer with far more INFO events than it can
            // hold; once full, tryPublishEvent fails and the events are dropped (and counted).
            for (int i = 0; i < 2_000; i++) {
                eventLog.add(new LogEntry()
                    .setType(RECEIVED_REQUEST)
                    .setLogLevel(Level.INFO)
                    .setHttpRequest(request("/" + i))
                    .setMessageFormat("received request:{}")
                    .setArguments(request("/" + i)));
            }

            assertThat(
                "expected some INFO events to be dropped while the ring buffer was full",
                eventLog.getDroppedLogEventCount(), greaterThan(0L));
        } finally {
            hold.countDown();
            eventLog.stop();
        }
    }
}
