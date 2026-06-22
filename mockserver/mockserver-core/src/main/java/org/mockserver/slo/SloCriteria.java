package org.mockserver.slo;

import org.mockserver.model.ObjectWithJsonToString;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A resilience verdict request: a named set of {@link SloObjective}s evaluated
 * synchronously over an already-elapsed {@link SloWindow}. Submitted to
 * {@code PUT /mockserver/verifySLO} and answered with an {@link SloVerdict}.
 *
 * <p>All objectives must hold for the criteria to {@code PASS}. The
 * {@link #minimumSampleCount} guard yields {@code INCONCLUSIVE} when there are
 * too few samples in the window to draw a verdict. An optional
 * {@link #upstreamHosts} filter narrows evaluation to specific upstreams;
 * null/empty means aggregate across all hosts.
 */
public class SloCriteria extends ObjectWithJsonToString {

    private String name;
    private SloWindow window;
    private List<SloObjective> objectives = new ArrayList<>();
    private Integer minimumSampleCount = 1;
    private Set<String> upstreamHosts;

    public static SloCriteria sloCriteria() {
        return new SloCriteria();
    }

    public String getName() {
        return name;
    }

    public SloCriteria withName(String name) {
        this.name = name;
        return this;
    }

    public SloWindow getWindow() {
        return window;
    }

    public SloCriteria withWindow(SloWindow window) {
        this.window = window;
        return this;
    }

    public List<SloObjective> getObjectives() {
        return objectives;
    }

    public SloCriteria withObjectives(List<SloObjective> objectives) {
        this.objectives = objectives != null ? objectives : new ArrayList<>();
        return this;
    }

    public SloCriteria withObjectives(SloObjective... objectives) {
        this.objectives = new ArrayList<>();
        Collections.addAll(this.objectives, objectives);
        return this;
    }

    public Integer getMinimumSampleCount() {
        return minimumSampleCount;
    }

    public SloCriteria withMinimumSampleCount(Integer minimumSampleCount) {
        this.minimumSampleCount = minimumSampleCount;
        return this;
    }

    public Set<String> getUpstreamHosts() {
        return upstreamHosts;
    }

    public SloCriteria withUpstreamHosts(Set<String> upstreamHosts) {
        this.upstreamHosts = upstreamHosts != null ? new LinkedHashSet<>(upstreamHosts) : null;
        return this;
    }
}
