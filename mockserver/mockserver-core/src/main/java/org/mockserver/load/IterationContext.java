package org.mockserver.load;

/**
 * Per-iteration template variable exposed to a {@link LoadScenario} step under the
 * key {@code "iteration"}, sibling of {@code request} / {@code response}. It lets a
 * step's templated request fields vary per iteration without any cross-step state
 * (e.g. {@code $iteration.index} for a unique path segment).
 *
 * <p>Plain JavaBean getters so all three engine syntaxes resolve identically:
 * {@code $iteration.index} (Velocity), {@code {{iteration.index}}} (Mustache) and
 * {@code iteration.getIndex()} (JavaScript).
 *
 * <ul>
 *   <li>{@link #getIndex()} — global iteration index across all virtual users (0-based)</li>
 *   <li>{@link #getVuId()} — the launching virtual user's id (0-based)</li>
 *   <li>{@link #getVuIteration()} — the iteration count within that virtual user (0-based)</li>
 *   <li>{@link #getElapsedMillis()} — millis since the scenario started</li>
 *   <li>{@link #getCount()} — total requests dispatched so far (the global request counter)</li>
 * </ul>
 */
public class IterationContext {

    private final long index;
    private final int vuId;
    private final long vuIteration;
    private final long elapsedMillis;
    private final long count;

    public IterationContext(long index, int vuId, long vuIteration, long elapsedMillis, long count) {
        this.index = index;
        this.vuId = vuId;
        this.vuIteration = vuIteration;
        this.elapsedMillis = elapsedMillis;
        this.count = count;
    }

    public long getIndex() {
        return index;
    }

    public int getVuId() {
        return vuId;
    }

    public long getVuIteration() {
        return vuIteration;
    }

    public long getElapsedMillis() {
        return elapsedMillis;
    }

    public long getCount() {
        return count;
    }
}
