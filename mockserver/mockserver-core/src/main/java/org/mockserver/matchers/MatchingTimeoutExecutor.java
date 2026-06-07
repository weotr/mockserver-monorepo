package org.mockserver.matchers;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import static org.slf4j.event.Level.WARN;

/**
 * Shared daemon-thread executor used by request matchers (regex, XPath) to
 * bound the runtime of pathological user-supplied expressions. Wrapping each
 * match in a Future-with-timeout protects MockServer from ReDoS / XPath DoS
 * attacks where a single malicious expectation or input would otherwise pin a
 * Netty worker thread.
 * <p>
 * The pool is cached (not single-thread) so concurrent matches do not serialize,
 * and daemon-flagged so it never blocks JVM shutdown.
 */
public final class MatchingTimeoutExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(MatchingTimeoutExecutor.class);

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(
        new ThreadFactoryBuilder()
            .setNameFormat("mockserver-match-eval-%d")
            .setDaemon(true)
            .build()
    );

    private MatchingTimeoutExecutor() {
    }

    /**
     * Run a matching task with a millisecond timeout. A non-positive timeout
     * disables the timeout and runs the task on the calling thread (preserving
     * pre-timeout behaviour for users who opt out).
     *
     * @return the task's result, or {@code onTimeout} when the timeout fires
     * @throws Exception any checked exception thrown by the task (other than TimeoutException)
     */
    public static <T> T callWithTimeout(Callable<T> task, long timeoutMillis, T onTimeout, OnTimeout onTimeoutCallback) throws Exception {
        if (timeoutMillis <= 0) {
            return task.call();
        }
        // Wrap the user task so that any residual interrupt flag left on a recycled pool
        // thread (e.g. from a previous timed-out task whose long-running work ignored
        // future.cancel(true)) is cleared before this task starts. Without this, the
        // recycled worker can carry an interrupt flag that causes the next task to
        // observe spurious failure or incorrect results.
        Callable<T> cleanTask = () -> {
            Thread.interrupted();
            return task.call();
        };
        Future<T> future = EXECUTOR.submit(cleanTask);
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            if (onTimeoutCallback != null) {
                onTimeoutCallback.fired(timeoutMillis);
            }
            return onTimeout;
        } catch (InterruptedException ie) {
            // restore the caller-thread interrupt flag so cooperative shutdown is not lost,
            // and return the safe sentinel rather than propagating an unrelated exception type.
            future.cancel(true);
            Thread.currentThread().interrupt();
            return onTimeout;
        } catch (ExecutionException ee) {
            // unwrap and rethrow the actual user-thrown exception (e.g. PatternSyntaxException)
            // so callers can catch it precisely.
            Throwable cause = ee.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw ee;
        }
    }

    /**
     * Evaluate a user-supplied regular expression under the shared
     * {@code mockserver.regexMatchingTimeoutMillis} timeout, so a pathological (ReDoS) pattern cannot
     * pin a worker thread. A timeout or any error is treated as a non-match (returns {@code false}) and,
     * when a timeout fires and a logger is supplied, logs a WARN naming the pattern.
     *
     * @param mockServerLogger logger for the timeout warning (may be null)
     * @param description       short label for the log (e.g. "graphql operationName")
     * @param pattern           the compiled user regex (used only for the log message)
     * @param matchOperation    the actual match call (e.g. {@code () -> pattern.matcher(input).matches()})
     * @return the match result, or {@code false} on timeout/error
     */
    public static boolean matchesWithRegexTimeout(MockServerLogger mockServerLogger, String description, Pattern pattern, Callable<Boolean> matchOperation) {
        try {
            return callWithTimeout(
                matchOperation,
                ConfigurationProperties.regexMatchingTimeoutMillis(),
                Boolean.FALSE,
                fired -> {
                    if (mockServerLogger != null) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(WARN)
                                .setMessageFormat(description + " regex evaluation timed out after {}ms for pattern:{}— treating as non-match (raise mockserver.regexMatchingTimeoutMillis or simplify the pattern to suppress this)")
                                .setArguments(fired, pattern.pattern())
                        );
                    } else {
                        // callers without a MockServerLogger still get an observable trace of the timeout
                        LOGGER.warn("{} regex evaluation timed out after {}ms for pattern:{} — treating as non-match (raise mockserver.regexMatchingTimeoutMillis or simplify the pattern to suppress this)", description, fired, pattern.pattern());
                    }
                });
        } catch (Exception e) {
            return false;
        }
    }

    @FunctionalInterface
    public interface OnTimeout {
        void fired(long timeoutMillis);
    }
}
