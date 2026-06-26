package org.mockserver.mock.listeners;

import org.mockserver.log.MockServerEventLog;
import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;
import org.mockserver.scheduler.Scheduler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Observer-pattern base for the event log. Fans out {@link MockServerLogListener#updated(MockServerEventLog)}
 * notifications to registered listeners.
 *
 * <p>Asynchronous notifications are <strong>coalesced (debounced)</strong>: instead of firing one
 * {@code updated(...)} per log add (each of which makes every listener retrieve and re-serialize the
 * whole log), a rapid burst of adds collapses into at most one {@code updated(...)} per debounce
 * window ({@link #COALESCE_WINDOW_MILLIS} ms). The three listeners — the dashboard WebSocket push, the
 * memory-usage CSV monitor, and the file-system expectation persistence — only ever need the latest
 * state, so dropping intermediate snapshots is safe and dramatically reduces work under load. This does
 * <strong>not</strong> affect verification/retrieval correctness: those paths drain the disruptor and
 * query the event log directly; they never wait on the listener notification path.</p>
 *
 * <p>The <strong>synchronous</strong> path (used by stop/clear/reset where ordering and a final
 * flush matter) stays immediate and is never debounced.</p>
 *
 * @author jamesdbloom
 */
public class MockServerEventLogNotifier extends ObjectWithReflectiveEqualsHashCodeToString {

    /**
     * Debounce window for coalescing asynchronous listener notifications. Small enough that the
     * dashboard stays visually responsive (≤4 updates/sec) while still collapsing high-throughput
     * bursts of log adds into a single retrieve+serialize per window.
     */
    static final long COALESCE_WINDOW_MILLIS = 250L;

    private boolean listenerAdded = false;
    private final List<MockServerLogListener> listeners = Collections.synchronizedList(new ArrayList<>());
    private final Scheduler scheduler;

    // Coalescing state for the asynchronous notification path. dirty is set on every async call;
    // a single scheduled task fires one updated(...) per window when dirty, then re-arms only if more
    // work arrived. coalesceScheduled guards against scheduling more than one pending task at a time.
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final AtomicBoolean coalesceScheduled = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> coalesceFuture;
    private volatile boolean stopped = false;

    public MockServerEventLogNotifier(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * The {@link Scheduler} backing the asynchronous notification path. Exposed to subclasses so the
     * eventual-verification harness can reuse the same scheduled executor for its retry deadline.
     */
    protected Scheduler getScheduler() {
        return scheduler;
    }

    protected void notifyListeners(final MockServerEventLog notifier, boolean synchronous) {
        if (listenerAdded && !listeners.isEmpty()) {
            if (synchronous) {
                // stop/clear/reset: fire immediately so the final state is flushed in order.
                dispatch(notifier);
            } else {
                scheduleCoalescedNotification(notifier);
            }
        }
    }

    private void scheduleCoalescedNotification(final MockServerEventLog notifier) {
        if (stopped) {
            // After stop the final state has already been flushed synchronously and the log is being
            // torn down — drop further async notifications so no task is scheduled past shutdown.
            return;
        }
        ScheduledExecutorService executor = scheduler.getExecutorService();
        if (executor == null || executor.isShutdown()) {
            // No scheduled executor available (synchronous Scheduler, e.g. WAR/servlet) — fall back to
            // the previous immediate behaviour so notifications are never lost.
            dispatch(notifier);
            return;
        }
        dirty.set(true);
        // Only ever keep one pending coalescing task in flight; the task itself re-arms if more
        // adds arrived while it was running.
        if (coalesceScheduled.compareAndSet(false, true)) {
            try {
                coalesceFuture = executor.schedule(
                    () -> runCoalesced(notifier, executor),
                    COALESCE_WINDOW_MILLIS,
                    TimeUnit.MILLISECONDS
                );
            } catch (java.util.concurrent.RejectedExecutionException rejected) {
                // executor shutting down between the isShutdown() check and schedule() — fall back to
                // an immediate dispatch and clear the guard so we never wedge in the scheduled state.
                coalesceScheduled.set(false);
                dispatch(notifier);
            }
        }
    }

    private void runCoalesced(final MockServerEventLog notifier, final ScheduledExecutorService executor) {
        // Consume the dirty flag, fire once, then re-arm only if more adds arrived in the meantime.
        coalesceScheduled.set(false);
        if (dirty.compareAndSet(true, false) && !stopped) {
            dispatch(notifier);
        }
        if (dirty.get() && !stopped && !executor.isShutdown()) {
            scheduleCoalescedNotification(notifier);
        }
    }

    private void dispatch(final MockServerEventLog notifier) {
        for (MockServerLogListener listener : listeners.toArray(new MockServerLogListener[0])) {
            listener.updated(notifier);
        }
    }

    /**
     * Cancel any pending coalesced notification so the scheduled task does not leak past shutdown.
     * Called from {@link MockServerEventLog#stop()} after the final synchronous notification.
     */
    protected void stopNotifications() {
        stopped = true;
        ScheduledFuture<?> future = coalesceFuture;
        if (future != null) {
            future.cancel(false);
        }
        coalesceScheduled.set(false);
        dirty.set(false);
    }

    public void registerListener(MockServerLogListener listener) {
        listeners.add(listener);
        listenerAdded = true;
    }

    public void unregisterListener(MockServerLogListener listener) {
        listeners.remove(listener);
    }

    /**
     * Number of currently-registered listeners. Exposed for tests to assert that transient listeners
     * (e.g. the eventual-verification retry listener) are always unregistered on completion and never leak.
     */
    @com.google.common.annotations.VisibleForTesting
    public int listenerCount() {
        return listeners.size();
    }
}
