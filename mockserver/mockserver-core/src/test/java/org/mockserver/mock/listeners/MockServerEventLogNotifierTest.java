package org.mockserver.mock.listeners;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.MockServerEventLog;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.scheduler.Scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Tests MockServerEventLogNotifier register/unregister/notify behaviour
 * using a real synchronous Scheduler (no Mockito).
 */
public class MockServerEventLogNotifierTest {

    /**
     * Concrete subclass to expose the protected notifyListeners method for testing.
     */
    private static class TestableEventLogNotifier extends MockServerEventLogNotifier {
        TestableEventLogNotifier(Scheduler scheduler) {
            super(scheduler);
        }

        void fireNotification(MockServerEventLog notifier, boolean synchronous) {
            notifyListeners(notifier, synchronous);
        }

        void stop() {
            stopNotifications();
        }
    }

    /**
     * Counting listener for the async coalescing tests.
     */
    private static class CountingLogListener implements MockServerLogListener {
        final AtomicInteger updateCount = new AtomicInteger(0);

        @Override
        public void updated(MockServerEventLog mockServerLog) {
            updateCount.incrementAndGet();
        }
    }

    private TestableEventLogNotifier createAsyncNotifier(Scheduler scheduler) {
        return new TestableEventLogNotifier(scheduler);
    }

    /**
     * Real listener that records calls for assertion.
     */
    private static class RecordingLogListener implements MockServerLogListener {
        private final List<MockServerEventLog> receivedLogs = new ArrayList<>();

        @Override
        public void updated(MockServerEventLog mockServerLog) {
            receivedLogs.add(mockServerLog);
        }

        List<MockServerEventLog> getReceivedLogs() {
            return receivedLogs;
        }
    }

    private TestableEventLogNotifier createNotifier() {
        Configuration configuration = Configuration.configuration();
        MockServerLogger logger = new MockServerLogger();
        Scheduler scheduler = new Scheduler(configuration, logger, true);
        return new TestableEventLogNotifier(scheduler);
    }

    @Test
    public void shouldNotifyRegisteredListener() {
        TestableEventLogNotifier notifier = createNotifier();
        RecordingLogListener listener = new RecordingLogListener();

        notifier.registerListener(listener);
        notifier.fireNotification(null, true);

        assertThat(listener.getReceivedLogs(), hasSize(1));
    }

    @Test
    public void shouldNotifyMultipleListeners() {
        TestableEventLogNotifier notifier = createNotifier();
        RecordingLogListener listener1 = new RecordingLogListener();
        RecordingLogListener listener2 = new RecordingLogListener();

        notifier.registerListener(listener1);
        notifier.registerListener(listener2);
        notifier.fireNotification(null, true);

        assertThat(listener1.getReceivedLogs(), hasSize(1));
        assertThat(listener2.getReceivedLogs(), hasSize(1));
    }

    @Test
    public void shouldNotNotifyWhenNoListenersRegistered() {
        TestableEventLogNotifier notifier = createNotifier();

        // should not throw
        notifier.fireNotification(null, true);
    }

    @Test
    public void shouldNotNotifyAfterUnregister() {
        TestableEventLogNotifier notifier = createNotifier();
        RecordingLogListener listener = new RecordingLogListener();

        notifier.registerListener(listener);
        notifier.unregisterListener(listener);
        notifier.fireNotification(null, true);

        assertThat(listener.getReceivedLogs(), is(empty()));
    }

    @Test
    public void shouldOnlyUnregisterSpecifiedListener() {
        TestableEventLogNotifier notifier = createNotifier();
        RecordingLogListener listener1 = new RecordingLogListener();
        RecordingLogListener listener2 = new RecordingLogListener();

        notifier.registerListener(listener1);
        notifier.registerListener(listener2);
        notifier.unregisterListener(listener1);
        notifier.fireNotification(null, true);

        assertThat(listener1.getReceivedLogs(), is(empty()));
        assertThat(listener2.getReceivedLogs(), hasSize(1));
    }

    @Test
    public void shouldNotifyListenerMultipleTimes() {
        TestableEventLogNotifier notifier = createNotifier();
        RecordingLogListener listener = new RecordingLogListener();

        notifier.registerListener(listener);
        notifier.fireNotification(null, true);
        notifier.fireNotification(null, true);
        notifier.fireNotification(null, true);

        assertThat(listener.getReceivedLogs(), hasSize(3));
    }

    @Test
    public void shouldCoalesceRapidAsynchronousNotifications() throws InterruptedException {
        // async Scheduler so notifyListeners(..., false) goes through the debounce path
        Scheduler scheduler = new Scheduler(Configuration.configuration(), new MockServerLogger(), false);
        try {
            TestableEventLogNotifier notifier = createAsyncNotifier(scheduler);
            CountingLogListener listener = new CountingLogListener();
            notifier.registerListener(listener);

            // when - a rapid burst of async notifications (simulating many log adds within one window)
            for (int i = 0; i < 1000; i++) {
                notifier.fireNotification(null, false);
            }

            // then - they coalesce into at most one updated(...) within the debounce window.
            // Wait beyond one window (250ms) plus scheduling slack, then assert exactly one fired.
            Thread.sleep(1500);
            assertThat("rapid async adds should coalesce to a single notification",
                listener.updateCount.get(), is(1));
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    public void shouldFireSynchronousNotificationsImmediatelyWithoutCoalescing() {
        // even with an async Scheduler, synchronous=true must fire immediately and per-call
        Scheduler scheduler = new Scheduler(Configuration.configuration(), new MockServerLogger(), false);
        try {
            TestableEventLogNotifier notifier = createAsyncNotifier(scheduler);
            CountingLogListener listener = new CountingLogListener();
            notifier.registerListener(listener);

            notifier.fireNotification(null, true);
            notifier.fireNotification(null, true);
            notifier.fireNotification(null, true);

            // immediate, no waiting required, no coalescing
            assertThat(listener.updateCount.get(), is(3));
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    public void shouldNotLeakCoalescingTaskAfterStop() throws InterruptedException {
        Scheduler scheduler = new Scheduler(Configuration.configuration(), new MockServerLogger(), false);
        try {
            TestableEventLogNotifier notifier = createAsyncNotifier(scheduler);
            CountingLogListener listener = new CountingLogListener();
            notifier.registerListener(listener);

            // schedule a pending coalesced notification then immediately stop
            notifier.fireNotification(null, false);
            notifier.stop();

            // any further async notifications after stop must not be scheduled/dispatched
            notifier.fireNotification(null, false);

            // wait well beyond the debounce window: the cancelled task must never fire
            Thread.sleep(1500);
            assertThat("coalescing task must be cancelled on stop and not fire afterwards",
                listener.updateCount.get(), is(0));
        } finally {
            scheduler.shutdown();
        }
    }
}
