package org.mockserver.model;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class ChaosProbabilityTest {

    @Test
    public void nullProbabilityAlwaysInjects() {
        assertThat(ChaosProbability.shouldInject(null, null), is(true));
    }

    @Test
    public void probabilityOneAlwaysInjects() {
        assertThat(ChaosProbability.shouldInject(1.0, null), is(true));
    }

    @Test
    public void probabilityAboveOneAlwaysInjects() {
        assertThat(ChaosProbability.shouldInject(1.5, null), is(true));
    }

    @Test
    public void probabilityZeroNeverInjects() {
        assertThat(ChaosProbability.shouldInject(0.0, null), is(false));
    }

    @Test
    public void probabilityBelowZeroNeverInjects() {
        assertThat(ChaosProbability.shouldInject(-0.1, null), is(false));
    }

    @Test
    public void nanProbabilityNeverInjects() {
        assertThat(ChaosProbability.shouldInject(Double.NaN, null), is(false));
    }

    @Test
    public void seededFractionalProbabilityIsReproducible() {
        boolean first = ChaosProbability.shouldInject(0.5, 42L);
        boolean second = ChaosProbability.shouldInject(0.5, 42L);
        assertThat("same seed should produce the same result", first, is(second));
    }

    @Test
    public void seed42WithProbabilityHalfProducesExpectedResult() {
        // Random(42).nextDouble() is approximately 0.7275... which is >= 0.5,
        // so shouldInject should return false
        assertThat(ChaosProbability.shouldInject(0.5, 42L), is(false));
    }

    @Test
    public void seed42WithProbability08ProducesExpectedResult() {
        // Random(42).nextDouble() is approximately 0.7275... which is < 0.8,
        // so shouldInject should return true
        assertThat(ChaosProbability.shouldInject(0.8, 42L), is(true));
    }
}
