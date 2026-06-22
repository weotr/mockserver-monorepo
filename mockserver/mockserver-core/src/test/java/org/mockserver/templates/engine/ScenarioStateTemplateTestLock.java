package org.mockserver.templates.engine;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Shared lock that serialises the scenario-state template tests
 * ({@code VelocityScenarioStateTemplateTest} and
 * {@code JavaScriptScenarioStateTemplateTest}) against each other.
 * <p>
 * Both classes mutate the process-global {@link org.mockserver.mock.CrossProtocolEventBus}
 * singleton in {@code @Before} (wiring a fresh live {@link org.mockserver.mock.ScenarioManager})
 * and restore the previous value in {@code @After}. If the two classes run
 * concurrently (e.g. Surefire {@code parallel=classes}), one class's {@code @After}
 * restore can overwrite the other class's {@code @Before} write mid-test, so the
 * template reads a stale/null manager and the scenario state set by the test is
 * invisible. Each test acquires this lock in {@code @Before} and releases it in
 * {@code @After}, guaranteeing the {@code @Before} → test body → {@code @After}
 * sequence is atomic with respect to the sibling class no matter which Surefire
 * phase or thread runs them. JUnit runs {@code @Before}, the test method, and
 * {@code @After} on the same thread, so the {@link ReentrantLock} is acquired and
 * released by the same thread.
 * <p>
 * This guards a <b>test-only</b> hazard: in production a single
 * {@link org.mockserver.mock.HttpState} wires exactly one manager once, so the
 * bus manager is never concurrently rewritten.
 */
public final class ScenarioStateTemplateTestLock {

    public static final ReentrantLock LOCK = new ReentrantLock();

    private ScenarioStateTemplateTestLock() {
    }
}
