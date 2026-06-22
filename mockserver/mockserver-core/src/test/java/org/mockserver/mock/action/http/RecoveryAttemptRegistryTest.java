package org.mockserver.mock.action.http;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.core.Is.is;

/**
 * Behavioural tests for {@link RecoveryAttemptRegistry} — the node-local keyed attempt counter for
 * the {@link org.mockserver.model.RecoverAfter} recovery primitive.
 *
 * <p>State-mutating-singleton test: registered in BOTH the parallel {@code <excludes>} AND the
 * sequential {@code <includes>} of mockserver-core's two-phase Surefire config.
 */
public class RecoveryAttemptRegistryTest {

    @Before
    @After
    public void resetRegistry() {
        RecoveryAttemptRegistry.getInstance().reset();
    }

    @Test
    public void nextAttemptIsOneBasedAndIncrementsPerKey() {
        RecoveryAttemptRegistry registry = RecoveryAttemptRegistry.getInstance();
        assertThat(registry.nextAttempt("exp-1", "key-a"), is(1));
        assertThat(registry.nextAttempt("exp-1", "key-a"), is(2));
        assertThat(registry.nextAttempt("exp-1", "key-a"), is(3));
    }

    @Test
    public void distinctKeysHaveIndependentCounters() {
        RecoveryAttemptRegistry registry = RecoveryAttemptRegistry.getInstance();
        assertThat(registry.nextAttempt("exp-1", "key-a"), is(1));
        assertThat(registry.nextAttempt("exp-1", "key-b"), is(1));
        assertThat(registry.nextAttempt("exp-1", "key-a"), is(2));
        assertThat(registry.nextAttempt("exp-1", "key-b"), is(2));
    }

    @Test
    public void distinctExpectationsDoNotCollideOnSameKeyValue() {
        RecoveryAttemptRegistry registry = RecoveryAttemptRegistry.getInstance();
        assertThat(registry.nextAttempt("exp-1", "shared"), is(1));
        assertThat(registry.nextAttempt("exp-2", "shared"), is(1));
    }

    @Test
    public void expectationIdContainingSpaceDoesNotCollide() {
        RecoveryAttemptRegistry registry = RecoveryAttemptRegistry.getInstance();
        // With a space separator, ("exp 1", "a") and ("exp", "1 a") would both compose to
        // "exp 1 a" and collide. The NUL separator keeps them distinct.
        assertThat(registry.nextAttempt("exp 1", "a"), is(1));
        assertThat(registry.nextAttempt("exp", "1 a"), is(1));
        assertThat(registry.nextAttempt("exp 1", "a"), is(2));
        assertThat(registry.nextAttempt("exp", "1 a"), is(2));
    }

    @Test
    public void mapDoesNotGrowPastCapAndEvictedKeyRestartsAtAttemptOne() {
        RecoveryAttemptRegistry registry = RecoveryAttemptRegistry.getInstance();

        // first distinct key becomes the least-recently-used and will be evicted first
        String firstKey = "key-00000000";
        assertThat(registry.nextAttempt("exp-1", firstKey), is(1));
        assertThat(registry.nextAttempt("exp-1", firstKey), is(2));

        // insert far more distinct keys than the cap, touching the firstKey last so it ages out
        for (int i = 1; i <= RecoveryAttemptRegistry.MAX_SIZE + 1_000; i++) {
            registry.nextAttempt("exp-1", "key-" + i);
        }

        assertThat(registry.size(), is(lessThanOrEqualTo(RecoveryAttemptRegistry.MAX_SIZE)));

        // firstKey was evicted (it became the eldest), so its counter restarts from 1
        assertThat(registry.nextAttempt("exp-1", firstKey), is(1));
    }

    @Test
    public void resetClearsAllCounters() {
        RecoveryAttemptRegistry registry = RecoveryAttemptRegistry.getInstance();
        registry.nextAttempt("exp-1", "key-a");
        registry.nextAttempt("exp-1", "key-a");

        registry.reset();

        assertThat(registry.nextAttempt("exp-1", "key-a"), is(1));
    }
}
