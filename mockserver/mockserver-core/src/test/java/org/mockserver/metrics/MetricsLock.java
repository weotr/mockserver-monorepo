package org.mockserver.metrics;

import org.junit.rules.ExternalResource;

import java.util.concurrent.locks.ReentrantLock;

/**
 * JUnit {@code @ClassRule} that serializes test classes which mutate the global
 * {@link io.prometheus.metrics.model.registry.PrometheusRegistry#defaultRegistry}
 * and the static fields in {@link Metrics}.
 *
 * <p>Under Surefire {@code parallel=classes}, multiple test classes run
 * concurrently. Classes that call {@link Metrics#resetAdditionalMetricsForTesting()}
 * and then re-register metrics share JVM-global mutable state that cannot be
 * thread-isolated. This rule acquires a JVM-wide lock for the lifetime of the
 * test class, preventing concurrent execution of any two classes that use it.</p>
 *
 * <p>Usage (as a class rule, so the lock spans all tests in the class):</p>
 * <pre>{@code
 * @ClassRule
 * public static final MetricsLock metricsLock = new MetricsLock();
 * }</pre>
 */
public class MetricsLock extends ExternalResource {

    /**
     * JVM-wide lock shared by all MetricsLock instances. Because it is a
     * ReentrantLock (not an intrinsic lock), it can be held across the entire
     * class lifecycle without blocking unrelated tests.
     */
    private static final ReentrantLock LOCK = new ReentrantLock();

    @Override
    protected void before() {
        LOCK.lock();
    }

    @Override
    protected void after() {
        LOCK.unlock();
    }
}
