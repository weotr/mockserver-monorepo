package org.mockserver.mock.listeners;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.MockServerEventLog;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.scheduler.Scheduler;

import java.util.ArrayList;
import java.util.List;

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
}
