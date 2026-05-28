package org.mockserver.mock.listeners;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.RequestMatchers;
import org.mockserver.scheduler.Scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Tests MockServerMatcherNotifier register/unregister/notify behaviour
 * and the Cause value class.
 */
public class MockServerMatcherNotifierTest {

    /**
     * Concrete subclass to expose the protected notifyListeners method for testing.
     */
    private static class TestableMatcherNotifier extends MockServerMatcherNotifier {
        TestableMatcherNotifier(Scheduler scheduler) {
            super(scheduler);
        }

        void fireNotification(RequestMatchers notifier, MockServerMatcherNotifier.Cause cause) {
            notifyListeners(notifier, cause);
        }
    }

    /**
     * Real listener recording calls for assertion.
     */
    private static class RecordingMatcherListener implements MockServerMatcherListener {
        private final List<MockServerMatcherNotifier.Cause> receivedCauses = new ArrayList<>();

        @Override
        public void updated(RequestMatchers requestMatchers, MockServerMatcherNotifier.Cause cause) {
            receivedCauses.add(cause);
        }

        List<MockServerMatcherNotifier.Cause> getReceivedCauses() {
            return receivedCauses;
        }
    }

    private TestableMatcherNotifier createNotifier() {
        Configuration configuration = Configuration.configuration();
        MockServerLogger logger = new MockServerLogger();
        Scheduler scheduler = new Scheduler(configuration, logger, true);
        return new TestableMatcherNotifier(scheduler);
    }

    @Test
    public void shouldNotifyRegisteredListener() {
        TestableMatcherNotifier notifier = createNotifier();
        RecordingMatcherListener listener = new RecordingMatcherListener();

        notifier.registerListener(listener);
        notifier.fireNotification(null, MockServerMatcherNotifier.Cause.API);

        assertThat(listener.getReceivedCauses(), hasSize(1));
        assertThat(listener.getReceivedCauses().get(0), is(MockServerMatcherNotifier.Cause.API));
    }

    @Test
    public void shouldNotifyMultipleListeners() {
        TestableMatcherNotifier notifier = createNotifier();
        RecordingMatcherListener listener1 = new RecordingMatcherListener();
        RecordingMatcherListener listener2 = new RecordingMatcherListener();

        notifier.registerListener(listener1);
        notifier.registerListener(listener2);
        notifier.fireNotification(null, MockServerMatcherNotifier.Cause.API);

        assertThat(listener1.getReceivedCauses(), hasSize(1));
        assertThat(listener2.getReceivedCauses(), hasSize(1));
    }

    @Test
    public void shouldNotNotifyWhenNoListenersRegistered() {
        TestableMatcherNotifier notifier = createNotifier();
        // should not throw
        notifier.fireNotification(null, MockServerMatcherNotifier.Cause.API);
    }

    @Test
    public void shouldNotNotifyAfterUnregister() {
        TestableMatcherNotifier notifier = createNotifier();
        RecordingMatcherListener listener = new RecordingMatcherListener();

        notifier.registerListener(listener);
        notifier.unregisterListener(listener);
        notifier.fireNotification(null, MockServerMatcherNotifier.Cause.API);

        assertThat(listener.getReceivedCauses(), is(empty()));
    }

    @Test
    public void shouldOnlyUnregisterSpecifiedListener() {
        TestableMatcherNotifier notifier = createNotifier();
        RecordingMatcherListener listener1 = new RecordingMatcherListener();
        RecordingMatcherListener listener2 = new RecordingMatcherListener();

        notifier.registerListener(listener1);
        notifier.registerListener(listener2);
        notifier.unregisterListener(listener1);
        notifier.fireNotification(null, MockServerMatcherNotifier.Cause.API);

        assertThat(listener1.getReceivedCauses(), is(empty()));
        assertThat(listener2.getReceivedCauses(), hasSize(1));
    }

    @Test
    public void shouldPassCauseToListener() {
        TestableMatcherNotifier notifier = createNotifier();
        RecordingMatcherListener listener = new RecordingMatcherListener();

        MockServerMatcherNotifier.Cause cause = new MockServerMatcherNotifier.Cause("my-source", MockServerMatcherNotifier.Cause.Type.FILE_INITIALISER);
        notifier.registerListener(listener);
        notifier.fireNotification(null, cause);

        assertThat(listener.getReceivedCauses().get(0).getSource(), is("my-source"));
        assertThat(listener.getReceivedCauses().get(0).getType(), is(MockServerMatcherNotifier.Cause.Type.FILE_INITIALISER));
    }

    // Cause value class tests

    @Test
    public void shouldCreateCauseWithSourceAndType() {
        MockServerMatcherNotifier.Cause cause = new MockServerMatcherNotifier.Cause("source-file", MockServerMatcherNotifier.Cause.Type.CLASS_INITIALISER);

        assertThat(cause.getSource(), is("source-file"));
        assertThat(cause.getType(), is(MockServerMatcherNotifier.Cause.Type.CLASS_INITIALISER));
    }

    @Test
    public void shouldHaveAPIConstant() {
        assertThat(MockServerMatcherNotifier.Cause.API.getSource(), is(""));
        assertThat(MockServerMatcherNotifier.Cause.API.getType(), is(MockServerMatcherNotifier.Cause.Type.API));
    }

    @Test
    public void shouldHaveAllCauseTypes() {
        MockServerMatcherNotifier.Cause.Type[] types = MockServerMatcherNotifier.Cause.Type.values();
        assertThat(types.length, is(3));
        assertThat(types, hasItemInArray(MockServerMatcherNotifier.Cause.Type.FILE_INITIALISER));
        assertThat(types, hasItemInArray(MockServerMatcherNotifier.Cause.Type.CLASS_INITIALISER));
        assertThat(types, hasItemInArray(MockServerMatcherNotifier.Cause.Type.API));
    }

    @Test
    public void shouldImplementEqualsForCause() {
        MockServerMatcherNotifier.Cause cause1 = new MockServerMatcherNotifier.Cause("src", MockServerMatcherNotifier.Cause.Type.API);
        MockServerMatcherNotifier.Cause cause2 = new MockServerMatcherNotifier.Cause("src", MockServerMatcherNotifier.Cause.Type.API);
        MockServerMatcherNotifier.Cause different = new MockServerMatcherNotifier.Cause("other", MockServerMatcherNotifier.Cause.Type.API);

        assertThat(cause1.equals(cause2), is(true));
        assertThat(cause1.equals(different), is(false));
        assertThat(cause1.equals(null), is(false));
    }

    @Test
    public void shouldImplementHashCodeForCause() {
        MockServerMatcherNotifier.Cause cause1 = new MockServerMatcherNotifier.Cause("src", MockServerMatcherNotifier.Cause.Type.API);
        MockServerMatcherNotifier.Cause cause2 = new MockServerMatcherNotifier.Cause("src", MockServerMatcherNotifier.Cause.Type.API);

        assertThat(cause1.hashCode(), is(cause2.hashCode()));
    }
}
