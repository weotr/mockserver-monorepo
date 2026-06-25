package org.mockserver.slo;

import org.mockserver.model.ObjectWithJsonToString;

/**
 * A single objective within an {@link SloCriteria}: one service-level indicator
 * compared against a threshold. All objectives of a criteria must hold for the
 * criteria to {@code PASS} (logical AND, see {@link SloEvaluator}).
 */
public class SloObjective extends ObjectWithJsonToString {

    /**
     * The service-level indicator computed over the window.
     *
     * <p>v1 evaluates {@link #LATENCY_P95}, {@link #LATENCY_P99} and
     * {@link #ERROR_RATE}. {@link #LATENCY_P50} is computed with the same
     * percentile algorithm.
     */
    public enum Sli {
        LATENCY_P50,
        LATENCY_P95,
        LATENCY_P99,
        ERROR_RATE
    }

    public enum Comparator {
        LESS_THAN,
        LESS_THAN_OR_EQUAL,
        GREATER_THAN,
        GREATER_THAN_OR_EQUAL;

        /**
         * The single source of truth for threshold comparison semantics, shared by both
         * {@link SloObjective#satisfiedBy(double)} and
         * {@link org.mockserver.load.LoadThreshold#satisfiedBy(double)} so the two features can never
         * drift if the enum grows.
         *
         * @return true when {@code observed} satisfies this comparator against {@code threshold}.
         */
        public boolean test(double observed, double threshold) {
            switch (this) {
                case LESS_THAN:
                    return observed < threshold;
                case LESS_THAN_OR_EQUAL:
                    return observed <= threshold;
                case GREATER_THAN:
                    return observed > threshold;
                case GREATER_THAN_OR_EQUAL:
                    return observed >= threshold;
                default:
                    return false;
            }
        }
    }

    private Sli sli;
    private Comparator comparator;
    private double threshold;
    private Scope scope = Scope.FORWARD;

    public static SloObjective sloObjective() {
        return new SloObjective();
    }

    public Sli getSli() {
        return sli;
    }

    public SloObjective withSli(Sli sli) {
        this.sli = sli;
        return this;
    }

    public Comparator getComparator() {
        return comparator;
    }

    public SloObjective withComparator(Comparator comparator) {
        this.comparator = comparator;
        return this;
    }

    public double getThreshold() {
        return threshold;
    }

    public SloObjective withThreshold(double threshold) {
        this.threshold = threshold;
        return this;
    }

    public Scope getScope() {
        return scope;
    }

    public SloObjective withScope(Scope scope) {
        this.scope = scope;
        return this;
    }

    /**
     * @return true when {@code observed} satisfies this objective's
     * {@link #comparator} against its {@link #threshold}.
     */
    public boolean satisfiedBy(double observed) {
        return comparator.test(observed, threshold);
    }
}
