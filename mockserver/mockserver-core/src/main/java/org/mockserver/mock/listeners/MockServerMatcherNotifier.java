package org.mockserver.mock.listeners;

import org.mockserver.mock.RequestMatchers;
import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;
import org.mockserver.scheduler.Scheduler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * @author jamesdbloom
 */
public class MockServerMatcherNotifier extends ObjectWithReflectiveEqualsHashCodeToString {

    private static final MockServerMatcherListener[] EMPTY_LISTENERS = new MockServerMatcherListener[0];

    private boolean listenerAdded = false;
    // copy-on-write listener snapshot: the data-plane notify path reads this single volatile with
    // zero allocation/locking; register/unregister rebuild the array under a lock (rare control-plane
    // events). Mirrors CopyOnWriteArrayList semantics with a plain array for the cheapest read.
    private volatile MockServerMatcherListener[] listeners = EMPTY_LISTENERS;
    private final Scheduler scheduler;

    public MockServerMatcherNotifier(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    protected void notifyListeners(final RequestMatchers notifier, Cause cause) {
        if (listenerAdded) {
            for (MockServerMatcherListener listener : listeners) {
                scheduler.submit(() -> listener.updated(notifier, cause));
            }
        }
    }

    public synchronized void registerListener(MockServerMatcherListener listener) {
        MockServerMatcherListener[] current = listeners;
        MockServerMatcherListener[] updated = Arrays.copyOf(current, current.length + 1);
        updated[current.length] = listener;
        listeners = updated;
        listenerAdded = true;
    }

    public synchronized void unregisterListener(MockServerMatcherListener listener) {
        MockServerMatcherListener[] current = listeners;
        List<MockServerMatcherListener> remaining = new ArrayList<>(current.length);
        for (MockServerMatcherListener existing : current) {
            if (existing != listener) {
                remaining.add(existing);
            }
        }
        listeners = remaining.toArray(EMPTY_LISTENERS);
    }

    public static class Cause {
        public Cause(String source, Type type) {
            this.source = source;
            this.type = type;
        }

        public static final Cause API = new Cause("", Type.API);

        public enum Type {
            FILE_INITIALISER,
            CLASS_INITIALISER,
            API
        }

        private final String source;
        private final Type type;

        public String getSource() {
            return source;
        }

        public Type getType() {
            return type;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Cause cause = (Cause) o;
            return Objects.equals(source, cause.source) && type == cause.type;
        }

        @Override
        public int hashCode() {
            return Objects.hash(source, type);
        }
    }
}
