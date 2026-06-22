package org.mockserver.serialization.model;

import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;
import org.mockserver.slo.Scope;
import org.mockserver.slo.SloObjective;

/**
 * DTO for {@link SloObjective}. See {@link SloCriteriaDTO}.
 */
public class SloObjectiveDTO extends ObjectWithReflectiveEqualsHashCodeToString implements DTO<SloObjective> {

    private SloObjective.Sli sli;
    private SloObjective.Comparator comparator;
    private double threshold;
    private Scope scope = Scope.FORWARD;

    public SloObjectiveDTO(SloObjective objective) {
        if (objective != null) {
            sli = objective.getSli();
            comparator = objective.getComparator();
            threshold = objective.getThreshold();
            scope = objective.getScope();
        }
    }

    public SloObjectiveDTO() {
    }

    public SloObjective buildObject() {
        return new SloObjective()
            .withSli(sli)
            .withComparator(comparator)
            .withThreshold(threshold)
            .withScope(scope != null ? scope : Scope.FORWARD);
    }

    public SloObjective.Sli getSli() {
        return sli;
    }

    public SloObjectiveDTO setSli(SloObjective.Sli sli) {
        this.sli = sli;
        return this;
    }

    public SloObjective.Comparator getComparator() {
        return comparator;
    }

    public SloObjectiveDTO setComparator(SloObjective.Comparator comparator) {
        this.comparator = comparator;
        return this;
    }

    public double getThreshold() {
        return threshold;
    }

    public SloObjectiveDTO setThreshold(double threshold) {
        this.threshold = threshold;
        return this;
    }

    public Scope getScope() {
        return scope;
    }

    public SloObjectiveDTO setScope(Scope scope) {
        this.scope = scope;
        return this;
    }
}
