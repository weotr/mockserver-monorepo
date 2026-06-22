package org.mockserver.serialization.model;

import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;
import org.mockserver.slo.SloCriteria;
import org.mockserver.slo.SloObjective;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * DTO for {@link SloCriteria}, the body of {@code PUT /mockserver/verifySLO}.
 * Mirrors {@link VerificationSequenceDTO}: a Jackson-friendly holder with
 * {@code setX} mutators and a {@link #buildObject()} that reconstructs the model.
 */
public class SloCriteriaDTO extends ObjectWithReflectiveEqualsHashCodeToString implements DTO<SloCriteria> {

    private String name;
    private SloWindowDTO window;
    private List<SloObjectiveDTO> objectives = new ArrayList<>();
    private Integer minimumSampleCount;
    private Set<String> upstreamHosts;

    public SloCriteriaDTO(SloCriteria criteria) {
        if (criteria != null) {
            name = criteria.getName();
            if (criteria.getWindow() != null) {
                window = new SloWindowDTO(criteria.getWindow());
            }
            if (criteria.getObjectives() != null) {
                for (SloObjective objective : criteria.getObjectives()) {
                    objectives.add(new SloObjectiveDTO(objective));
                }
            }
            minimumSampleCount = criteria.getMinimumSampleCount();
            if (criteria.getUpstreamHosts() != null) {
                upstreamHosts = new LinkedHashSet<>(criteria.getUpstreamHosts());
            }
        }
    }

    public SloCriteriaDTO() {
    }

    public SloCriteria buildObject() {
        List<SloObjective> builtObjectives = new ArrayList<>();
        if (objectives != null) {
            for (SloObjectiveDTO objective : objectives) {
                builtObjectives.add(objective.buildObject());
            }
        }
        SloCriteria criteria = new SloCriteria()
            .withName(name)
            .withWindow(window != null ? window.buildObject() : null)
            .withObjectives(builtObjectives)
            .withUpstreamHosts(upstreamHosts);
        if (minimumSampleCount != null) {
            criteria.withMinimumSampleCount(minimumSampleCount);
        }
        return criteria;
    }

    public String getName() {
        return name;
    }

    public SloCriteriaDTO setName(String name) {
        this.name = name;
        return this;
    }

    public SloWindowDTO getWindow() {
        return window;
    }

    public SloCriteriaDTO setWindow(SloWindowDTO window) {
        this.window = window;
        return this;
    }

    public List<SloObjectiveDTO> getObjectives() {
        return objectives;
    }

    public SloCriteriaDTO setObjectives(List<SloObjectiveDTO> objectives) {
        this.objectives = objectives != null ? objectives : new ArrayList<>();
        return this;
    }

    public Integer getMinimumSampleCount() {
        return minimumSampleCount;
    }

    public SloCriteriaDTO setMinimumSampleCount(Integer minimumSampleCount) {
        this.minimumSampleCount = minimumSampleCount;
        return this;
    }

    public Set<String> getUpstreamHosts() {
        return upstreamHosts;
    }

    public SloCriteriaDTO setUpstreamHosts(Set<String> upstreamHosts) {
        this.upstreamHosts = upstreamHosts;
        return this;
    }
}
