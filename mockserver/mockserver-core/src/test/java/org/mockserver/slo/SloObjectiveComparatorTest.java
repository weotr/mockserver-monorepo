package org.mockserver.slo;

import org.junit.Test;
import org.mockserver.load.LoadThreshold;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Unit tests for the single-sourced {@link SloObjective.Comparator#test(double, double)} comparison
 * semantics, and that both callers ({@link SloObjective#satisfiedBy(double)} and
 * {@link LoadThreshold#satisfiedBy(double)}) delegate to it. Pure in-memory, no global state — runs in
 * the parallel Surefire phase.
 */
public class SloObjectiveComparatorTest {

    @Test
    public void testCoversAllFourComparatorsForBothSidesOfTheThreshold() {
        assertThat(SloObjective.Comparator.LESS_THAN.test(4.0, 5.0), is(true));
        assertThat(SloObjective.Comparator.LESS_THAN.test(5.0, 5.0), is(false));
        assertThat(SloObjective.Comparator.LESS_THAN.test(6.0, 5.0), is(false));

        assertThat(SloObjective.Comparator.LESS_THAN_OR_EQUAL.test(4.0, 5.0), is(true));
        assertThat(SloObjective.Comparator.LESS_THAN_OR_EQUAL.test(5.0, 5.0), is(true));
        assertThat(SloObjective.Comparator.LESS_THAN_OR_EQUAL.test(6.0, 5.0), is(false));

        assertThat(SloObjective.Comparator.GREATER_THAN.test(6.0, 5.0), is(true));
        assertThat(SloObjective.Comparator.GREATER_THAN.test(5.0, 5.0), is(false));
        assertThat(SloObjective.Comparator.GREATER_THAN.test(4.0, 5.0), is(false));

        assertThat(SloObjective.Comparator.GREATER_THAN_OR_EQUAL.test(6.0, 5.0), is(true));
        assertThat(SloObjective.Comparator.GREATER_THAN_OR_EQUAL.test(5.0, 5.0), is(true));
        assertThat(SloObjective.Comparator.GREATER_THAN_OR_EQUAL.test(4.0, 5.0), is(false));
    }

    @Test
    public void sloObjectiveSatisfiedByDelegatesToComparator() {
        for (SloObjective.Comparator comparator : SloObjective.Comparator.values()) {
            SloObjective objective = SloObjective.sloObjective().withComparator(comparator).withThreshold(5.0);
            assertThat(objective.satisfiedBy(4.0), is(comparator.test(4.0, 5.0)));
            assertThat(objective.satisfiedBy(5.0), is(comparator.test(5.0, 5.0)));
            assertThat(objective.satisfiedBy(6.0), is(comparator.test(6.0, 5.0)));
        }
    }

    @Test
    public void loadThresholdSatisfiedByDelegatesToTheSameComparator() {
        for (SloObjective.Comparator comparator : SloObjective.Comparator.values()) {
            LoadThreshold threshold = LoadThreshold.loadThreshold().withComparator(comparator).withThreshold(5.0);
            assertThat(threshold.satisfiedBy(4.0), is(comparator.test(4.0, 5.0)));
            assertThat(threshold.satisfiedBy(5.0), is(comparator.test(5.0, 5.0)));
            assertThat(threshold.satisfiedBy(6.0), is(comparator.test(6.0, 5.0)));
        }
    }

    @Test
    public void loadThresholdWithNullComparatorIsSatisfied() {
        // Preserved lenient behaviour: a threshold with no comparator never fails.
        assertThat(LoadThreshold.loadThreshold().withThreshold(5.0).satisfiedBy(999.0), is(true));
    }
}
