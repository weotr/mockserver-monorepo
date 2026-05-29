package org.mockserver.model;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Shared deterministic probability-draw logic for chaos injection, used by both
 * {@link HttpChaosProfile} (HTTP fault injection) and {@link LlmChaosProfile}
 * (LLM fault injection).
 * <p>
 * Determinism contract:
 * <ul>
 *   <li>{@code probability == null} or {@code probability >= 1.0} &rarr; always inject</li>
 *   <li>{@code probability <= 0.0} &rarr; never inject</li>
 *   <li>Otherwise a single draw decides; when {@code seed != null} the draw is
 *       reproducible (same seed always yields the same result).</li>
 * </ul>
 */
public final class ChaosProbability {

    private ChaosProbability() {
        // utility class
    }

    /**
     * Returns {@code true} when a chaos fault should be injected, based on the
     * given probability and optional seed for reproducibility.
     *
     * @param probability a value in [0.0, 1.0], or {@code null} (treated as 1.0)
     * @param seed        optional seed for reproducible draws; {@code null} uses ThreadLocalRandom
     * @return {@code true} if the fault should fire
     */
    public static boolean shouldInject(Double probability, Long seed) {
        if (probability != null && Double.isNaN(probability)) {
            return false;
        }
        if (probability == null || probability >= 1.0) {
            return true;
        }
        if (probability <= 0.0) {
            return false;
        }
        double draw = seed != null
            ? new Random(seed).nextDouble()
            : ThreadLocalRandom.current().nextDouble();
        return draw < probability;
    }
}
