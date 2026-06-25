package org.mockserver.serialization.model;

import org.mockserver.load.LoadCapture;
import org.mockserver.load.LoadStep;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @author jamesdbloom
 */
public class LoadStepDTO extends ObjectWithReflectiveEqualsHashCodeToString implements DTO<LoadStep> {

    private HttpRequestDTO request;
    private DelayDTO thinkTime;
    private String name;
    private Map<String, String> labels;
    private List<LoadCapture> captures;
    private Double weight;

    public LoadStepDTO(LoadStep step) {
        if (step != null) {
            if (step.getRequest() != null) {
                request = new HttpRequestDTO(step.getRequest());
            }
            if (step.getThinkTime() != null) {
                thinkTime = new DelayDTO(step.getThinkTime());
            }
            name = step.getName();
            if (step.getLabels() != null && !step.getLabels().isEmpty()) {
                labels = new LinkedHashMap<>(step.getLabels());
            }
            if (step.getCaptures() != null && !step.getCaptures().isEmpty()) {
                captures = new ArrayList<>(step.getCaptures());
            }
            weight = step.getWeight();
        }
    }

    public LoadStepDTO() {
    }

    public LoadStep buildObject() {
        LoadStep step = new LoadStep();
        if (request != null) {
            HttpRequest builtRequest = request.buildObject();
            step.withRequest(builtRequest);
        }
        if (thinkTime != null) {
            step.withThinkTime(thinkTime.buildObject());
        }
        if (name != null) {
            step.withName(name);
        }
        if (labels != null && !labels.isEmpty()) {
            step.withLabels(labels);
        }
        if (captures != null && !captures.isEmpty()) {
            step.withCaptures(captures);
        }
        if (weight != null) {
            step.withWeight(weight);
        }
        return step;
    }

    public HttpRequestDTO getRequest() {
        return request;
    }

    public LoadStepDTO setRequest(HttpRequestDTO request) {
        this.request = request;
        return this;
    }

    public DelayDTO getThinkTime() {
        return thinkTime;
    }

    public LoadStepDTO setThinkTime(DelayDTO thinkTime) {
        this.thinkTime = thinkTime;
        return this;
    }

    public String getName() {
        return name;
    }

    public LoadStepDTO setName(String name) {
        this.name = name;
        return this;
    }

    public Map<String, String> getLabels() {
        return labels;
    }

    public LoadStepDTO setLabels(Map<String, String> labels) {
        this.labels = labels;
        return this;
    }

    public List<LoadCapture> getCaptures() {
        return captures;
    }

    public LoadStepDTO setCaptures(List<LoadCapture> captures) {
        this.captures = captures;
        return this;
    }

    public Double getWeight() {
        return weight;
    }

    public LoadStepDTO setWeight(Double weight) {
        this.weight = weight;
        return this;
    }
}
