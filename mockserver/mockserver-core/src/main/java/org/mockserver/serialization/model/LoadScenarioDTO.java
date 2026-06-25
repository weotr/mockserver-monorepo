package org.mockserver.serialization.model;

import org.mockserver.load.LoadFeeder;
import org.mockserver.load.LoadScenario;
import org.mockserver.load.LoadStep;
import org.mockserver.load.LoadThreshold;
import org.mockserver.model.HttpTemplate;
import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @author jamesdbloom
 */
public class LoadScenarioDTO extends ObjectWithReflectiveEqualsHashCodeToString implements DTO<LoadScenario> {

    private String name;
    private List<LoadStepDTO> steps = new ArrayList<>();
    private LoadProfileDTO profile;
    private HttpTemplate.TemplateType templateType;
    private Integer maxRequests;
    private Long startDelayMillis;
    private Map<String, String> labels;
    private List<LoadThresholdDTO> thresholds;
    private Boolean abortOnFail;
    private Long abortGraceMillis;
    private LoadPacingDTO pacing;
    private LoadFeeder feeder;
    private LoadScenario.StepSelection stepSelection;

    public LoadScenarioDTO(LoadScenario scenario) {
        if (scenario != null) {
            name = scenario.getName();
            if (scenario.getSteps() != null) {
                for (LoadStep step : scenario.getSteps()) {
                    steps.add(new LoadStepDTO(step));
                }
            }
            if (scenario.getProfile() != null) {
                profile = new LoadProfileDTO(scenario.getProfile());
            }
            templateType = scenario.getTemplateType();
            maxRequests = scenario.getMaxRequests();
            if (scenario.getStartDelayMillis() > 0) {
                startDelayMillis = scenario.getStartDelayMillis();
            }
            if (scenario.getLabels() != null && !scenario.getLabels().isEmpty()) {
                labels = new LinkedHashMap<>(scenario.getLabels());
            }
            if (scenario.getThresholds() != null && !scenario.getThresholds().isEmpty()) {
                thresholds = new ArrayList<>();
                for (LoadThreshold threshold : scenario.getThresholds()) {
                    thresholds.add(new LoadThresholdDTO(threshold));
                }
            }
            if (scenario.isAbortOnFail()) {
                abortOnFail = true;
            }
            if (scenario.getAbortGraceMillis() > 0) {
                abortGraceMillis = scenario.getAbortGraceMillis();
            }
            if (scenario.getPacing() != null && scenario.getPacing().getMode() != null
                && scenario.getPacing().getMode() != org.mockserver.load.LoadPacing.Mode.NONE) {
                pacing = new LoadPacingDTO(scenario.getPacing());
            }
            // The feeder is a simple POJO (rows / data+format / strategy) with no DTO indirection,
            // mirroring how LoadCapture is embedded directly. Echoing the model object preserves the
            // raw data/format source of truth, so a data-driven feeder round-trips without re-parsing.
            feeder = scenario.getFeeder();
            // Emit only when non-default so existing SEQUENTIAL scenarios serialize unchanged.
            if (scenario.getStepSelection() != null
                && scenario.getStepSelection() != LoadScenario.StepSelection.SEQUENTIAL) {
                stepSelection = scenario.getStepSelection();
            }
        }
    }

    public LoadScenarioDTO() {
    }

    public LoadScenario buildObject() {
        List<LoadStep> builtSteps = new ArrayList<>();
        if (steps != null) {
            for (LoadStepDTO step : steps) {
                builtSteps.add(step.buildObject());
            }
        }
        LoadScenario scenario = new LoadScenario()
            .withName(name)
            .withSteps(builtSteps)
            .withProfile(profile != null ? profile.buildObject() : null)
            .withTemplateType(templateType != null ? templateType : HttpTemplate.TemplateType.VELOCITY)
            .withMaxRequests(maxRequests)
            .withStartDelayMillis(startDelayMillis != null ? startDelayMillis : 0L)
            .withAbortOnFail(abortOnFail != null && abortOnFail)
            .withAbortGraceMillis(abortGraceMillis != null ? abortGraceMillis : 0L)
            .withPacing(pacing != null ? pacing.buildObject() : null)
            .withFeeder(feeder)
            .withStepSelection(stepSelection);
        if (labels != null && !labels.isEmpty()) {
            scenario.withLabels(labels);
        }
        if (thresholds != null && !thresholds.isEmpty()) {
            List<LoadThreshold> builtThresholds = new ArrayList<>();
            for (LoadThresholdDTO threshold : thresholds) {
                builtThresholds.add(threshold.buildObject());
            }
            scenario.withThresholds(builtThresholds);
        }
        return scenario;
    }

    public String getName() {
        return name;
    }

    public LoadScenarioDTO setName(String name) {
        this.name = name;
        return this;
    }

    public List<LoadStepDTO> getSteps() {
        return steps;
    }

    public LoadScenarioDTO setSteps(List<LoadStepDTO> steps) {
        this.steps = steps;
        return this;
    }

    public LoadProfileDTO getProfile() {
        return profile;
    }

    public LoadScenarioDTO setProfile(LoadProfileDTO profile) {
        this.profile = profile;
        return this;
    }

    public HttpTemplate.TemplateType getTemplateType() {
        return templateType;
    }

    public LoadScenarioDTO setTemplateType(HttpTemplate.TemplateType templateType) {
        this.templateType = templateType;
        return this;
    }

    public Integer getMaxRequests() {
        return maxRequests;
    }

    public LoadScenarioDTO setMaxRequests(Integer maxRequests) {
        this.maxRequests = maxRequests;
        return this;
    }

    public Long getStartDelayMillis() {
        return startDelayMillis;
    }

    public LoadScenarioDTO setStartDelayMillis(Long startDelayMillis) {
        this.startDelayMillis = startDelayMillis;
        return this;
    }

    public Map<String, String> getLabels() {
        return labels;
    }

    public LoadScenarioDTO setLabels(Map<String, String> labels) {
        this.labels = labels;
        return this;
    }

    public List<LoadThresholdDTO> getThresholds() {
        return thresholds;
    }

    public LoadScenarioDTO setThresholds(List<LoadThresholdDTO> thresholds) {
        this.thresholds = thresholds;
        return this;
    }

    public Boolean getAbortOnFail() {
        return abortOnFail;
    }

    public LoadScenarioDTO setAbortOnFail(Boolean abortOnFail) {
        this.abortOnFail = abortOnFail;
        return this;
    }

    public Long getAbortGraceMillis() {
        return abortGraceMillis;
    }

    public LoadScenarioDTO setAbortGraceMillis(Long abortGraceMillis) {
        this.abortGraceMillis = abortGraceMillis;
        return this;
    }

    public LoadPacingDTO getPacing() {
        return pacing;
    }

    public LoadScenarioDTO setPacing(LoadPacingDTO pacing) {
        this.pacing = pacing;
        return this;
    }

    public LoadFeeder getFeeder() {
        return feeder;
    }

    public LoadScenarioDTO setFeeder(LoadFeeder feeder) {
        this.feeder = feeder;
        return this;
    }

    public LoadScenario.StepSelection getStepSelection() {
        return stepSelection;
    }

    public LoadScenarioDTO setStepSelection(LoadScenario.StepSelection stepSelection) {
        this.stepSelection = stepSelection;
        return this;
    }
}
